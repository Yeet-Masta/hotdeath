package com.smorgasbork.hotdeath

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.smorgasbork.hotdeath.engine.CardColor
import com.smorgasbork.hotdeath.engine.CardDeck
import com.smorgasbork.hotdeath.engine.CardType
import com.smorgasbork.hotdeath.engine.Direction
import com.smorgasbork.hotdeath.engine.PenaltyType
import com.smorgasbork.hotdeath.engine.Prompt
import com.smorgasbork.hotdeath.engine.Seat
import com.smorgasbork.hotdeath.net.PlayerAction
import com.smorgasbork.hotdeath.net.PlayerView
import com.smorgasbork.hotdeath.net.TableView
import com.smorgasbork.hotdeath.net.ViewEvent
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws one player's view of the table and turns touches into
 * [PlayerAction]s. It holds no game logic and never sees cards its player
 * could not see: everything comes from a [TableView].
 *
 * The viewer always sits at the bottom; the other seats are rotated around
 * the table accordingly. All sizes derive from the view's size.
 */
@SuppressLint("ViewConstructor")
class GameTable(context: Context) : View(context) {

	private var view: TableView? = null
	private var deck: CardDeck? = null
	private var familyFriendly = true

	/** Sends the player's moves to the game. */
	var onAction: ((PlayerAction) -> Unit)? = null
	/** Long-press on a face-up card. */
	var onCardHelp: ((CardType) -> Unit)? = null
	/** Called after every layout pass, once sizes are known. */
	var onLayoutReady: (() -> Unit)? = null
	/** Display name for a seat. */
	var nameOf: (Seat) -> String = { CardText.seatName(context, it) }
	/** Space kept free under the viewer's hand for the action buttons. */
	var bottomMarginExternal = 0

	/** Where game messages should be shown (top of the viewer's area). */
	var messageAnchorY = 0
		private set

	// ── sprites ───────────────────────────────────────────────────────────────

	private val sprites = HashMap<Int, CardSprite>()
	private fun sprite(id: Int) = sprites.getOrPut(id) { CardSprite() }
	private val pointer = PointerSprite()
	private val ring = DirectionRing()
	private val chooser = ColorChooserSprite()
	private val moving = ArrayList<Pair<Int, Float>>()

	/** A card flying into the face-down draw pile, where it has no identity. */
	private class Flight(val sprite: CardSprite, val type: CardType?)
	private val flights = ArrayList<Flight>()

	// ── bitmaps & paints ──────────────────────────────────────────────────────

	private val faces = HashMap<CardType, Bitmap>()
	private val bmpBack = decode(R.drawable.card_back)
	private val bmpBadge = decode(R.drawable.card_badge)
	private val bmpEmoAggressor = decode(R.drawable.emoticon_aggressor)
	private val bmpEmoVictim = decode(R.drawable.emoticon_victim)
	private val bmpWinner = Seat.entries.map {
		decode(when (it) {
			Seat.SOUTH -> R.drawable.winner_south
			Seat.WEST -> R.drawable.winner_west
			Seat.NORTH -> R.drawable.winner_north
			Seat.EAST -> R.drawable.winner_east
		})
	}
	private var bmpPointer: Bitmap? = null
	private var bmpRing: Bitmap? = null
	private var bmpChooser: Bitmap? = null

	private val paintBitmap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
	private val paintTint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
	private val paintScore = textPaint(R.color.score_text)
	private val paintBadge = textPaint(R.color.card_badge_text)
	private val matrix = Matrix()
	private val camera = Camera()
	private val dst = RectF()
	private val textBounds = Rect()

	// ── layout (indexed by screen position: SOUTH = bottom = the viewer) ──────

	private var cardW = 0f
	private var cardH = 0f
	private var badge = 0f
	private var emoW = 0f
	private var emoH = 0f
	private var spacing = 0f
	private var spacingSouth = 0f
	private var stackStep = 0f
	private var maxCards = 7

	private val ptSeat = Array(4) { PointF() }
	private val ptEmoticon = Array(4) { PointF() }
	private val ptHeldOffsetBadge = Array(4) { PointF() }
	private val ptTableOffsetBadge = Array(4) { PointF() }
	private val ptHeldOverflowBadge = Array(4) { PointF() }
	private val ptTableOverflowBadge = Array(4) { PointF() }
	private val ptScore = Array(4) { PointF() }
	private val ptPointer = PointF()
	private val ptDrawPile = PointF()
	private val ptDiscardPile = PointF()
	private val ptDiscardBadge = PointF()
	private val ptWinner = PointF()
	private var winnerW = 0f
	private var winnerH = 0f

	private val heldRect = arrayOfNulls<RectF>(4)
	private val tableRect = arrayOfNulls<RectF>(4)
	private var drawPileRect = RectF()
	private var discardRect = RectF()

	// ── touch (screen positions) ──────────────────────────────────────────────

	private val heldOffset = IntArray(4)
	private val tableOffset = IntArray(4)
	private val heldDrag = IntArray(4)
	private val tableDrag = IntArray(4)

	private val touchDown = PointF()
	private var heldSteady = false
	private var waitingForHold = false
	private var touchAndHold = false
	private var touchDrawPile = false
	private var touchDiscardPile = false
	private var touchHeldPos: Seat? = null
	private var touchTablePos: Seat? = null

	// ── accessibility ─────────────────────────────────────────────────────────

	private class A11yCard(val id: Int, val rect: RectF)
	private val myCards = ArrayList<A11yCard>()
	private val a11y = TableAccessibility()

	init {
		setBackgroundResource(R.drawable.table_background)
		isFocusable = true
		isFocusableInTouchMode = true
		ViewCompat.setAccessibilityDelegate(this, a11y)
	}

	// =========================================================================
	// Seats on screen
	// =========================================================================

	private val me: Seat get() = view?.me ?: Seat.SOUTH

	/** Where [seat] sits on this screen. */
	private fun pos(seat: Seat): Seat = Seat.entries[(seat.index - me.index + 4) % 4]

	/** Who sits at screen position [p]. */
	private fun seatAt(p: Seat): Seat = Seat.entries[(p.index + me.index) % 4]

	private fun typeOf(id: Int): CardType? = if (id < 0) null else deck?.get(id)?.type

	// =========================================================================
	// Binding
	// =========================================================================

	/** Shows [v] without animation (first view, or after a rotation). */
	fun bind(v: TableView?, familyFriendly: Boolean) {
		this.familyFriendly = familyFriendly
		sprites.clear()
		faces.clear()
		flights.clear()
		view = null
		if (v == null) return
		setView(v)
		v.currPlayer?.let { pointer.snapTo(pos(it)) }
		ring.snap(v.indicatorDirection, v.indicatorColor.rgb())
		chooser.snap(v.prompt == Prompt.Color)
	}

	/** The table changed; [e] says how, if it should be animated. */
	fun update(v: TableView, e: ViewEvent?) {
		setView(v)
		e?.let(::onEvent)
	}

	private fun setView(v: TableView) {
		if (deck?.deckType != v.deckType) deck = CardDeck(v.deckType)
		view = v
		contentDescription = describe()
		a11y.invalidateRoot()
		invalidate()
	}

	private fun onEvent(e: ViewEvent) {
		val now = SystemClock.uptimeMillis()
		when (e) {
			ViewEvent.RoundReset -> {
				sprites.clear()
				ring.reset()
				chooser.snap(false)
			}
			is ViewEvent.Dealt -> {
				val o = dealOrigin(pos(e.dealer))
				sprite(e.card).move(o.x, o.y, false, e.durationMs, now)
			}
			is ViewEvent.Drawn -> sprite(e.card).move(ptDrawPile.x, ptDrawPile.y, false, e.durationMs, now)
			is ViewEvent.Flipped -> sprite(e.card).moveFromHere(e.durationMs, now, ptDrawPile.x, ptDrawPile.y)
			is ViewEvent.Discarded -> {
				val s = sprite(e.card)
				if (s.placed) s.moveFromHere(e.durationMs, now, 0f, 0f)
				else {
					// It came from a face-down hand (or the draw pile): start at that hand.
					val from = e.from?.let { ptSeat[pos(it).index] } ?: ptDrawPile
					s.move(from.x, from.y, false, e.durationMs, now)
				}
			}
			is ViewEvent.CheatSwap -> {
				sprite(e.newCard).move(ptDrawPile.x, ptDrawPile.y, false, e.durationMs, now)
				val old = sprite(e.oldCard)
				val f = CardSprite().apply { move(old.x, old.y, old.shownFaceUp, e.durationMs, now) }
				flights += Flight(f, typeOf(e.oldCard))
				sprites.remove(e.oldCard)
			}
			is ViewEvent.PointerMoved -> pointer.moveTo(pos(e.seat), e.direction, e.durationMs, now)
			is ViewEvent.IndicatorChanged -> ring.animateTo(e.direction, e.color.rgb(), e.durationMs, now)
			is ViewEvent.ColorChooser -> chooser.animate(e.show, e.durationMs, now)
			is ViewEvent.Message -> return
		}
		invalidate()
	}

	// =========================================================================
	// Layout
	// =========================================================================

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		if (w == 0 || h == 0) return
		val d = resources.displayMetrics.density

		// The table needs about 7 card widths across and 4.4 card heights down.
		val usableH = (h - bottomMarginExternal).toFloat()
		cardH = min(usableH / 4.4f, w / 7f / CARD_ASPECT).coerceIn(40 * d, 140 * d)
		cardW = cardH * CARD_ASPECT
		badge = cardW * 27f / 52f
		emoW = cardW * 27f / 52f
		emoH = cardW * 20f / 52f
		winnerW = cardW * 201f / 52f
		winnerH = cardW * 48f / 52f
		stackStep = cardH / 40f
		paintScore.textSize = cardH * 0.15f
		paintBadge.textSize = cardH * 0.175f

		val leftMargin = cardW / 4
		val rightMargin = cardW / 4
		var topMargin = cardH / 3
		var bottomMargin = cardH / 3 + bottomMarginExternal
		if (h < 4.5f * cardH) {
			topMargin = cardH / 4
			bottomMargin = cardH / 4 + bottomMarginExternal
		}

		paintScore.getTextBounds("0", 0, 1, textBounds)
		val textH = textBounds.height().toFloat()

		spacing = cardW / 2
		spacingSouth = 2 * cardW / 3

		// Layout 1: N/S hands sit between the E/W hands.
		val maxHuman1 = ((w - 2 * cardW - 2 * leftMargin - 2 * rightMargin - cardW) / spacingSouth).toInt() + 1
		val maxComputer1 = ((h - topMargin - bottomMargin - textH * 1.2f - cardH) / spacing).toInt() + 1
		// Layout 2: E/W hands sit between the N/S hands.
		val maxHuman2 = ((w - leftMargin - rightMargin - cardW) / spacingSouth).toInt() + 1
		val maxComputer2 = ((h - 2 * cardH - 2 * topMargin - 2 * bottomMargin - cardH) / spacing).toInt() + 1
		maxCards = max(1, max(min(maxComputer1, maxHuman1), min(maxComputer2, maxHuman2)))

		val maxWidthHand = (maxCards - 1) * spacing + cardW
		val maxHeightHand = (maxCards - 1) * spacing + cardH
		val maxWidthHandHuman = (maxCards - 1) * spacingSouth + cardW
		val vertCentre = (h - bottomMargin + topMargin) / 2

		at(Seat.NORTH).set(w / 2f, topMargin)
		at(Seat.EAST).set(w - (cardW + rightMargin), vertCentre)
		at(Seat.SOUTH).set(w / 2f, h - (cardH + bottomMargin))
		at(Seat.WEST).set(leftMargin, vertCentre)

		val ringSize = (4 * cardW).toInt()
		bmpPointer = vectorBitmap(R.drawable.pointer, ringSize)
		bmpRing = vectorBitmap(R.drawable.ring_segment, ringSize)
		bmpChooser = vectorBitmap(R.drawable.colorchooser, ringSize)

		ptPointer.set(w / 2f, vertCentre)
		ptDrawPile.set(w / 2f - cardW * 5 / 4, (h - bottomMargin + topMargin - cardH) / 2)
		ptDiscardPile.set(w / 2f + cardW / 4, ptDrawPile.y)
		ptDiscardBadge.set(ptDiscardPile.x + cardW - badge / 2, ptDiscardPile.y + cardH - badge / 2)

		val south = at(Seat.SOUTH)
		ptWinner.set(south.x - winnerW / 2, south.y - cardH / 2 * 3 - winnerH * 5 / 4)
		messageAnchorY = (south.y - 3 * cardH / 4).toInt()

		layoutEmoticons()
		layoutBadges(maxWidthHand, maxHeightHand, maxWidthHandHuman)
		layoutScores(maxHeightHand, textH)

		onLayoutReady?.invoke()
	}

	private fun at(p: Seat) = ptSeat[p.index]

	private fun layoutEmoticons() {
		val n = at(Seat.NORTH); val e = at(Seat.EAST); val s = at(Seat.SOUTH); val w = at(Seat.WEST)
		ptEmoticon[Seat.NORTH.index].set(n.x - emoW / 2, n.y + cardH * 11 / 10)
		ptEmoticon[Seat.EAST.index].set(e.x - emoW - cardW / 10, e.y - emoH / 2)
		ptEmoticon[Seat.SOUTH.index].set(s.x - emoW / 2, s.y - emoH - cardH / 10)
		ptEmoticon[Seat.WEST.index].set(w.x + cardW * 11 / 10, w.y - emoH / 2)
	}

	private fun layoutBadges(maxWidthHand: Float, maxHeightHand: Float, maxWidthHandHuman: Float) {
		val b = badge
		fun put(p: Seat, heldOff: PointF, tableDx: Float, tableDy: Float, heldOver: PointF) {
			val i = p.index
			ptHeldOffsetBadge[i].set(heldOff)
			ptTableOffsetBadge[i].set(heldOff.x + tableDx, heldOff.y + tableDy)
			ptHeldOverflowBadge[i].set(heldOver)
			ptTableOverflowBadge[i].set(heldOver.x + tableDx, heldOver.y + tableDy)
		}
		val n = at(Seat.NORTH)
		put(Seat.NORTH, PointF(n.x - maxWidthHand / 2 - b / 2, n.y - b / 2), 0f, cardH * 3 / 2,
			PointF(n.x + maxWidthHand / 2 - b / 2, n.y - b / 2))
		val e = at(Seat.EAST)
		put(Seat.EAST, PointF(e.x + cardW - b / 2, e.y - maxHeightHand / 2 - b / 2), -cardW * 3 / 2, 0f,
			PointF(e.x + cardW - b / 2, e.y + maxHeightHand / 2 - b / 2))
		val s = at(Seat.SOUTH)
		put(Seat.SOUTH, PointF(s.x - maxWidthHandHuman / 2 - b / 2, s.y + cardH - b / 2), 0f, -cardH * 5 / 3,
			PointF(s.x + maxWidthHandHuman / 2 - b / 2, s.y + cardH - b / 2))
		val w = at(Seat.WEST)
		put(Seat.WEST, PointF(w.x - b / 2, w.y - maxHeightHand / 2 - b / 2), cardW * 3 / 2, 0f,
			PointF(w.x - b / 2, w.y + maxHeightHand / 2 - b / 2))
	}

	private fun layoutScores(maxHeightHand: Float, textH: Float) {
		val th = textH * 1.1f
		val n = at(Seat.NORTH); val e = at(Seat.EAST); val s = at(Seat.SOUTH); val w = at(Seat.WEST)
		ptScore[Seat.NORTH.index].set(n.x, n.y - th)
		ptScore[Seat.EAST.index].set(e.x + cardW, e.y - maxHeightHand / 2 - th)
		ptScore[Seat.SOUTH.index].set(s.x, s.y + cardH + textH * 1.5f)
		ptScore[Seat.WEST.index].set(w.x, w.y - maxHeightHand / 2 - th)
	}

	/** Where cards fly in from when dealt by the player at screen position [p]. */
	private fun dealOrigin(p: Seat): PointF {
		val d = p.index
		val pt = ptSeat[d]
		val x = pt.x - cardW / 2 * (1 - d % 2) + cardW * 2 * (if (d % 2 == 1) d - 2 else 0)
		val y = pt.y - cardH / 2 * (d % 2) + cardH * 2 * (if (d % 2 == 0) 1 - d else 0)
		return PointF(x, y)
	}

	// =========================================================================
	// Drawing
	// =========================================================================

	override fun onDraw(canvas: Canvas) {
		val v = view ?: return
		if (cardW == 0f) return
		val now = SystemClock.uptimeMillis()
		var animating = pointer.update(now) or ring.update(now) or chooser.update(now)

		drawScores(canvas, v)

		val cp = v.currPlayer
		if (cp != null && !v.roundComplete) drawDirectionIndicator(canvas, pos(cp))

		myCards.clear()
		if (cp != null && !v.fastForward) {
			moving.clear()
			drawPileRect = drawDrawPile(canvas, v)
			discardRect = drawDiscardPile(canvas, v, now)

			for (i in 0 until 4) {
				val p = v.players[(cp.index + i) % 4]
				if (p.active) drawHand(canvas, p, now)
			}

			// Cards in flight go over everything else.
			for ((id, flip) in moving) {
				val s = sprite(id)
				drawCard(canvas, typeOf(id), s.x, s.y, s.shownFaceUp, flip)
			}
			val it = flights.iterator()
			while (it.hasNext()) {
				val f = it.next()
				val flip = f.sprite.frame(now, ptDrawPile.x, ptDrawPile.y, false)
				drawCard(canvas, f.type, f.sprite.x, f.sprite.y, f.sprite.shownFaceUp, flip)
				if (!f.sprite.moving) it.remove()
			}
			animating = animating || moving.isNotEmpty() || flights.isNotEmpty()

			drawColorChooser(canvas)
			v.gameWinner?.let { drawBitmap(canvas, bmpWinner[pos(it).index], ptWinner.x, ptWinner.y, winnerW, winnerH) }
			drawPenalty(canvas, v)
		}

		if (animating) postInvalidateOnAnimation()
	}

	private fun drawDirectionIndicator(canvas: Canvas, p: Seat) {
		val bp = bmpPointer ?: return
		val br = bmpRing ?: return
		val sign = if (ring.direction == Direction.CLOCKWISE) 1 else -1
		for (i in 0 until DirectionRing.SEGMENTS) {
			matrix.setTranslate(-br.width / 2f, -br.height / 2f)
			matrix.postRotate(p.index * 90f)
			if (ring.direction == Direction.COUNTERCLOCKWISE) {
				if (p.number % 2 == 0) matrix.postScale(1f, -1f) else matrix.postScale(-1f, 1f)
			}
			matrix.postRotate(i * (360f / DirectionRing.SEGMENTS) * sign)
			matrix.postTranslate(ptPointer.x, ptPointer.y)
			paintTint.colorFilter = PorterDuffColorFilter(ring.segmentColors[i], PorterDuff.Mode.MULTIPLY)
			canvas.drawBitmap(br, matrix, paintTint)
		}

		matrix.reset()
		matrix.postTranslate(-bp.width / 2f, -bp.height / 2f)
		matrix.postRotate(pointer.rot)
		matrix.postScale(pointer.scale, pointer.scale)
		matrix.postTranslate(ptPointer.x, ptPointer.y)
		paintTint.colorFilter = PorterDuffColorFilter(CardColor.WILD.rgb(), PorterDuff.Mode.MULTIPLY)
		canvas.drawBitmap(bp, matrix, paintTint)
	}

	private fun drawColorChooser(canvas: Canvas) {
		val b = bmpChooser ?: return
		for (i in 0 until ColorChooserSprite.SEGMENTS) {
			val scale = chooser.segmentScales[i]
			if (scale <= 0f) continue
			matrix.setTranslate(-b.width / 2f, -b.height / 2f)
			matrix.postRotate(i * (360f / ColorChooserSprite.SEGMENTS))
			matrix.postScale(scale, scale)
			matrix.postTranslate(ptPointer.x, ptPointer.y)
			paintTint.colorFilter = PorterDuffColorFilter(chooser.segmentColors[i], PorterDuff.Mode.MULTIPLY)
			canvas.drawBitmap(b, matrix, paintTint)
		}
	}

	private fun stackSkip(v: TableView) = if ((deck?.size ?: 0) > 108) 32 else 16

	/** The draw pile is all backs: no card in it has an identity on this screen. */
	private fun drawDrawPile(canvas: Canvas, v: TableView): RectF {
		val pt = ptDrawPile
		val n = v.drawPileSize
		if (n == 0) return RectF(pt.x, pt.y, pt.x + cardW, pt.y + cardH)
		val skip = stackSkip(v)
		var x = pt.x
		var y = pt.y
		for (i in 0 until n) {
			if (i % skip == 0 || i >= n - 2) {
				val off = (i / skip) * stackStep
				x = pt.x + off
				y = pt.y + off
				drawCard(canvas, null, x, y, false, 0f)
			}
		}
		return RectF(pt.x, pt.y, x + cardW, y + cardH)
	}

	private fun drawDiscardPile(canvas: Canvas, v: TableView, now: Long): RectF {
		val pt = ptDiscardPile
		val n = v.discard.size
		if (n == 0) return RectF(pt.x, pt.y, pt.x + cardW, pt.y + cardH)
		val skip = stackSkip(v)
		var x = pt.x
		var y = pt.y
		v.discard.forEachIndexed { i, id ->
			val off = (i / skip) * stackStep
			val s = sprite(id)
			val flip = s.frame(now, pt.x + off, pt.y + off, true)
			if (s.moving) {
				moving += id to flip
			} else if (i % skip == 0 || i >= n - 2) {
				x = pt.x + off
				y = pt.y + off
				drawCard(canvas, typeOf(id), x, y, s.shownFaceUp, flip)
			}
		}
		return RectF(pt.x, pt.y, x + cardW, y + cardH)
	}

	private class HandLayout(val x: Float, val y: Float, val dx: Float, val dy: Float, val rect: RectF)

	private fun spacingFor(p: Seat) = if (p == Seat.SOUTH) spacingSouth else spacing

	private fun drawHand(canvas: Canvas, pv: PlayerView, now: Long) {
		val p = pos(pv.seat)
		val i = p.index
		val table = pv.table
		val held = pv.held

		heldOffset[i] = clamp(heldOffset[i], 0, held.size - maxCards)
		tableOffset[i] = clamp(tableOffset[i], 0, table.size - maxCards)
		val hOff = clamp(heldOffset[i] + heldDrag[i], 0, held.size - maxCards)
		val tOff = clamp(tableOffset[i] + tableDrag[i], 0, table.size - maxCards)
		val sp = spacingFor(p)
		val mine = pv.seat == me

		val tl = handLayout(p, true, min(table.size - tableOffset[i], maxCards), sp)
		tableRect[i] = tl.rect
		layCards(canvas, table, tOff, tl, now, mine = false)

		val hl = handLayout(p, false, min(held.size - heldOffset[i], maxCards), sp)
		heldRect[i] = hl.rect
		layCards(canvas, held, hOff, hl, now, mine, if (mine) view?.legal ?: emptyList() else emptyList())

		drawCountBadge(canvas, tOff, ptTableOffsetBadge[i])
		drawCountBadge(canvas, hOff, ptHeldOffsetBadge[i])
		drawCountBadge(canvas, table.size - tOff - maxCards, ptTableOverflowBadge[i])
		drawCountBadge(canvas, held.size - hOff - maxCards, ptHeldOverflowBadge[i])
	}

	private fun handLayout(p: Seat, tableCards: Boolean, numShowing: Int, sp: Float): HandLayout {
		val pt = ptSeat[p.index]
		val len = if (numShowing < 1) 0f else (numShowing - 1) * sp
		return when (p) {
			Seat.SOUTH, Seat.NORTH -> {
				val w = if (numShowing < 1) 0f else len + cardW
				val x = pt.x - w / 2
				val y = when {
					!tableCards -> pt.y
					p == Seat.SOUTH -> pt.y - cardH * 2 / 3
					else -> pt.y + cardH / 2
				}
				HandLayout(x, y, sp, 0f, RectF(x, y, x + w, y + cardH))
			}
			Seat.WEST, Seat.EAST -> {
				val hh = if (numShowing < 1) 0f else len + cardH
				val x = when {
					!tableCards -> pt.x
					p == Seat.WEST -> pt.x + cardW / 2
					else -> pt.x - cardW / 2
				}
				val y = pt.y - hh / 2
				HandLayout(x, y, 0f, sp, RectF(x, y, x + cardW, y + hh))
			}
		}
	}

	/** [raised] cards (the ones you may play now) sit a little higher. */
	private fun layCards(canvas: Canvas, ids: List<Int>, offset: Int, l: HandLayout, now: Long, mine: Boolean, raised: List<Int> = emptyList()) {
		var x = l.x
		var y = l.y
		val stop = min(offset + maxCards, ids.size)
		for ((j, id) in ids.withIndex()) {
			val face = id >= 0
			val s = sprite(id)
			when {
				j in offset until stop -> {
					val lift = if (id in raised) cardH / 8 else 0f
					val flip = s.frame(now, x, y - lift, face)
					if (s.moving) moving += id to flip else drawCard(canvas, typeOf(id), s.x, s.y, s.shownFaceUp, flip)
					if (mine) myCards += A11yCard(id, RectF(x, y, x + if (j == stop - 1) cardW else l.dx, y + cardH))
					x += l.dx
					y += l.dy
				}
				s.moving -> moving += id to s.frame(now, x, y, face)
				else -> s.park(x, y, face)
			}
		}
	}

	private fun drawCountBadge(canvas: Canvas, count: Int, pt: PointF) {
		if (count > 0) drawBadgeWithText(canvas, pt, count.toString())
	}

	private fun drawBadgeWithText(canvas: Canvas, pt: PointF, text: String) {
		drawBitmap(canvas, bmpBadge, pt.x, pt.y, badge, badge)
		paintBadge.getTextBounds(text, 0, text.length, textBounds)
		canvas.drawText(text, pt.x + badge / 2, pt.y + badge / 2 + textBounds.height() / 2f, paintBadge)
	}

	private fun drawCard(canvas: Canvas, type: CardType?, x: Float, y: Float, faceUp: Boolean, flip: Float) {
		val b = if (faceUp && type != null) face(type) else bmpBack
		matrix.reset()
		camera.save()
		camera.rotateY(flip)
		camera.getMatrix(matrix)
		camera.restore()
		matrix.preTranslate(-cardW / 2, -cardH / 2)
		matrix.preScale(cardW / b.width, cardH / b.height)
		matrix.postTranslate(x + cardW / 2, y + cardH / 2)
		canvas.drawBitmap(b, matrix, paintBitmap)
	}

	private fun drawBitmap(canvas: Canvas, b: Bitmap, x: Float, y: Float, w: Float, h: Float) {
		dst.set(x, y, x + w, y + h)
		canvas.drawBitmap(b, null, dst, paintBitmap)
	}

	private fun drawPenalty(canvas: Canvas, v: TableView) {
		val p = v.penalty ?: return
		if (p.type == PenaltyType.CARD) {
			drawBadgeWithText(canvas, ptDiscardBadge, "+" + p.numCards)
		} else if (p.origCard >= 0 && p.origCard != v.lastPlayed) {
			typeOf(p.origCard)?.let { t ->
				val b = face(t)
				drawBitmap(canvas, b, ptDiscardBadge.x, ptDiscardBadge.y, badge, badge * b.height / b.width)
			}
		}
		drawEmoticon(canvas, v, p.victim, bmpEmoVictim)
		drawEmoticon(canvas, v, p.generator, bmpEmoAggressor)
	}

	private fun drawEmoticon(canvas: Canvas, v: TableView, seat: Seat?, emoticon: Bitmap) {
		if (seat == null) return
		val p = pos(seat)
		val pt = ptEmoticon[p.index]
		var dx = 0f
		var dy = 0f
		if (v.player(seat).table.isNotEmpty()) {
			when (p) {
				Seat.SOUTH -> dy -= cardH * 2 / 3
				Seat.WEST -> dx += cardW / 2
				Seat.NORTH -> dy += cardH / 2
				Seat.EAST -> dx -= cardW / 2
			}
		}
		drawBitmap(canvas, emoticon, pt.x + dx, pt.y + dy, emoW, emoH)
	}

	private fun drawScores(canvas: Canvas, v: TableView) {
		for (pv in v.players) {
			val p = pos(pv.seat)
			paintScore.textAlign = when (p) {
				Seat.SOUTH, Seat.NORTH -> Paint.Align.CENTER
				Seat.WEST -> Paint.Align.LEFT
				Seat.EAST -> Paint.Align.RIGHT
			}
			val pt = ptScore[p.index]
			val score = scoreText(v, pv)
			// Name other people so everyone knows who sits where.
			val label = if (v.name(pv.seat) != null && pv.seat != me) "${nameOf(pv.seat)}: $score" else score
			canvas.drawText(label, pt.x, pt.y, paintScore)
		}
	}

	private fun scoreText(v: TableView, p: PlayerView): String {
		if (!v.roundComplete) return p.totalScore.toString()
		val base = p.totalScore - p.lastScore - p.lastVirusPenalty
		val template = if (p.lastScore < 0) R.string.msg_round_score_negative else R.string.msg_round_score_positive
		return context.getString(template, base, abs(p.lastScore), p.lastVirusPenalty, p.totalScore)
	}

	// =========================================================================
	// Touch
	// =========================================================================

	private val holdTask = Runnable {
		if (!waitingForHold) return@Runnable
		touchAndHold = true
		val v = view ?: return@Runnable
		if (!v.myTurn && !v.roundComplete) return@Runnable
		val id = findTouchedCard(v, touchDown) ?: return@Runnable
		val t = typeOf(id) ?: return@Runnable
		if (!sprite(id).shownFaceUp) return@Runnable
		performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
		onCardHelp?.invoke(t)
	}

	@SuppressLint("ClickableViewAccessibility")
	override fun onTouchEvent(event: MotionEvent): Boolean {
		val v = view ?: return super.onTouchEvent(event)
		return when (event.actionMasked) {
			MotionEvent.ACTION_CANCEL -> {
				cancelHold()
				true
			}
			MotionEvent.ACTION_DOWN -> touchDown(v, event.x, event.y)
			MotionEvent.ACTION_UP -> touchUp(v)
			MotionEvent.ACTION_MOVE -> touchMove(event.x, event.y)
			else -> super.onTouchEvent(event)
		}
	}

	private fun act(a: PlayerAction) = onAction?.invoke(a)

	private fun touchDown(v: TableView, x: Float, y: Float): Boolean {
		touchDown.set(x, y)
		touchAndHold = false
		heldSteady = true
		touchDiscardPile = false
		touchDrawPile = false
		touchHeldPos = null
		touchTablePos = null

		for (p in TOUCH_ORDER) {
			if (heldRect[p.index]?.contains(x, y) == true) { touchHeldPos = p; break }
			if (tableRect[p.index]?.contains(x, y) == true) { touchTablePos = p; break }
		}
		if (touchHeldPos != null || touchTablePos != null) {
			scheduleHold()
			return true
		}

		if (v.prompt == Prompt.Color && hypot(x - ptPointer.x, y - ptPointer.y) <= 1.5f * cardW) {
			act(PlayerAction.Color(colorAt(x, y)))
		}
		if (drawPileRect.contains(x, y)) {
			touchDrawPile = true
			scheduleHold()
		}
		if (discardRect.contains(x, y)) {
			touchDiscardPile = true
			scheduleHold()
		}
		return true
	}

	private fun touchUp(v: TableView): Boolean {
		if (touchAndHold) return true
		cancelHold()
		val p = touchHeldPos ?: touchTablePos
		if (heldSteady) {
			when {
				p != null -> if (p == Seat.SOUTH && v.roundIsActive) findTouchedCardInHand(v, p, touchDown)?.let { act(PlayerAction.Play(it)) }
				touchDrawPile -> act(if (v.waitingToStartRound) PlayerAction.NextRound else PlayerAction.Draw)
				touchDiscardPile -> act(PlayerAction.DiscardTap)
			}
			return true
		}
		if (p != null) {
			val i = p.index
			val pv = v.player(seatAt(p))
			heldOffset[i] = clamp(heldOffset[i] + heldDrag[i], 0, pv.held.size - maxCards)
			tableOffset[i] = clamp(tableOffset[i] + tableDrag[i], 0, pv.table.size - maxCards)
			heldDrag[i] = 0
			tableDrag[i] = 0
			touchHeldPos = null
			touchTablePos = null
			invalidate()
		}
		return true
	}

	private fun touchMove(x: Float, y: Float): Boolean {
		val p = touchHeldPos ?: touchTablePos ?: return false
		val sp = spacingFor(p)
		val cardOffset = if (p == Seat.NORTH || p == Seat.SOUTH) ((x - touchDown.x) / (sp / 2)).toInt()
		else ((y - touchDown.y) / sp).toInt()

		if (cardOffset != 0 && heldSteady) {
			cancelHold()
			heldSteady = false
		}
		if (touchHeldPos == p) heldDrag[p.index] = -cardOffset
		else tableDrag[p.index] = -cardOffset
		invalidate()
		return true
	}

	private fun scheduleHold() {
		waitingForHold = true
		removeCallbacks(holdTask)
		postDelayed(holdTask, HOLD_MS)
	}

	private fun cancelHold() {
		waitingForHold = false
		removeCallbacks(holdTask)
	}

	override fun onDetachedFromWindow() {
		cancelHold()
		super.onDetachedFromWindow()
	}

	/** Quadrants clockwise from top-left: red, green, blue, yellow. */
	private fun colorAt(x: Float, y: Float): CardColor = if (y < ptPointer.y) {
		if (x < ptPointer.x) CardColor.RED else CardColor.GREEN
	} else {
		if (x > ptPointer.x) CardColor.BLUE else CardColor.YELLOW
	}

	private fun findTouchedCard(v: TableView, pt: PointF): Int? {
		if (touchDiscardPile) return if (discardRect.contains(pt.x, pt.y)) v.discard.lastOrNull() else null
		if (touchDrawPile) return null
		val p = touchHeldPos ?: touchTablePos ?: return null
		return findTouchedCardInHand(v, p, pt)
	}

	private fun findTouchedCardInHand(v: TableView, p: Seat, pt: PointF): Int? {
		val i = p.index
		val pv = v.player(seatAt(p))
		val sp = spacingFor(p)
		fun pick(rect: RectF?, ids: List<Int>, offset: Int): Int? {
			if (rect == null || !rect.contains(pt.x, pt.y) || ids.isEmpty()) return null
			val along = if (p == Seat.NORTH || p == Seat.SOUTH) pt.x - rect.left else pt.y - rect.top
			val idx = clamp((along / sp).toInt(), 0, ids.size - offset - 1)
			return ids.getOrNull(idx + offset)
		}
		return pick(heldRect[i], pv.held, heldOffset[i]) ?: pick(tableRect[i], pv.table, tableOffset[i])
	}

	// =========================================================================
	// Accessibility
	// =========================================================================

	override fun dispatchHoverEvent(event: MotionEvent): Boolean =
		a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

	private fun cardName(id: Int) = typeOf(id)?.let { CardText.cardName(context, it, familyFriendly) }
		?: context.getString(R.string.a11y_card_back)

	/** Spoken summary of the table. */
	private fun describe(): String {
		val v = view ?: return ""
		val cp = v.currPlayer
		if (v.roundComplete || cp == null) {
			if (cp == null) return context.getString(R.string.a11y_table_dealing)
			val scores = v.players.joinToString(", ") {
				context.getString(R.string.a11y_score_entry, nameOf(it.seat), it.totalScore)
			}
			return context.getString(R.string.a11y_table_round_over, scores)
		}
		val hand = v.player(me).let { it.table + it.held }
		return context.getString(
			R.string.a11y_table_turn,
			nameOf(cp),
			if (v.lastPlayed >= 0) cardName(v.lastPlayed) else "",
			CardText.colorName(context, v.currColor),
			hand.size,
			hand.joinToString(", ") { cardName(it) },
		)
	}

	/**
	 * Exposes the viewer's cards, the piles and the color chooser as virtual
	 * views so TalkBack users can play.
	 */
	private inner class TableAccessibility : ExploreByTouchHelper(this@GameTable) {

		private fun chooserRect(i: Int): RectF {
			val r = 1.5f * cardW
			val left = i == 0 || i == 3
			val top = i == 0 || i == 1
			return RectF(
				if (left) ptPointer.x - r else ptPointer.x, if (top) ptPointer.y - r else ptPointer.y,
				if (left) ptPointer.x else ptPointer.x + r, if (top) ptPointer.y else ptPointer.y + r,
			)
		}

		private val chooserShown get() = view?.prompt == Prompt.Color

		override fun getVirtualViewAt(x: Float, y: Float): Int {
			if (chooserShown) {
				for (i in 0 until 4) if (chooserRect(i).contains(x, y)) return ID_CHOOSER + i
			}
			myCards.forEachIndexed { i, a -> if (a.rect.contains(x, y)) return ID_CARD + i }
			if (drawPileRect.contains(x, y)) return ID_DRAW_PILE
			if (discardRect.contains(x, y)) return ID_DISCARD_PILE
			return ExploreByTouchHelper.INVALID_ID
		}

		override fun getVisibleVirtualViews(ids: MutableList<Int>) {
			if (view?.currPlayer == null) return
			if (chooserShown) for (i in 0 until 4) ids += ID_CHOOSER + i
			for (i in myCards.indices) ids += ID_CARD + i
			ids += ID_DRAW_PILE
			ids += ID_DISCARD_PILE
		}

		override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
			val v = view
			val r: RectF
			when {
				id >= ID_CHOOSER -> {
					val i = id - ID_CHOOSER
					r = chooserRect(i)
					node.contentDescription = CardText.colorName(context, ColorChooserSprite.COLORS[i])
					node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
				}
				id >= ID_CARD -> {
					val a = myCards.getOrNull(id - ID_CARD)
					r = a?.rect ?: RectF()
					node.contentDescription = a?.let { cardName(it.id) } ?: ""
					node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
					node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
				}
				id == ID_DRAW_PILE -> {
					r = drawPileRect
					node.contentDescription = context.getString(R.string.a11y_draw_pile, v?.drawPileSize ?: 0)
					node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
				}
				else -> {
					r = discardRect
					val top = v?.discard?.lastOrNull()
					node.contentDescription = context.getString(R.string.a11y_discard_pile, top?.let(::cardName) ?: "")
					if (top != null) node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
				}
			}
			val bounds = Rect()
			r.roundOut(bounds)
			if (bounds.isEmpty) bounds.set(0, 0, 1, 1)
			@Suppress("DEPRECATION")
			node.setBoundsInParent(bounds)
		}

		override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
			val v = view ?: return false
			val click = action == AccessibilityNodeInfoCompat.ACTION_CLICK
			val longClick = action == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK
			when {
				id >= ID_CHOOSER && click -> act(PlayerAction.Color(ColorChooserSprite.COLORS[id - ID_CHOOSER]))
				id >= ID_CARD -> {
					val c = myCards.getOrNull(id - ID_CARD)?.id ?: return false
					if (click && v.roundIsActive) act(PlayerAction.Play(c))
					else if (longClick) typeOf(c)?.let { onCardHelp?.invoke(it) }
					else return false
				}
				id == ID_DRAW_PILE && click -> act(if (v.waitingToStartRound) PlayerAction.NextRound else PlayerAction.Draw)
				id == ID_DISCARD_PILE && longClick -> v.discard.lastOrNull()?.let(::typeOf)?.let { onCardHelp?.invoke(it) }
				else -> return false
			}
			return true
		}
	}

	// =========================================================================
	// Resources
	// =========================================================================

	private fun face(t: CardType): Bitmap = faces.getOrPut(t) { decode(CardText.drawable(t, familyFriendly)) }

	private fun decode(@DrawableRes id: Int): Bitmap = BitmapFactory.decodeResource(resources, id)

	private fun vectorBitmap(@DrawableRes id: Int, size: Int): Bitmap {
		val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
		ContextCompat.getDrawable(context, id)?.apply {
			setBounds(0, 0, size, size)
			draw(Canvas(bmp))
		}
		return bmp
	}

	private fun textPaint(colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = ContextCompat.getColor(context, colorRes)
		typeface = Typeface.DEFAULT_BOLD
		textAlign = Paint.Align.CENTER
	}

	companion object {
		/** Card width / height of the card artwork. */
		private const val CARD_ASPECT = 52f / 80f
		private const val HOLD_MS = 1000L
		/** Screen positions, the viewer's own hand first. */
		private val TOUCH_ORDER = listOf(Seat.SOUTH, Seat.WEST, Seat.NORTH, Seat.EAST)

		private const val ID_DRAW_PILE = 1
		private const val ID_DISCARD_PILE = 2
		private const val ID_CARD = 100
		private const val ID_CHOOSER = 1000

		private fun clamp(v: Int, lo: Int, hi: Int): Int = max(lo, min(v, hi))
	}
}
