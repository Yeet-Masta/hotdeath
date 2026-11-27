package com.smorgasbork.hotdeath.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** What the game is waiting for a player to do. */
enum class Decision { NONE, TURN, ANSWER }

/** A recycle pile started by [owner]'s Recycler (or a Clone of one). */
class RecyclePile(val owner: Player) {
	val pile = CardPile()
}

/** People who may jump in right now, and with which cards. */
class JumpWindow(val on: CardType, val eligible: Map<Seat, List<Card>>) {
	internal val result = CompletableDeferred<Pair<Seat, Card>?>()
	internal val wrong = LinkedHashSet<Seat>()
}

/**
 * The Hot Death Uno Redux rules engine: drives the round/turn state machine.
 * Card effects live in Effects.kt and attacks in Attacks.kt.
 *
 * Threading model: [run] is a suspend function meant to run on the main
 * dispatcher. All waiting (animation pacing, player decisions, the next-round
 * tap) suspends instead of blocking, so the UI can read game state directly
 * from the same thread without locks. The engine never touches Android
 * classes; it reports what happened through [listener].
 */
class Game private constructor(
	val config: GameConfig,
	internal val random: Random,
	/** False in tests: skip all animation delays and jump-in windows. */
	internal val pacing: Boolean,
	/** Tests: who decides for each seat (default: people for [GameConfig.humanSeats], computers otherwise). */
	controllerFor: ((Seat) -> PlayerController)? = null,
) {
	val deck: CardDeck = CardDeck.STANDARD

	val players: List<Player> = Seat.entries.map { seat ->
		val controller = controllerFor?.invoke(seat)
			?: if (seat in config.humanSeats) HumanController() else ComputerController()
		Player(seat, controller)
	}

	/** The controller for a person's seat, or null for a computer seat. */
	fun humanController(seat: Seat): HumanController? = player(seat).controller as? HumanController

	/** True while at least one person is still in the round. */
	val anyHumanActive: Boolean get() = players.any { it.isHuman && it.active }

	var listener: GameListener? = null
	var logger: (String) -> Unit = {}

	// ── table state ───────────────────────────────────────────────────────────

	val drawPile = CardPile()
	val discardPile = CardPile()
	val recyclePiles = ArrayList<RecyclePile>()
	/** Field cards in neutral play (Famine, War, Horsemen without a holder). */
	val neutral = ArrayList<Card>()

	var currPlayer: Player? = null
		internal set
	var dealer: Player? = null
		internal set
	/** Who plays after the current turn; set by card effects. */
	internal var next: Player? = null

	/** The card the next play must match. Usually the top of the pile, but a field card or the "Red 4" after a Horseman counts too. */
	var topType: CardType? = null
		internal set
	var currColor: CardColor? = null
		internal set
	var direction: Direction = Direction.CLOCKWISE
		internal set

	/** All four Horsemen are in play: original Uno rules for the rest of the round. */
	var apocalypse: Boolean = false
		internal set
	var plagueCarrier: Player? = null
		internal set
	var prophet: Player? = null
		internal set
	/** Whose hand the Prophet last saw. */
	var prophetTarget: Player? = null
		internal set
	/** Number of cards the last Mystery Draw dealt out (for Clone). */
	internal var lastMysteryAmount = 0
	/** Players skipped by the card just played (they may not jump in on it). */
	internal val lastSkipped = HashSet<Seat>()
	/** The Flood's player skips the Plague draw this turn. */
	internal var skipPlagueDraw: Player? = null
	/** Players who drew because they could not play (Plague: one extra). */
	internal var drewBecauseStuck = false

	// ── round / UI state ──────────────────────────────────────────────────────

	var roundComplete: Boolean = true
		private set
	var waitingToStartRound: Boolean = false
		private set
	/** There are no points, so a game never ends; kept for the UI. */
	val gameOver: Boolean get() = false
	var roundWinner: Seat? = null
		private set
	var roundLoser: Seat? = null
		private set
	var fastForward: Boolean = false
		private set
	var showNextRoundButton: Boolean = false
		private set
	var showFastForwardButton: Boolean = false
		private set

	var pendingPrompt: Prompt? = null
		private set
	/** Who must answer [pendingPrompt]. */
	var promptSeat: Seat? = null
		private set
	/** Who the game is waiting on for a turn or an answer, and which. */
	var deciding: Player? = null
		private set
	var decision: Decision = Decision.NONE
		private set
	/** The attack being answered, if any. */
	var attack: Attack? = null
		internal set
	var jumpWindow: JumpWindow? = null
		private set

	/** What the direction indicator currently shows (for views attaching mid-game). */
	var indicatorDirection: Direction = Direction.NONE
		private set
	var indicatorColor: CardColor? = null
		private set

	/** JSON of the state at the last turn boundary; what gets saved. Null until a round runs. */
	var snapshot: String? = null
		private set

	private val paused = MutableStateFlow(false)
	private var nextRoundSignal: CompletableDeferred<Unit>? = null
	internal var resuming = false
	private val freeQueue = ArrayDeque<Pair<Seat, TurnAction>>()

	internal val cp: Player get() = currPlayer!!

	val activePlayers: List<Player> get() = players.filter { it.active }
	val activePlayerCount: Int get() = players.count { it.active }
	val duel: Boolean get() = activePlayerCount == 2
	val roundIsActive: Boolean get() = !waitingToStartRound && !roundComplete

	fun player(seat: Seat): Player = players[seat.index]

	// =========================================================================
	// Main loop
	// =========================================================================

	suspend fun run() {
		if (resuming) {
			resuming = false
			if (roundComplete) {
				waitForNextRound()
				startRound()
			} else {
				emit(GameEvent.PointerMoved(cp.seat, Direction.NONE, 0))
				announceIndicator(direction, currColor)
				if (!anyHumanActive) setShowFastForward(true)
			}
		} else {
			for (p in players) p.resetGame(config.faceUp)
			dealer = players[random.nextInt(4)]
			startRound()
		}

		while (true) {
			runRound()
			waitForNextRound()
			startRound()
		}
	}

	private fun resetRound() {
		direction = Direction.CLOCKWISE
		drawPile.clear()
		drawPile.addAll(deck.cards)
		discardPile.clear()
		recyclePiles.clear()
		neutral.clear()
		roundComplete = false
		roundWinner = null
		roundLoser = null
		apocalypse = false
		plagueCarrier = null
		prophet = null
		prophetTarget = null
		lastMysteryAmount = 0
		attack = null
		topType = null
		currColor = null
		next = null
		indicatorDirection = Direction.NONE
		indicatorColor = null
		freeQueue.clear()
		emit(GameEvent.RoundReset)
		for (p in players) p.resetRound(config.faceUp)
	}

	private suspend fun startRound() {
		awaitUnpaused()
		setShowNextRound(false)
		resetRound()
		currPlayer = dealer
		emit(GameEvent.PointerMoved(cp.seat, Direction.NONE, 0))
		drawPile.shuffle(random)
		waitABit(2)
		dealHands()
		if (!roundComplete) startCard()
		waitABit()
	}

	private suspend fun runRound() {
		while (!roundComplete) {
			snapshot = SnapshotJson.capture(this)
			awaitUnpaused()
			drainFree()
			if (roundComplete) break
			val p = cp
			playTurn(p)
			if (checkRoundEnd()) break
			moveTo(next ?: step(p))
		}
	}

	private suspend fun waitForNextRound() {
		setShowNextRound(true)
		message(Msg.TAP_DRAW_PILE)
		waitingToStartRound = true
		val signal = CompletableDeferred<Unit>()
		nextRoundSignal = signal
		changed()
		signal.await()
	}

	// =========================================================================
	// Dealing
	// =========================================================================

	private suspend fun dealHands() {
		val d = dealer!!
		message(Msg.DEALING, d.seat, n = HAND_SIZE)
		val order = (1..4).map { players[(d.seat.index + it) % 4] }
		for (round in 0 until HAND_SIZE) {
			for (p in order) {
				// Yin, Yang and Plague go straight into play; deal another card in their place.
				while (true) {
					val c = drawFromPile() ?: break
					p.hand.add(c)
					emit(GameEvent.CardDealt(c, d.seat, p.seat, animMs()))
					waitABit(60)
					if (!activateIfDrawn(p, c)) break
				}
			}
		}
		val humans = players.filter { it.isHuman }
		for (p in humans) for (c in p.hand.cards) emit(GameEvent.CardFlipped(c, animMs()))
		if (humans.isNotEmpty()) waitABit()
	}

	// =========================================================================
	// Turns
	// =========================================================================

	private suspend fun playTurn(p: Player) {
		next = null
		p.drawnThisTurn = null
		p.askedProphetThisTurn = false
		drewBecauseStuck = false
		skipPlagueDraw = null

		if (!apocalypse && turnStartEffects(p)) return
		computerFreeActions(p)
		if (!p.active || roundComplete) return

		var played = false
		while (p.active && !roundComplete) {
			val legal = legalPlays(p)
			val famine = famineActive
			if (famine && legal.isEmpty() && !played) {
				famineDraw(p)
				continue
			}
			if (played && (legal.isEmpty() || !famine)) break

			val action = decide(p, Decision.TURN) { p.controller.takeTurn(this, p) }
			when {
				action == null -> return
				action.isFree -> {
					handleFree(p, action, onTurn = true)
					continue
				}
				action is TurnAction.Play -> {
					val c = action.card
					if (c !in legal) {
						message(Msg.CARD_NO_GOOD, p.seat, to = p.seat)
						continue
					}
					playCard(p, c)
					// Famine: after a plain card that leaves the turn order alone, keep playing.
					if (famineActive && keepsTurn(c) && currPlayer === p && next === step(p) && p.active && !p.hand.isEmpty) {
						played = true
						continue
					}
					break
				}
				action is TurnAction.Draw -> {
					if (famine && legal.isNotEmpty()) {
						message(Msg.CARD_NO_GOOD, p.seat, to = p.seat)
						continue
					}
					if (p.drawnThisTurn != null) continue
					drewBecauseStuck = legal.isEmpty()
					val c = drawForTurn(p)
					// Original Uno (the Apocalypse) lets you play the card you drew.
					if (apocalypse && c != null && c in legalPlays(p)) continue
					break
				}
				action is TurnAction.Pass -> {
					if (p.drawnThisTurn != null) break
					continue
				}
				action is TurnAction.Invade -> {
					if (!canInvade(p, action.target)) continue
					invade(p, player(action.target))
					break
				}
			}
		}
		if (p.active && !roundComplete) plagueAfterTurn(p)
	}

	/** A card that does nothing to whose turn it is (Famine keeps you playing after one). */
	private fun keepsTurn(c: Card): Boolean = c.special == null && c.type.isNumber

	/** Asks a controller, publishing who is deciding so the views can offer the right moves. */
	internal suspend fun decide(p: Player, kind: Decision, ask: suspend () -> TurnAction): TurnAction? {
		if (!p.active) return null
		deciding = p
		decision = kind
		changed()
		try {
			return ask()
		} finally {
			deciding = null
			decision = Decision.NONE
			changed()
		}
	}

	internal suspend fun moveTo(p: Player) {
		if (p !== currPlayer) {
			currPlayer = p
			emit(GameEvent.PointerMoved(p.seat, direction, animMs()))
			waitABit(2)
		}
	}

	/** One card to skip your turn (or because you cannot play). */
	private suspend fun drawForTurn(p: Player): Card? {
		val before = p.hand.size
		val n = drawCount(p, 1, DrawKind.TURN)
		if (n == 0) message(Msg.SKIPPED, p.seat)
		repeat(n) { drawOne(p) }
		message(Msg.DRAWS_CARD, p.seat)
		val c = p.hand.cards.lastOrNull()?.takeIf { p.hand.size > before }
		p.drawnThisTurn = c ?: p.hand.cards.firstOrNull()
		return c
	}

	private suspend fun famineDraw(p: Player) {
		var n = 0
		while (legalPlays(p).isEmpty()) {
			drawOne(p) ?: break
			n++
		}
		message(Msg.FAMINE_DRAW, p.seat, n = n)
		if (legalPlays(p).isEmpty()) {
			// Nothing left to draw: the turn just ends.
			next = step(p)
			p.drawnThisTurn = p.hand.cards.firstOrNull()
		}
	}

	// =========================================================================
	// Player navigation
	// =========================================================================

	/** The next active player after [from] in [dir]. */
	fun step(from: Player, dir: Direction = direction): Player {
		var p = from
		for (i in 0 until 4) {
			p = if (dir == Direction.CLOCKWISE) players[p.seat.number % 4] else players[(p.seat.number + 2) % 4]
			if (p.active) return p
		}
		return from
	}

	/** The active player who plays immediately before [from]. */
	fun before(from: Player): Player = step(from, direction.reversed())

	internal fun reverseDirection() {
		direction = direction.reversed()
		logger("direction → $direction")
	}

	// =========================================================================
	// Card checking
	// =========================================================================

	val famineActive: Boolean get() = !apocalypse && neutral.any { it.special == Special.FAMINE }

	/** Whether [t] may be played on the current top card (ignoring whose turn it is). */
	fun matches(t: CardType): Boolean {
		val top = topType ?: return true
		if (apocalypse) {
			val b = t.base
			return b.face.isWild || b.color == currColor || b.face == top.base.face
		}
		if (t.face.isWild) return true
		if (t.special == Special.FUCK_YOU || t.special == Special.MAGIC_5 || t.special == Special.CLONE) return true
		if (t.color == currColor) return true
		if (t.suits.any { it in top.suits }) return true
		return t.special != null && t.special == top.special
	}

	/** War: [p] may not play [c]'s color. */
	fun banned(p: Player, c: Card): Boolean {
		if (apocalypse) return false
		val ban = p.bannedColor ?: return false
		if (c.special == Special.FUCK_YOU || c.special == Special.MAGIC_5) return false
		return c.color == ban
	}

	/** Cards [p] may play on their turn now. */
	fun legalPlays(p: Player): List<Card> {
		val cards = p.hand.cards.filter { matches(it.type) && !banned(p, it) }
		if (!apocalypse) return cards
		// Original Uno: Wild Draw Four only without a card of the current color.
		val hasColor = p.hand.cards.any { it.type.base.color == currColor }
		return cards.filter { !(it.type.base.face == Face.WILD_DRAW_FOUR && hasColor) }
	}

	/** Cards that count as the same card for a jump-in. */
	fun identical(a: CardType, b: CardType): Boolean {
		if (a === b) return true
		if (a.color != b.color) return false
		val sixNine = setOf(Face.SIX, Face.NINE)
		if (a.special == Special.SIXTY_NINE && b.special == null && b.face in sixNine) return true
		return b.special == Special.SIXTY_NINE && a.special == null && a.face in sixNine
	}

	// =========================================================================
	// Drawing
	// =========================================================================

	enum class DrawKind {
		/** Drawing to skip a turn. */
		TURN,
		/** Made to draw by an attack. */
		ATTACK,
		/** A broken rule. */
		PENALTY,
		/** Ping, Spreader, Gift, Gold Coin, card effects: never changed. */
		PLAIN,
	}

	/** How many cards [p] actually draws for [n] (the draw hierarchy). */
	internal fun drawCount(p: Player, n: Int, kind: DrawKind): Int {
		if (apocalypse || kind == DrawKind.PLAIN) return n
		var k = n - p.luckCount + p.virusCount
		if (kind == DrawKind.ATTACK && plagueCarrier != null && p.luckCount == 0) k++
		return maxOf(0, k)
	}

	/** Makes [p] draw [n] cards (after the draw hierarchy); returns how many were drawn. */
	internal suspend fun forceDraw(p: Player, n: Int, kind: DrawKind): Int {
		val k = drawCount(p, n, kind)
		if (k <= 0) return 0
		message(Msg.DRAWING, p.seat, n = k)
		logger("${p.seat} draws $k")
		var drawn = 0
		for (i in 0 until k) {
			drawOne(p) ?: break
			drawn++
			waitABit(15)
		}
		waitABit(2)
		return drawn
	}

	/** Draws one card into [p]'s hand, putting Yin, Yang and Plague straight into play. */
	internal suspend fun drawOne(p: Player): Card? {
		val c = drawFromPile() ?: run {
			message(Msg.DISCARD_EMPTY)
			return null
		}
		p.hand.add(c)
		p.calledUno = false
		emit(GameEvent.CardDrawn(c, p.seat, animMs()))
		activateIfDrawn(p, c)
		return c
	}

	internal fun drawFromPile(): Card? {
		if (drawPile.isEmpty) rolloverDiscardPile()
		return drawPile.draw()
	}

	private fun rolloverDiscardPile() {
		if (discardPile.size <= 1) return
		val top = discardPile.draw()!!
		val rest = discardPile.takeAll()
		message(Msg.SHUFFLING_DISCARD, n = rest.size)
		drawPile.addAll(rest)
		drawPile.shuffle(random)
		discardPile.add(top)
		changed()
	}

	// =========================================================================
	// UNO
	// =========================================================================

	/** [p] went down to one card by playing: computers may forget to call, and may get caught. */
	internal suspend fun afterPlayUno(p: Player) {
		if (p.hand.size != 1 || p.calledUno) return
		if (p.isHuman && config.autoUno) {
			callUno(p)
			return
		}
		if (!p.isHuman && random.nextFloat() < rememberUnoChance(p)) {
			callUno(p)
			return
		}
		// Somebody may notice.
		for (q in players.shuffled(random)) {
			if (q === p || !q.active || q.isHuman) continue
			if (random.nextFloat() < catchChance(q)) {
				catchUno(q, p)
				return
			}
		}
	}

	private fun rememberUnoChance(p: Player) = when (config.skill(p.seat)) { 0 -> 0.7f; 1 -> 0.9f; else -> 0.98f }
	private fun catchChance(p: Player) = when (config.skill(p.seat)) { 0 -> 0.15f; 1 -> 0.3f; else -> 0.5f }

	internal fun callUno(p: Player) {
		p.calledUno = true
		message(Msg.CALLS_UNO, p.seat)
	}

	private suspend fun catchUno(caller: Player, caught: Player) {
		message(Msg.CAUGHT_NO_UNO, caught.seat, caller.seat)
		forceDraw(caught, 2, DrawKind.PENALTY)
	}

	/** The UNO button: call, catch, or a false call. */
	private suspend fun unoButton(p: Player) {
		if (p.hand.size <= 2 && !p.calledUno) {
			callUno(p)
			return
		}
		val caught = players.firstOrNull { it !== p && it.active && it.hand.size == 1 && !it.calledUno }
		if (caught != null) {
			catchUno(p, caught)
		} else if (!(p.hand.size <= 2 && p.calledUno)) {
			message(Msg.FALSE_UNO, p.seat)
			forceDraw(p, 2, DrawKind.PENALTY)
		}
	}

	// =========================================================================
	// Free actions
	// =========================================================================

	/** Free actions from people who are not deciding wait here for the next pause between plays. */
	internal suspend fun drainFree() {
		while (freeQueue.isNotEmpty() && !roundComplete) {
			val (seat, a) = freeQueue.removeFirst()
			val p = player(seat)
			if (p.active) handleFree(p, a, onTurn = false)
		}
	}

	internal suspend fun handleFree(p: Player, a: TurnAction, onTurn: Boolean) {
		when (a) {
			TurnAction.CallUno -> unoButton(p)
			is TurnAction.Accuse -> if (canAccuse(p, a.target)) accuse(p, player(a.target))
			is TurnAction.AskProphet -> if (onTurn && canAskProphet(p)) askProphet(p, a.about)
			is TurnAction.ChangeColor -> if (onTurn && canChangeColor(p) && a.color != CardColor.WILD) {
				currColor = a.color
				message(Msg.COLOR_CHANGE, p.seat)
				announceIndicator(direction, currColor)
			}
			else -> {}
		}
		checkRoundEnd()
	}

	fun canAccuse(p: Player, target: Seat): Boolean =
		roundIsActive && !apocalypse && p.active && target != p.seat && player(target).active

	fun canAskProphet(p: Player): Boolean {
		val pr = prophet ?: return false
		return !apocalypse && pr !== p && pr.active && prophetTarget != null && !p.askedProphetThisTurn
	}

	fun isConqueror(p: Player): Boolean {
		if (apocalypse || !p.hasField(Special.CONQUEST)) return false
		val others = players.filter { it !== p && it.active }
		return others.isNotEmpty() && others.all { it.seat in p.conquered }
	}

	fun canChangeColor(p: Player): Boolean = isConqueror(p) && p.hand.size != 1

	fun invadeTargets(p: Player): List<Seat> {
		if (apocalypse || !p.hasField(Special.CONQUEST) || p.hand.cards.none { it.type.isNumber }) return emptyList()
		return players.filter { it !== p && it.active && it.seat !in p.conquered }.map { it.seat }
	}

	fun canInvade(p: Player, target: Seat): Boolean = target in invadeTargets(p)

	/** Computers call a late UNO, catch others and make accusations at the start of their turn. */
	private suspend fun computerFreeActions(p: Player) {
		if (p.isHuman) return
		if (p.hand.size == 1 && !p.calledUno && random.nextFloat() < rememberUnoChance(p)) callUno(p)
		val caught = players.firstOrNull { it !== p && it.active && it.hand.size == 1 && !it.calledUno }
		if (caught != null && random.nextFloat() < catchChance(p)) catchUno(p, caught)
		(p.controller as? ComputerController)?.accusation(this, p)?.let { if (canAccuse(p, it)) accuse(p, player(it)) }
		if (canAskProphet(p)) (p.controller as? ComputerController)?.prophetQuestion(this, p)?.let { askProphet(p, it) }
	}

	// =========================================================================
	// Jump-ins
	// =========================================================================

	/**
	 * Opens a jump-in window on [played] (just played by [by]). Returns who
	 * jumped in and with what, or null. Wrong jump-ins are punished here.
	 */
	internal suspend fun jumpIn(played: CardType, by: Player): Pair<Player, Card>? {
		if (apocalypse || roundComplete) return null
		val eligible = players.filter { it.active && it !== by && it.seat !in lastSkipped }
			.associateWith { p -> p.hand.cards.filter { identical(it.type, played) && !banned(p, it) } }
		val r = reactionWindow(played, eligible) ?: return null
		message(Msg.JUMPED_IN, r.first.seat, card = r.second.type)
		return r
	}

	/**
	 * Gives [eligible] players a chance to play one of their listed cards out
	 * of turn. People get [GameConfig.jumpInMs]; computers answer at a random
	 * moment within it. Returns the first to act, or null.
	 */
	internal suspend fun reactionWindow(on: CardType, eligibleAll: Map<Player, List<Card>>): Pair<Player, Card>? {
		val eligible = eligibleAll.filter { it.key.active && it.value.isNotEmpty() }
		if (eligible.isEmpty()) return null
		val humans = eligible.keys.filter { it.isHuman }
		val windowMs = if (pacing && humans.isNotEmpty()) config.jumpInMs else 0L
		val computer = eligible.filterKeys { !it.isHuman }.mapNotNull { (p, cards) ->
			p.controller.jumpInDelay(this, p, cards.first(), windowMs)?.let { Triple(p, cards.first(), it.coerceIn(0, windowMs)) }
		}.minByOrNull { it.third }

		var result: Pair<Player, Card>? = computer?.let { it.first to it.second }
		if (humans.isNotEmpty() && windowMs > 0) {
			val w = JumpWindow(on, humans.associate { it.seat to eligible[it]!! })
			jumpWindow = w
			for (h in humans) message(Msg.JUMP_IN_NOW, h.seat, card = on, to = h.seat)
			changed()
			val wait = computer?.third ?: windowMs
			val human = withTimeoutOrNull(wait) { w.result.await() }
			jumpWindow = null
			changed()
			for (s in w.wrong) {
				message(Msg.WRONG_JUMP_IN, s)
				forceDraw(player(s), 2, DrawKind.PENALTY)
			}
			if (human != null) result = player(human.first) to human.second
		}
		val (p, c) = result ?: return null
		if (!p.active || c !in p.hand) return null
		return p to c
	}

	// =========================================================================
	// Round end
	// =========================================================================

	/** Ends the round if it is decided. Returns true if it is over. */
	internal suspend fun checkRoundEnd(): Boolean {
		if (roundComplete) return true
		// Anyone left with no cards is out.
		for (p in players) {
			if (p.active && p.hand.isEmpty && attack == null) {
				if (p.emptiedWithoutUno) {
					// You must call UNO or you cannot win.
					p.emptiedWithoutUno = false
					message(Msg.CANNOT_WIN_NO_UNO, p.seat)
					forceDraw(p, 2, DrawKind.PENALTY)
					continue
				}
				if (!config.eliminationMode) {
					finishRound(winner = p, loser = null)
					return true
				}
				setStatus(p, Status.FINISHED)
				message(Msg.PLAYER_OUT, p.seat)
			}
		}
		val left = activePlayers
		if (config.eliminationMode) {
			if (left.size <= 1) {
				finishRound(winner = null, loser = left.firstOrNull())
				return true
			}
		} else if (left.size <= 1) {
			finishRound(winner = left.firstOrNull(), loser = null)
			return true
		}
		return false
	}

	internal fun setStatus(p: Player, s: Status) {
		p.status = s
		p.teammate?.takeIf { it.status == Status.TEAMED }?.status = s
		if (p.isHuman && !anyHumanActive && roundIsActive) setShowFastForward(true)
		changed()
	}

	private suspend fun finishRound(winner: Player?, loser: Player?) {
		roundComplete = true
		fastForward = false
		setShowFastForward(false)
		announceIndicator(direction, null)
		if (winner != null) {
			winner.wins++
			winner.teammate?.let { it.wins++ }
			roundWinner = winner.seat
			dealer = winner
			message(Msg.ROUND_WINNER, winner.seat)
		} else if (loser != null) {
			loser.losses++
			loser.teammate?.let { it.losses++ }
			roundLoser = loser.seat
			dealer = loser
			message(Msg.ROUND_LOSER, loser.seat)
		} else {
			message(Msg.ROUND_NO_WINNER)
		}
		for (p in players) animateFlips(p.hand.revealAll())
		snapshot = SnapshotJson.capture(this)
		changed()
	}

	private suspend fun animateFlips(cards: List<Card>) {
		cards.forEachIndexed { i, c ->
			emit(GameEvent.CardFlipped(c, animMs()))
			waitABit(if (i == cards.lastIndex) 2 else 120)
		}
	}

	// =========================================================================
	// Pacing
	// =========================================================================

	/** Base delay between actions, from the game-speed setting. */
	fun delayMs(): Long {
		if (fastForward) return 0
		if (!anyHumanActive) return 250
		return when (config.pauseLength) {
			0 -> 700
			1 -> 1200
			2 -> 1700
			3 -> 2900
			else -> 4000
		}
	}

	internal fun animMs(): Long = if (pacing) delayMs() / 4 else 0

	internal suspend fun waitABit(div: Int = 1) {
		if (!pacing) return
		val d = delayMs() / div
		if (d > 0) delay(d)
	}

	fun pause() {
		paused.value = true
	}

	fun unpause() {
		paused.value = false
	}

	private suspend fun awaitUnpaused() {
		paused.first { !it }
	}

	// =========================================================================
	// UI input (call on the main thread)
	// =========================================================================

	/** [seat] taps the draw pile (draw a card, or start the next round). */
	fun drawPileTapped(seat: Seat) {
		if (waitingToStartRound) {
			requestNextRound()
			return
		}
		if (deciding?.seat == seat && decision == Decision.TURN) humanController(seat)?.submit(TurnAction.Draw)
	}

	fun discardPileTapped() {
		if (waitingToStartRound) requestNextRound()
	}

	fun requestNextRound() {
		if (!waitingToStartRound) return
		waitingToStartRound = false
		showNextRoundButton = false
		nextRoundSignal?.complete(Unit)
		nextRoundSignal = null
		changed()
	}

	/** "Take it" when answering; "end turn" after drawing under Apocalypse rules. */
	fun humanPass(seat: Seat) {
		if (deciding?.seat == seat) humanController(seat)?.submit(TurnAction.Pass)
	}

	/** [seat] taps one of their cards. */
	fun humanPlay(seat: Seat, c: Card) {
		val p = player(seat)
		if (!roundIsActive || c !in p.hand) return
		jumpWindow?.let { w ->
			val mine = w.eligible[seat] ?: return@let
			if (c in mine) w.result.complete(seat to c) else w.wrong += seat
			return
		}
		if (deciding !== p) return
		val ok = when (decision) {
			Decision.TURN -> c in legalPlays(p)
			Decision.ANSWER -> attack?.let { c in answers(p, it) } ?: false
			Decision.NONE -> false
		}
		if (ok) humanController(seat)?.submit(TurnAction.Play(c))
		else message(Msg.CARD_NO_GOOD, seat, to = seat)
	}

	/**
	 * A free action (UNO, accuse, ask the Prophet, change color) or an invasion.
	 * If [seat] is the one deciding it is handled at once; UNO and accusations
	 * from anyone else wait for the next pause between plays.
	 */
	fun humanAction(seat: Seat, a: TurnAction) {
		if (!roundIsActive) return
		val h = humanController(seat) ?: return
		if (deciding?.seat == seat && (a.isFree || (a is TurnAction.Invade && decision == Decision.TURN))) {
			h.submit(a)
		} else if (a == TurnAction.CallUno || a is TurnAction.Accuse) {
			freeQueue.addLast(seat to a)
		}
	}

	/** Answers the open prompt, if it belongs to [seat]. */
	fun submitColor(seat: Seat, c: CardColor) {
		if (promptSeat == seat && c != CardColor.WILD) humanController(seat)?.submitColor(c)
	}

	fun submitVictim(seat: Seat, victim: Seat?) {
		val p = pendingPrompt as? Prompt.Victim ?: return
		if (promptSeat == seat && (victim in p.options || (victim == null && p.optional))) humanController(seat)?.submitVictim(victim)
	}

	fun submitCard(seat: Seat, card: Card?) {
		val p = pendingPrompt as? Prompt.ChooseCard ?: return
		if (promptSeat == seat && (card?.deckIndex in p.options || (card == null && p.optional))) humanController(seat)?.submitCard(card)
	}

	/** A person left: a computer plays that seat from now on. */
	fun handOverToComputer(seat: Seat) {
		val h = humanController(seat) ?: return
		h.handOver()
		jumpWindow?.let { if (seat in it.eligible) it.result.complete(null) }
		if (!anyHumanActive && roundIsActive) setShowFastForward(true)
		changed()
	}

	fun startFastForward() {
		fastForward = true
		showFastForwardButton = false
		changed()
	}

	// =========================================================================
	// Internal helpers
	// =========================================================================

	/** A person can now act. */
	internal fun turnStarted() = changed()

	internal fun beginPrompt(p: Prompt, seat: Seat) {
		pendingPrompt = p
		promptSeat = seat
		if (p == Prompt.Color) emit(GameEvent.ColorChooserShown(true, animMs()))
		changed()
	}

	internal fun endPrompt() {
		if (pendingPrompt == Prompt.Color) emit(GameEvent.ColorChooserShown(false, animMs()))
		pendingPrompt = null
		promptSeat = null
		changed()
	}

	internal suspend fun announceIndicator(dir: Direction, color: CardColor?) {
		if (dir == indicatorDirection && color == indicatorColor) return
		indicatorDirection = dir
		indicatorColor = color
		emit(GameEvent.IndicatorChanged(dir, color, animMs()))
		waitABit(2)
	}

	private fun setShowNextRound(show: Boolean) {
		showNextRoundButton = show
		changed()
	}

	internal fun setShowFastForward(show: Boolean) {
		showFastForwardButton = show
		changed()
	}

	internal fun emit(e: GameEvent) {
		listener?.onEvent(e)
	}

	internal fun changed() = emit(GameEvent.StateChanged)

	internal fun message(kind: Msg, seat: Seat? = null, other: Seat? = null, n: Int = 0, card: CardType? = null, to: Seat? = null) {
		if (!fastForward) emit(GameEvent.Message(GameMessage(kind, seat, other, n, card, to)))
	}

	internal fun restoreRoundState(complete: Boolean, winner: Seat?, loser: Seat?) {
		roundComplete = complete
		roundWinner = winner
		roundLoser = loser
	}

	internal fun restoreSnapshot(s: String) {
		snapshot = s
	}

	companion object {
		const val HAND_SIZE = 8

		fun newGame(
			config: GameConfig,
			random: Random = Random.Default,
			pacing: Boolean = true,
			controllerFor: ((Seat) -> PlayerController)? = null,
		): Game = Game(config, random, pacing, controllerFor)

		/** Rebuilds a game from [snapshot]; throws [IllegalArgumentException] if it is unusable. */
		fun restore(snapshot: String, config: GameConfig, random: Random = Random.Default, pacing: Boolean = true): Game {
			val g = Game(config, random, pacing)
			SnapshotJson.restore(g, snapshot)
			g.snapshot = snapshot
			g.resuming = true
			return g
		}
	}
}
