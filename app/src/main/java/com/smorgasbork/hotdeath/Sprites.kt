package com.smorgasbork.hotdeath

import android.graphics.Color
import com.smorgasbork.hotdeath.engine.CardColor
import com.smorgasbork.hotdeath.engine.Direction
import com.smorgasbork.hotdeath.engine.Seat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Animated pieces of the table. Each table owns its own instances (the old
 * code used process-wide singletons). All of them are driven from
 * GameTable.onDraw with a frame timestamp, on the main thread only.
 */

/** Screen color for a card color; transparent for null. */
fun CardColor?.rgb(): Int = when (this) {
	CardColor.RED -> Color.rgb(203, 13, 40)
	CardColor.GREEN -> Color.rgb(4, 133, 64)
	CardColor.BLUE -> Color.rgb(4, 86, 165)
	CardColor.YELLOW -> Color.rgb(233, 146, 6)
	CardColor.WILD -> Color.rgb(221, 220, 215)
	null -> Color.TRANSPARENT
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun progress(now: Long, start: Long, duration: Long): Float =
	if (duration <= 0) 1f else ((now - start).toFloat() / duration).coerceIn(0f, 1f)

/**
 * Where a card is drawn. The card's resting place is worked out from game
 * state every frame; a sprite only remembers where a move started, so a
 * card heading into a hand follows that hand if it re-lays out mid-flight.
 */
class CardSprite {
	var x = 0f
		private set
	var y = 0f
		private set
	/** The face that was showing when the card was last drawn. */
	var shownFaceUp = false
		private set
	var placed = false
		private set

	private var fromX = 0f
	private var fromY = 0f
	private var fromFace = false
	private var start = 0L
	private var duration = 0L

	var moving = false
		private set

	fun move(fromX: Float, fromY: Float, fromFace: Boolean, durationMs: Long, now: Long) {
		this.fromX = fromX
		this.fromY = fromY
		this.fromFace = fromFace
		start = now
		duration = durationMs
		moving = durationMs > 0
		placed = true
	}

	/** Starts a move from wherever the card was last drawn. */
	fun moveFromHere(durationMs: Long, now: Long, fallbackX: Float, fallbackY: Float) {
		if (placed) move(x, y, shownFaceUp, durationMs, now)
		else move(fallbackX, fallbackY, false, durationMs, now)
	}

	/** Puts the card somewhere without drawing it (hidden behind an overflow badge). */
	fun park(px: Float, py: Float, face: Boolean) {
		if (moving) return
		x = px
		y = py
		shownFaceUp = face
		placed = true
	}

	/**
	 * Advances to [now] for a card resting at ([tx], [ty]) with face [restFace].
	 * Returns the Y-axis flip angle to draw it with.
	 */
	fun frame(now: Long, tx: Float, ty: Float, restFace: Boolean): Float {
		placed = true
		if (!moving) {
			x = tx; y = ty; shownFaceUp = restFace
			return 0f
		}
		val t = progress(now, start, duration)
		if (t >= 1f) moving = false
		x = lerp(fromX, tx, t)
		y = lerp(fromY, ty, t)
		if (fromFace == restFace) {
			shownFaceUp = restFace
			return 0f
		}
		// Turn edge-on showing the old face, then come back showing the new one.
		return when {
			t < FLIP_MID -> { shownFaceUp = fromFace; 90f * t / FLIP_MID }
			t < FLIP_END -> { shownFaceUp = restFace; -90f * (1f - (t - FLIP_MID) / (FLIP_END - FLIP_MID)) }
			else -> { shownFaceUp = restFace; 0f }
		}
	}

	private companion object {
		const val FLIP_END = 0.875f
		const val FLIP_MID = FLIP_END / 2f
	}
}

/** The arrow in the middle of the table that points at the current player. */
class PointerSprite {
	var rot = 0f
		private set
	var scale = 1f
		private set

	private var startRot = 0f
	private var targetRot = 0f
	private var jump = false
	private var start = 0L
	private var duration = 0L
	private var active = false

	fun snapTo(seat: Seat) {
		rot = seat.index * 90f
		scale = 1f
		active = false
	}

	/** Sweeps in [direction] to [seat]; [Direction.NONE] shrinks and regrows instead. */
	fun moveTo(seat: Seat, direction: Direction, durationMs: Long, now: Long) {
		if (durationMs <= 0) {
			snapTo(seat)
			return
		}
		startRot = rot
		targetRot = seat.index * 90f
		jump = direction == Direction.NONE
		if (direction == Direction.CLOCKWISE && targetRot <= startRot) targetRot += 360f
		else if (direction == Direction.COUNTERCLOCKWISE && targetRot >= startRot) targetRot -= 360f
		start = now
		duration = durationMs
		active = true
	}

	/** Returns true while still animating. */
	fun update(now: Long): Boolean {
		if (!active) return false
		val t = progress(now, start, duration)
		if (t >= 1f) {
			rot = ((targetRot % 360f) + 360f) % 360f
			scale = 1f
			active = false
			return false
		}
		if (jump) {
			rot = if (t < 0.5f) startRot else targetRot
			scale = abs(1f - 2f * t)
		} else {
			rot = lerp(startRot, targetRot, t)
			scale = 1f
		}
		return true
	}
}

/** The ring of segments showing play direction and the current color. */
class DirectionRing {
	val segmentColors = IntArray(SEGMENTS)
	var direction = Direction.NONE
		private set
	private var color = Color.TRANSPARENT

	private var startColor = 0
	private var targetColor = 0
	private var startDirection = Direction.NONE
	private var targetDirection = Direction.NONE
	private var start = 0L
	private var duration = 0L
	private var active = false

	fun snap(dir: Direction, c: Int) {
		direction = dir
		color = c
		segmentColors.fill(c)
		active = false
	}

	fun reset() = snap(Direction.NONE, Color.TRANSPARENT)

	fun animateTo(dir: Direction, c: Int, durationMs: Long, now: Long) {
		if (durationMs <= 0) {
			snap(dir, c)
			return
		}
		startColor = color
		targetColor = c
		startDirection = direction
		targetDirection = dir
		start = now
		// Direction changes get double time so the wipe-out / wipe-in is visible.
		duration = if (dir != direction) durationMs * 2 else durationMs
		active = true
	}

	fun update(now: Long): Boolean {
		if (!active) return false
		val t = progress(now, start, duration)
		if (targetDirection != startDirection) {
			if (direction != targetDirection) {
				if (t <= 0.5f) {
					for (i in 0 until SEGMENTS) {
						val a = (min(1f, max(0f, SEGMENTS - i - 2f * SEGMENTS * t)) * Color.alpha(color)).toInt()
						segmentColors[i] = withAlpha(color, a)
					}
				} else {
					direction = targetDirection
					color = targetColor
				}
			} else {
				for (i in 0 until SEGMENTS) {
					val a = (255 * min(1f, max(0f, 2f * SEGMENTS * t - SEGMENTS - i))).toInt()
					segmentColors[i] = withAlpha(color, a)
				}
			}
		} else if (targetColor != startColor) {
			for (i in 0 until SEGMENTS) segmentColors[i] = if (t * SEGMENTS >= i + 1) targetColor else startColor
		}
		if (t >= 1f) {
			snap(targetDirection, targetColor)
			return false
		}
		return true
	}

	companion object {
		const val SEGMENTS = 12
		private fun withAlpha(c: Int, a: Int) = (a shl 24) or (c and 0x00FFFFFF)
	}
}

/** Four colored quadrants that fan out when the human must pick a color. */
class ColorChooserSprite {
	val segmentColors = IntArray(SEGMENTS)
	val segmentScales = FloatArray(SEGMENTS)
	var shown = false
		private set

	private var start = 0L
	private var duration = 0L
	private var active = false

	fun snap(show: Boolean) {
		shown = show
		active = false
		apply(if (show) 1f else 0f)
	}

	fun animate(show: Boolean, durationMs: Long, now: Long) {
		if (durationMs <= 0) {
			snap(show)
			return
		}
		shown = show
		start = now
		duration = durationMs
		active = true
	}

	fun update(now: Long): Boolean {
		if (!active) return false
		val t = progress(now, start, duration)
		for (i in 0 until SEGMENTS) {
			val s = min(1f, max(0f, 2f * SEGMENTS * t - SEGMENTS - i))
			set(i, if (shown) s else 1f - s)
		}
		if (t >= 1f) active = false
		return active
	}

	private fun apply(s: Float) {
		for (i in 0 until SEGMENTS) set(i, s)
	}

	private fun set(i: Int, s: Float) {
		segmentScales[i] = s
		val rgb = COLORS[i].rgb() and 0x00FFFFFF
		segmentColors[i] = ((255 * s).toInt() shl 24) or rgb
	}

	companion object {
		const val SEGMENTS = 4
		/** Clockwise from the top-left quadrant. */
		val COLORS = CardColor.SUITS
	}
}
