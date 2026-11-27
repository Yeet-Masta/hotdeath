package com.smorgasbork.hotdeath.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.random.Random

/**
 * The Hot Death rules engine: drives the round/turn state machine and all
 * card effects.
 *
 * Threading model: [run] is a suspend function meant to run on the main
 * dispatcher. All waiting (animation pacing, player decisions, the next-round
 * tap) suspends instead of blocking, so the UI can read game state directly
 * from the same thread without locks. The engine never touches Android
 * classes; it reports what happened through [listener].
 */
class Game private constructor(
	val config: GameConfig,
	val deck: CardDeck,
	private val random: Random,
	/** False in tests: skip all animation delays. */
	private val pacing: Boolean,
) {

	val deckType: DeckType get() = deck.deckType
	val standardRules: Boolean get() = deckType.standardRules

	val players: List<Player> = Seat.entries.map { seat ->
		val controller = if (seat in config.humanSeats) HumanController() else ComputerController()
		Player(seat, controller)
	}

	val southPlayer: Player get() = players[Seat.SOUTH.index]

	/** The controller for a person's seat, or null for a computer seat. */
	fun humanController(seat: Seat): HumanController? = player(seat).controller as? HumanController

	/** True while at least one person is still in the round. */
	val anyHumanActive: Boolean get() = players.any { it.isHuman && it.active }

	var listener: GameListener? = null
	var logger: (String) -> Unit = {}

	// ── table state ───────────────────────────────────────────────────────────

	val drawPile = CardPile()
	val discardPile = CardPile()
	val penalty = Penalty()

	var currPlayer: Player? = null
		private set
	var dealer: Player? = null
		private set
	private var nextPlayerPreset: Player? = null
	private var numCardsToDeal = 0

	var lastPlayedCard: Card? = null
		private set
	private var prevCard: Card? = null

	var direction: Direction = Direction.NONE
		private set
	var currColor: CardColor? = null
		private set
	private var cardsPlayed = 0
	private var forceDrawing = false

	// ── round / UI state ──────────────────────────────────────────────────────

	var roundComplete: Boolean = true
		private set
	var waitingToStartRound: Boolean = false
		private set
	var gameOver: Boolean = false
		private set
	/** Seat with the lowest score once the game is over. */
	var gameWinner: Seat? = null
		private set
	var fastForward: Boolean = false
		private set

	var showNextRoundButton: Boolean = false
		private set
	var showFastForwardButton: Boolean = false
		private set
	var showMenu: Boolean = false
		private set
	var pendingPrompt: Prompt? = null
		private set
	/** Who must answer [pendingPrompt]. */
	var promptSeat: Seat? = null
		private set

	/** The person whose decision is timed, while the clock runs. */
	var clockSeat: Seat? = null
		private set
	/** When [clockSeat]'s time runs out ([System.currentTimeMillis]). */
	var clockDeadline: Long = 0
		private set

	/** What the direction indicator currently shows (for views attaching mid-game). */
	var indicatorDirection: Direction = Direction.NONE
		private set
	var indicatorColor: CardColor? = null
		private set

	/** State at the last turn boundary; what gets saved. Null until a round runs. */
	var snapshot: GameSnapshot? = null
		private set

	private val paused = MutableStateFlow(false)
	private var nextRoundSignal: CompletableDeferred<Unit>? = null
	private var resuming = false

	private val cp: Player get() = currPlayer!!

	val activePlayerCount: Int get() = players.count { it.active }
	val roundIsActive: Boolean get() = !waitingToStartRound && !gameOver
	val currPlayerUnderAttack: Boolean get() = penalty.isActive
	val currPlayerHasDrawn: Boolean get() = currPlayer?.hasTriedDrawing == true

	fun player(seat: Seat): Player = players[seat.index]

	// =========================================================================
	// Main loop
	// =========================================================================

	suspend fun run() {
		if (resuming) {
			resuming = false
			if (roundComplete) {
				setShowNextRound(true)
				waitForNextRound()
				startRound()
			} else {
				emit(GameEvent.PointerMoved(cp.seat, Direction.NONE, 0))
				announceIndicator(direction, currColor)
				if (!anyHumanActive) setShowFastForward(true)
			}
		} else {
			startGame()
		}

		while (!gameOver) {
			runRound()
			if (!gameOver) {
				waitForNextRound()
				startRound()
			}
		}
		changed()
	}

	private suspend fun startGame() {
		resetGame()
		startRound()
	}

	private fun resetGame() {
		gameOver = false
		gameWinner = null
		for (p in players) p.resetGame(config.faceUp)
		dealer = players[random.nextInt(4)]
	}

	private fun resetRound() {
		direction = Direction.CLOCKWISE
		drawPile.apply { while (!isEmpty) draw(); addAll(deck.cards) }
		discardPile.apply { while (!isEmpty) draw() }
		cardsPlayed = 0
		roundComplete = false
		indicatorDirection = Direction.NONE
		indicatorColor = null
		emit(GameEvent.RoundReset)
		for (p in players) p.resetRound(config.faceUp)
		penalty.reset()
		lastPlayedCard = null
		prevCard = null
		nextPlayerPreset = null
	}

	private suspend fun startRound() {
		awaitUnpaused()
		setShowNextRound(false)
		resetRound()
		currPlayer = dealer
		emit(GameEvent.PointerMoved(cp.seat, Direction.NONE, 0))

		drawPile.shuffle(random)

		val d = dealer!!
		numCardsToDeal = if (standardRules) 7 else d.controller.chooseNumCardsToDeal(this, d)

		waitABit(2)
		dealHands()
		postDealHands()
		waitABit()
	}

	private suspend fun runRound() {
		while (true) {
			snapshot = capture()
			awaitUnpaused()
			if (roundComplete) return
			if (cardsPlayed >= STALEMATE_PLAYS) {
				// With few players and an empty draw pile, attack cards can cycle
				// between hands forever. Call it: the lowest hand wins the round.
				message(GameMessage.Stalemate)
				finishRound(players.filter { it.active }.minBy { it.hand.calculateValue().total })
				return
			}
			if (!advanceRound()) return
		}
	}

	private suspend fun waitForNextRound() {
		message(GameMessage.TapDrawPile)
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
		awaitUnpaused()
		val d = dealer!!
		message(GameMessage.Dealing(d.seat, numCardsToDeal))
		var p = getNextPlayer(d)
		repeat(4 * numCardsToDeal) {
			val c = drawPile.draw() ?: return@repeat
			p.hand.add(c)
			emit(GameEvent.CardDealt(c, d.seat, p.seat, p.hand.isVisible(c), animMs()))
			waitABit(60)
			p = getNextPlayer(p)
		}
		waitABit(2)
	}

	private fun isCheatCard(c: Card): Boolean = when (c.type) {
		CardType.RED_0_HD, CardType.RED_2_GLASNOST, CardType.RED_5_MAGIC, CardType.RED_D_SPREADER,
		CardType.YELLOW_69, CardType.GREEN_D_SPREADER, CardType.WILD_MYSTERY, CardType.GREEN_3_AIDS,
		CardType.WILD_DB, CardType.BLUE_2_SHIELD, CardType.GREEN_4_IRISH, CardType.WILD_DRAW_FOUR -> true
		else -> false
	}

	private suspend fun postDealHands() {
		for (p in players) {
			if (p.hand.hasAllBastardCards()) {
				message(GameMessage.AllBastardCards(p.seat))
				finishRound(p)
				return
			}
		}

		// People see their own cards turn over; nobody else does.
		val humans = players.filter { it.isHuman }
		for (p in humans) {
			p.hand.sort()
			for (c in p.hand.heldCards) emit(GameEvent.CardFlipped(c, animMs()))
		}
		if (humans.isNotEmpty()) waitABit()

		// Cheat (single-player only): swap good cards from the draw pile into the human hand.
		val cheater = humans.singleOrNull()
		var cheatLevel = if (cheater != null) config.cheatLevel else 0
		var i = drawPile.size - 1
		while (i >= 0 && cheatLevel > 0) {
			val newCard = drawPile[i]
			if (newCard != null && isCheatCard(newCard)) {
				val pos = drawPile.remove(newCard)
				if (pos != -1) {
					val oldCard = cheater!!.hand.swapRandom(newCard, random)
					drawPile.insert(oldCard, pos)
					emit(GameEvent.CheatSwap(cheater.seat, newCard, oldCard, animMs()))
					waitABit(2)
					cheatLevel--
				}
			}
			i--
		}

		do {
			val c = drawPile.draw() ?: break
			lastPlayedCard = c
			currColor = c.color
			discardPile.add(c)
			if ((c.value == CardValue.REVERSE || c.value == CardValue.REVERSE_SKIP) && !standardRules) {
				changeDirection()
			}
			animateDiscard(c, null)
		} while (standardRules && currColor == CardColor.WILD)

		if (!standardRules) handleSpecialCards(virtualPlayer = true)

		if (nextPlayerPreset != null || standardRules) currPlayer = nextPlayer()
	}

	// =========================================================================
	// Turns
	// =========================================================================

	private suspend fun advanceRound(): Boolean {
		val p = currPlayer ?: return false

		if (p.isHuman) {
			message(GameMessage.YourPlay(p.seat))
			setShowMenu(true)
		} else {
			setShowMenu(false)
		}

		val action = if (penalty.isActive && !hasValidCards(p.hand)) {
			// Nothing can answer the attack: take it.
			waitABit()
			TurnAction.Pass
		} else {
			p.controller.takeTurn(this, p)
		}

		if (hasValidCards(p.hand) && action != TurnAction.Pass) {
			if (!handleValidCardsTurn(action)) return false
		} else {
			handleNoValidCardsTurn()
		}

		// Check for a winner after every player action.
		for (pl in players) {
			val h = pl.hand
			val allBastard = h.hasAllBastardCards()
			if (allBastard) message(GameMessage.AllBastardCards(pl.seat))
			if ((allBastard || h.isEmpty) && !penalty.isActive) {
				finishRound(pl)
				return false
			}
		}
		return true
	}

	private suspend fun handleValidCardsTurn(action: TurnAction): Boolean {
		when (action) {
			is TurnAction.Play -> return executeCardPlay(action.card)
			TurnAction.Draw -> if (!cp.hasTriedDrawing) executeDrawCard()
			TurnAction.Pass -> {}
		}
		return true
	}

	private suspend fun executeCardPlay(card: Card): Boolean {
		val p = cp
		prevCard = lastPlayedCard
		lastPlayedCard = card
		p.hand.remove(card)
		logger("${p.seat} plays ${card.type}")
		cardsPlayed++
		discardPile.add(card)

		if (card.value == CardValue.REVERSE || card.value == CardValue.REVERSE_SKIP ||
			(card.type == CardType.BLUE_0_FUCK_YOU && (penalty.victim === p || penalty.secondaryVictim === p))) {
			changeDirection()
		}

		currColor = card.color
		animateDiscard(card, p.seat)
		handleSpecialCards(virtualPlayer = false)

		// if the previous player set us up, and we did not throw something
		// that negates the penalty, we get penalized now
		if (penalty.isActive
			&& (penalty.victim === currPlayer
					|| lastPlayedCard?.type == CardType.YELLOW_1_MAD
					|| penalty.victim == null && penalty.secondaryVictim === currPlayer)) {
			assessPenalty()
		}
		if (!penalty.isActive && cp.hand.isEmpty) {
			finishRound(cp)
			return false
		}
		currPlayer = nextPlayer()
		return true
	}

	private suspend fun executeDrawCard() {
		val p = cp
		val card = playerDraws(p) ?: return
		emit(GameEvent.CardDrawn(card, p.seat, p.hand.isVisible(card), animMs()))
		waitABit(2)
		logger("${p.seat} draws ${card.type}")
	}

	private suspend fun handleNoValidCardsTurn() {
		if (penalty.isActive) {
			assessPenalty()
			if (currColor == CardColor.WILD) {
				currColor = cp.controller.chooseColor(this, cp)
				announceIndicator(direction, currColor)
			}
			currPlayer = nextPlayer()
		} else if (cp.hasTriedDrawing) {
			currPlayer = nextPlayer()
		} else {
			val p = cp
			val card = playerDraws(p) ?: return
			emit(GameEvent.CardDrawn(card, p.seat, p.hand.isVisible(card), animMs()))
			waitABit(2)
			logger("${p.seat} draws a card")
			message(GameMessage.PlayerDrawsCard(p.seat))
			if (!hasValidCards(p.hand)) currPlayer = nextPlayer()
		}
	}

	/** A player voluntarily draws one card. */
	private fun playerDraws(p: Player): Card? {
		p.hasTriedDrawing = true
		val c = drawFromPile() ?: return null
		p.hand.add(c)
		p.lastDrawn = c
		return c
	}

	// =========================================================================
	// Player navigation
	// =========================================================================

	fun getNextPlayer(from: Player? = null): Player = step(from ?: cp, direction)

	/** The active player who plays immediately before `from` in the current direction. */
	fun getPlayerBefore(from: Player): Player = step(from, direction.reversed())

	private fun step(from: Player, dir: Direction): Player {
		var p = from
		for (i in 0 until 4) {
			p = if (dir == Direction.CLOCKWISE) players[p.seat.number % 4] else players[(p.seat.number + 2) % 4]
			if (p.active) return p
		}
		return p
	}

	private suspend fun nextPlayer(): Player {
		var dir = direction
		cp.resetLastDrawn()

		val preset = nextPlayerPreset
		val p = if (preset != null) {
			nextPlayerPreset = null
			dir = Direction.NONE
			preset
		} else {
			getNextPlayer()
		}

		if (p !== currPlayer) {
			emit(GameEvent.PointerMoved(p.seat, dir, animMs()))
			waitABit(2)
		}
		return p
	}

	// =========================================================================
	// Card checking
	// =========================================================================

	fun checkCard(h: Hand, c: Card): Boolean = evaluate(h, c) != PlayCheck.INVALID

	fun hasValidCards(h: Hand): Boolean = h.cards.any { checkCard(h, it) }

	/** Whether `c` may be played from `h` right now, and whether it defends against an attack. */
	fun evaluate(h: Hand, c: Card): PlayCheck {
		if (c !in h) return PlayCheck.INVALID
		val curr = lastPlayedCard ?: return PlayCheck.INVALID

		if (penalty.isActive) return evaluateUnderPenalty(c, curr)

		val currVal = curr.value
		val checkVal = c.value

		// 69 interplay: 6↔9 if the player holds the 69 card
		if ((checkVal == CardValue.SIX && currVal == CardValue.NINE) || (checkVal == CardValue.NINE && currVal == CardValue.SIX)) {
			if (h.has(CardType.YELLOW_69)) return PlayCheck.VALID
		}

		if (c.type == CardType.YELLOW_0_SHITTER) {
			return if (curr.type == CardType.RED_0_HD || curr.type == CardType.RED_5_MAGIC || h.size == 1)
				PlayCheck.VALID else PlayCheck.INVALID
		}

		val sixOrNine = setOf(CardValue.SIX, CardValue.NINE)
		if (curr.type == CardType.YELLOW_69 && checkVal in sixOrNine) return PlayCheck.VALID
		if (c.type == CardType.YELLOW_69 && currVal in sixOrNine) return PlayCheck.VALID
		if (c.type == CardType.RED_5_MAGIC) return PlayCheck.VALID

		// Variants play on each other: Draw/Spreader, Skip/Double Skip,
		// and every Reverse variant (Reverse, Skip-Reverse, Backstab, Swap).
		if (currVal.isDrawFamily && checkVal.isDrawFamily) return PlayCheck.VALID
		if (currVal.isSkipFamily && checkVal.isSkipFamily) return PlayCheck.VALID
		if (currVal.isReverseFamily && checkVal.isReverseFamily) return PlayCheck.VALID
		// Clone can be played on anything
		if (checkVal == CardValue.CLONE) return PlayCheck.VALID

		if (standardRules && h.hasColorMatch(currColor) && checkVal == CardValue.WILD_DRAW) return PlayCheck.INVALID

		return if (c.color == currColor || c.color == CardColor.WILD || currColor == CardColor.WILD || checkVal == currVal)
			PlayCheck.VALID else PlayCheck.INVALID
	}

	private fun evaluateUnderPenalty(c: Card, curr: Card): PlayCheck {
		// AIDS cannot be defended against.
		if (curr.type == CardType.GREEN_3_AIDS) return PlayCheck.INVALID
		val orig = penalty.origCard ?: return PlayCheck.INVALID

		// Holy Defender, Fuck You, and AIDS can defend against most attacks.
		val isDefender = c.type == CardType.BLUE_0_FUCK_YOU || c.type == CardType.RED_0_HD || c.type == CardType.GREEN_3_AIDS
		val origIsAttack = (orig.value == CardValue.WILD_DRAW && orig.type != CardType.WILD_HOS)
				|| orig.type == CardType.GREEN_0_QUITTER
				|| orig.type == CardType.RED_2_GLASNOST
		if (isDefender && origIsAttack) return PlayCheck.DEFENDER

		// Stack wild-draws (except on HOS / Mystery).
		if (!standardRules
			&& penalty.secondaryVictim == null
			&& orig.value == CardValue.WILD_DRAW
			&& orig.type != CardType.WILD_HOS
			&& orig.type != CardType.WILD_MYSTERY
			&& c.value == CardValue.WILD_DRAW
			&& c.type != CardType.WILD_MYSTERY) {
			return PlayCheck.DEFENDER
		}

		// Magic 5 defends specifically against Hot Death.
		return if (c.type == CardType.RED_5_MAGIC && penalty.hasHotDeath && curr.type != CardType.WILD_HOS)
			PlayCheck.DEFENDER else PlayCheck.INVALID
	}

	// =========================================================================
	// Special card handling
	// =========================================================================

	/** @param virtualPlayer true for the first card flipped onto the discard pile after dealing. */
	private suspend fun handleSpecialCards(virtualPlayer: Boolean) {
		if (currColor == CardColor.WILD && !virtualPlayer) {
			currColor = cp.controller.chooseColor(this, cp)
			logger("${cp.seat} chose $currColor")
			announceIndicator(direction, currColor)
		}

		val curr = lastPlayedCard!!
		nextPlayerPreset = null

		when (curr.value) {
			CardValue.REVERSE -> {
				if (activePlayerCount == 2) currPlayer = nextPlayer()
				return
			}
			CardValue.REVERSE_SKIP -> {
				if (virtualPlayer) waitABit(2)
				currPlayer = nextPlayer()
				return
			}
			// Backstab: the player BEFORE the current one draws 2, then reverse
			CardValue.REVERSE_BACKSTAB -> {
				if (virtualPlayer) waitABit(2)
				val prev = getPlayerBefore(cp)
				if (prev !== currPlayer) forceDraw(prev, 2)
				changeDirection()
				if (activePlayerCount == 2) currPlayer = nextPlayer()
				return
			}
			// Swap: swap hands with the player before you, then reverse
			CardValue.REVERSE_SWAP -> {
				if (virtualPlayer) waitABit(2)
				val prev = getPlayerBefore(cp)
				if (prev !== currPlayer) {
					swapHands(cp, prev)
					message(GameMessage.Swap(cp.seat, prev.seat))
				}
				changeDirection()
				if (activePlayerCount == 2) currPlayer = nextPlayer()
				return
			}
			CardValue.SKIP, CardValue.SKIP_DOUBLE -> {
				if (virtualPlayer) waitABit(2)
				currPlayer = nextPlayer()
				if (curr.value == CardValue.SKIP_DOUBLE && activePlayerCount > 2) currPlayer = nextPlayer()
				return
			}
			CardValue.DRAW -> {
				if (virtualPlayer) waitABit(2) else currPlayer = nextPlayer()
				forceDraw(cp, 2)
				if (virtualPlayer) currPlayer = nextPlayer()
				return
			}
			CardValue.DRAW_SPREAD -> {
				handleSpreader(virtualPlayer)
				return
			}
			else -> {}
		}

		handleWildCards(curr, virtualPlayer)
		handleOtherSpecialCards(curr, virtualPlayer)
	}

	private suspend fun handleSpreader(virtualPlayer: Boolean) {
		var everyone = virtualPlayer
		for (p in players) {
			if (p !== currPlayer && p.active && checkForShield(p.hand)) {
				nextPlayerPreset = p
				message(GameMessage.HasBlueShield(p.seat))
				currPlayer = nextPlayer()
				everyone = false
				break
			}
		}
		if (everyone) {
			waitABit(2)
			forceDraw(cp, 2)
		}
		for (i in 1 until activePlayerCount) {
			currPlayer = nextPlayer()
			forceDraw(cp, 2)
		}
		if (everyone) currPlayer = nextPlayer()
	}

	private fun handleWildCards(curr: Card, virtualPlayer: Boolean) {
		val generator = if (virtualPlayer) null else currPlayer
		when (curr.type) {
			CardType.WILD_DRAW_FOUR, CardType.WILD_HOS -> {
				penalty.addCards(curr, 4, generator, if (virtualPlayer) currPlayer else getNextPlayer())
				changed()
			}
			CardType.WILD_HD -> {
				penalty.addCards(curr, 8, generator, if (virtualPlayer) currPlayer else getNextPlayer())
				changed()
			}
			CardType.WILD_MYSTERY -> if (!virtualPlayer) handleMysteryDraw()
			else -> {}
		}
	}

	/** Double Barrel skips one player; separate because it moves the turn first. */
	private suspend fun handleDoubleBarrel(curr: Card, virtualPlayer: Boolean) {
		val origin = currPlayer
		if (activePlayerCount > 2) currPlayer = nextPlayer()
		penalty.addCards(curr, 4,
			if (virtualPlayer) null else origin,
			if (virtualPlayer) currPlayer else getNextPlayer())
		changed()
	}

	private fun handleMysteryDraw() {
		val prev = prevCard ?: return
		val prevNum = prev.value.number
		if (prev.type == CardType.YELLOW_69) {
			penalty.addCards(lastPlayedCard, 69, currPlayer, getNextPlayer())
		} else if (prevNum != null && prevNum in 1..9) {
			penalty.addCards(lastPlayedCard, prevNum, currPlayer, getNextPlayer())
		}
		changed()
	}

	private suspend fun handleOtherSpecialCards(curr: Card, virtualPlayer: Boolean) {
		when (curr.type) {
			CardType.WILD_DB -> handleDoubleBarrel(curr, virtualPlayer)

			CardType.RED_0_HD -> {
				if (penalty.victim === currPlayer) {
					penalty.generatingPlayer = currPlayer
					penalty.victim = getNextPlayer()
					message(GameMessage.HolyDefender(penalty.victim!!.seat))
				}
			}

			CardType.RED_2_GLASNOST -> {
				if (cp.hand.isEmpty) return
				val victim = player(cp.controller.chooseVictim(this, cp, VictimReason.GLASNOST))
				penalty.setFaceup(curr, if (virtualPlayer) null else currPlayer, victim)
				nextPlayerPreset = victim
				changed()
			}

			CardType.RED_5_MAGIC -> {
				if (penalty.victim === currPlayer && penalty.hasHotDeath) {
					penalty.removeHotDeath()
					message(GameMessage.Magic5)
				}
			}

			CardType.GREEN_0_QUITTER -> {
				if (cp.hand.isEmpty) return
				if (activePlayerCount > 2) {
					if (virtualPlayer) penalty.setEject(curr, null, currPlayer)
					else penalty.setEject(curr, currPlayer, getNextPlayer())
					changed()
				}
			}

			CardType.GREEN_3_AIDS -> {
				if (penalty.victim === currPlayer) {
					val g = penalty.generatingPlayer
					penalty.victim = g
					penalty.generatingPlayer = currPlayer
					penalty.secondaryVictim = currPlayer
					nextPlayerPreset = g
				}
			}

			CardType.BLUE_0_FUCK_YOU -> handleFuckYou()

			CardType.YELLOW_1_MAD -> {
				if (cp.hand.isEmpty) return
				if (activePlayerCount > 3) {
					val victim = player(cp.controller.chooseVictim(this, cp, VictimReason.MAD))
					penalty.setEject(curr, currPlayer, victim)
					penalty.secondaryVictim = currPlayer
					changed()
				}
			}

			// Clone: repeat the effect of the previous card onto the next player.
			CardType.GREEN_2_CLONE, CardType.YELLOW_2_CLONE -> {
				if (!virtualPlayer && prevCard != null) handleClone()
			}

			// Ping: chosen player draws exactly 1 card. Unblockable.
			CardType.BLUE_1_PING -> {
				if (!virtualPlayer) ping()
			}

			else -> {}
		}
	}

	private suspend fun ping() {
		val from = cp
		val victim = player(from.controller.chooseVictim(this, from, VictimReason.PING))
		message(GameMessage.Ping(from.seat, victim.seat))
		// Bypasses all shields and modifiers per rules.
		val drawn = drawFromPile()
		if (drawn != null) {
			victim.hand.add(drawn)
			emit(GameEvent.CardDrawn(drawn, victim.seat, victim.hand.isVisible(drawn), animMs()))
			waitABit(15)
		}
		waitABit(2)
	}

	/** Swap the held cards of two players. Table cards are not swapped, per rules. */
	private fun swapHands(a: Player, b: Player) {
		val cardsA = a.hand.heldCards.toList()
		val cardsB = b.hand.heldCards.toList()
		for (c in cardsA) a.hand.remove(c)
		for (c in cardsB) b.hand.remove(c)
		for (c in cardsB) a.hand.add(c)
		for (c in cardsA) b.hand.add(c)
		changed()
	}

	/**
	 * Clone effect: re-apply the previous card's effect, aimed at the next player.
	 *
	 * Not cloneable (no effect): Clone itself (no chains), AIDS, the 69,
	 * Blue Shield, Magic 5 and plain number cards.
	 */
	private suspend fun handleClone() {
		val prev = prevCard ?: return
		val prevVal = prev.value
		if (prevVal == CardValue.CLONE) return

		message(GameMessage.Clone(prev.type))

		when {
			prevVal == CardValue.WILD_DRAW -> {
				if (prev.type == CardType.WILD_MYSTERY) {
					handleMysteryDraw()
				} else {
					val amt = if (prev.type == CardType.WILD_HD) 8 else 4
					penalty.addCards(prev, amt, currPlayer, getNextPlayer())
					changed()
				}
			}
			prevVal == CardValue.DRAW -> {
				penalty.addCards(prev, 2, currPlayer, getNextPlayer())
				changed()
			}
			prevVal == CardValue.DRAW_SPREAD -> handleSpreader(false)
			prevVal.isSkipFamily || prevVal == CardValue.REVERSE_SKIP -> {
				currPlayer = nextPlayer()
				if (prevVal == CardValue.SKIP_DOUBLE && activePlayerCount > 2) currPlayer = nextPlayer()
			}
			prevVal == CardValue.REVERSE || prevVal == CardValue.REVERSE_BACKSTAB || prevVal == CardValue.REVERSE_SWAP -> {
				changeDirection()
				val before = getPlayerBefore(cp)
				if (prevVal == CardValue.REVERSE_BACKSTAB && before !== currPlayer) forceDraw(before, 2)
				if (prevVal == CardValue.REVERSE_SWAP && before !== currPlayer) {
					swapHands(cp, before)
					message(GameMessage.Swap(cp.seat, before.seat))
				}
				if (activePlayerCount == 2) currPlayer = nextPlayer()
			}
			prev.type == CardType.RED_2_GLASNOST -> {
				if (cp.hand.isEmpty) return
				val victim = player(cp.controller.chooseVictim(this, cp, VictimReason.GLASNOST))
				penalty.setFaceup(prev, currPlayer, victim)
				nextPlayerPreset = victim
				changed()
			}
			prev.type == CardType.GREEN_0_QUITTER -> {
				if (activePlayerCount > 2) {
					penalty.setEject(prev, currPlayer, getNextPlayer())
					changed()
				}
			}
			prev.type == CardType.YELLOW_1_MAD -> {
				if (cp.hand.isEmpty) return
				if (activePlayerCount > 3) {
					val victim = player(cp.controller.chooseVictim(this, cp, VictimReason.MAD))
					penalty.setEject(prev, currPlayer, victim)
					penalty.secondaryVictim = currPlayer
					changed()
				}
			}
			prev.type == CardType.RED_0_HD -> {
				if (penalty.isActive && penalty.victim === currPlayer) {
					penalty.generatingPlayer = currPlayer
					penalty.victim = getNextPlayer()
					message(GameMessage.HolyDefender(penalty.victim!!.seat))
				}
			}
			prev.type == CardType.BLUE_0_FUCK_YOU -> {
				if (penalty.isActive && penalty.victim === currPlayer) {
					val g = penalty.generatingPlayer ?: getNextPlayer()
					penalty.victim = g
					penalty.generatingPlayer = currPlayer
					message(GameMessage.SendingPenalty(g.seat))
				}
			}
			prev.type == CardType.BLUE_1_PING -> {
				if (cp.hand.isEmpty) return
				ping()
			}
		}
	}

	private suspend fun handleFuckYou() {
		if (penalty.victim !== currPlayer) return
		val g = penalty.generatingPlayer ?: getNextPlayer()
		penalty.victim = g
		penalty.generatingPlayer = currPlayer

		val origType = penalty.origCard?.type
		if (origType == CardType.WILD_DB && activePlayerCount > 2 && g !== getNextPlayer()) {
			currPlayer = nextPlayer()
		} else if (origType == CardType.RED_2_GLASNOST) {
			nextPlayerPreset = g
			currPlayer = nextPlayer()
		}
		message(GameMessage.SendingPenalty(g.seat))
	}

	// =========================================================================
	// Penalty assessment
	// =========================================================================

	private suspend fun assessPenalty() {
		if (!penalty.isActive) return
		val victim = penalty.victim
		val victim2 = penalty.secondaryVictim
		if (victim == null && victim2 == null) return

		when (penalty.type) {
			PenaltyType.CARD -> assessCardPenalty(victim, victim2)
			PenaltyType.FACEUP -> assessFaceUpPenalty(victim, victim2)
			PenaltyType.EJECT -> assessEjectPenalty(victim, victim2)
			PenaltyType.NONE -> {}
		}
		penalty.reset()
		changed()
	}

	private suspend fun assessCardPenalty(victim: Player?, victim2: Player?) {
		var numCards = penalty.numCards
		if (victim2 != null) numCards = (numCards + 1) / 2
		val reason = penalty.origCard?.type

		if (victim != null) {
			message(GameMessage.TakesPenalty(victim.seat, numCards, reason))
			forceDraw(victim, numCards)
		} else {
			waitABit(2)
		}

		if (victim2 != null) {
			if (currPlayer !== victim2) {
				nextPlayerPreset = victim2
				currPlayer = nextPlayer()
			}
			val numCards2 = penalty.numCards / 2
			message(GameMessage.TakesPenalty(victim2.seat, numCards2, reason))
			forceDraw(victim2, numCards2)
		}
	}

	private suspend fun assessFaceUpPenalty(victim: Player?, victim2: Player?) {
		if (victim != null) {
			placeOnTable(victim)
			if (players.any { it.isHuman }) message(GameMessage.FaceUp(victim.seat))
		}
		if (victim2 != null) {
			if (currPlayer !== victim2) {
				nextPlayerPreset = victim2
				currPlayer = nextPlayer()
			}
			placeOnTable(victim2)
			if (players.any { it.isHuman }) message(GameMessage.FaceUp(victim2.seat))
		} else {
			nextPlayerPreset = penalty.generatingPlayer ?: dealer
			currPlayer = nextPlayer()
		}
	}

	private suspend fun assessEjectPenalty(victim: Player?, victim2: Player?) {
		if (victim != null) {
			message(GameMessage.EjectedBy(victim.seat, penalty.generatingPlayer?.seat))
			ejectPlayer(victim)
		}
		if (victim2 != null) ejectPlayer(victim2)

		if (activePlayerCount == 1) {
			penalty.reset()
			val last = players.first { it.active }
			last.hand.clear()
			finishRound(last)
		}
	}

	private suspend fun ejectPlayer(p: Player) {
		nextPlayerPreset = p
		currPlayer = nextPlayer()
		p.active = false
		message(GameMessage.Ejected(p.seat))
		if (p.isHuman && !anyHumanActive) setShowFastForward(true)
		changed()
	}

	// =========================================================================
	// Round finish / scoring
	// =========================================================================

	private suspend fun finishRound(winner: Player) {
		fastForward = false
		setShowFastForward(false)
		setShowMenu(false)
		announceIndicator(direction, null)

		dealer = winner
		calculateScore(winner)
		roundComplete = true

		for (p in players) {
			p.active = true
			revealHand(p)
		}

		message(GameMessage.RoundWinner(winner.seat))

		val endScore = if (standardRules) 500 else 1000
		if (players.any { it.totalScore >= endScore }) gameOver = true

		snapshot = capture()
		if (!gameOver) {
			setShowNextRound(true)
		} else {
			gameWinner = players.minByOrNull { it.totalScore }!!.seat
		}
		changed()
	}

	private fun calculateScore(winner: Player) {
		val scores = IntArray(4)
		for ((i, p) in players.withIndex()) {
			if (p.hand.hasAllBastardCards()) continue
			val v = p.hand.calculateValue()
			scores[i] = v.total
			p.virusPenalty += 10 * v.aidsCount
		}
		val maxScore = maxOf(0, scores.maxOrNull() ?: 0)

		// Shitter penalty: whoever holds the Shitter inherits the highest score.
		for ((i, p) in players.withIndex()) {
			if (!p.hand.hasAllBastardCards() && p.hand.has(CardType.YELLOW_0_SHITTER)) scores[i] = maxScore
			p.lastScore = scores[i]
			val virusPen = if (p === winner) 0 else p.virusPenalty
			p.lastVirusPenalty = virusPen
			p.totalScore += scores[i] + virusPen
		}
	}

	private suspend fun revealHand(p: Player) = animateFlips(p.hand.revealAll())

	private suspend fun placeOnTable(p: Player) = animateFlips(p.hand.moveAllToTable())

	private suspend fun animateFlips(cards: List<Card>) {
		cards.forEachIndexed { i, c ->
			emit(GameEvent.CardFlipped(c, animMs()))
			waitABit(if (i == cards.lastIndex) 2 else 120)
		}
	}

	// =========================================================================
	// Draw pile
	// =========================================================================

	private fun drawFromPile(): Card? {
		if (drawPile.isEmpty) {
			return if (rolloverDiscardPile() > 0) drawPile.draw() else null
		}
		val c = drawPile.draw()
		// Proactively roll over if the draw pile is now empty and the discard pile has enough cards.
		// Quietly: the drawn card is not in a hand yet, so the table is not in a state to show.
		if (drawPile.isEmpty && discardPile.size >= 5) rolloverDiscardPile(notify = false)
		return c
	}

	private fun rolloverDiscardPile(notify: Boolean = true): Int {
		val numPlayed = discardPile.size
		if (numPlayed > 1) {
			val topCard = discardPile.draw()!!
			message(GameMessage.ShufflingDiscard(numPlayed - 1))
			while (!discardPile.isEmpty) drawPile.add(discardPile.draw()!!)
			drawPile.shuffle(random)
			discardPile.add(topCard)
			if (notify) changed()
			return numPlayed - 1
		}
		if (!forceDrawing) {
			changed()
			message(GameMessage.DiscardEmpty)
		}
		return 0
	}

	/** Makes `p` draw `n` cards as a penalty (Luck of the Irish saves one). */
	private suspend fun forceDraw(p: Player, n: Int) {
		var numCards = n
		if (numCards <= 0) return

		val irish = p.hand.cards.firstOrNull { it.type == CardType.GREEN_4_IRISH }
		if (irish != null) {
			numCards--
			p.hand.reveal(irish)
			message(GameMessage.LuckOfIrish)
		}

		message(GameMessage.Drawing(p.seat, numCards))
		logger("${p.seat} draws $numCards")

		if (numCards > drawPile.size) rolloverDiscardPile()

		forceDrawing = true
		var notEnough = false
		for (i in 0 until numCards) {
			val c = drawFromPile()
			if (c == null) {
				notEnough = true
				break
			}
			p.hand.add(c)
			emit(GameEvent.CardDrawn(c, p.seat, p.hand.isVisible(c), animMs()))
			waitABit(15)
		}
		waitABit(2)
		forceDrawing = false

		if (notEnough) message(GameMessage.DiscardEmpty)
	}

	private fun checkForShield(h: Hand): Boolean {
		val shield = h.cards.firstOrNull { it.type == CardType.BLUE_2_SHIELD } ?: return false
		h.reveal(shield)
		return true
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

	private fun animMs(): Long = if (pacing) delayMs() / 4 else 0

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
		val p = currPlayer
		if (p == null || p.seat != seat || !p.isHuman || penalty.isActive) return
		humanController(seat)?.submitTurn(TurnAction.Draw)
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

	fun humanPass(seat: Seat) {
		val p = currPlayer ?: return
		if (p.seat == seat && p.isHuman) humanController(seat)?.submitTurn(TurnAction.Pass)
	}

	fun humanPlay(seat: Seat, c: Card) {
		val p = currPlayer ?: return
		val h = humanController(seat) ?: return
		if (p.seat != seat) {
			// Tapping a card on someone else's turn: say so.
			if (roundIsActive && player(seat).isHuman && c in player(seat).hand && pendingPrompt == null)
				message(GameMessage.CardNoGood(seat, NoGoodReason.NotYourTurn))
			return
		}
		if (!p.isHuman || !roundIsActive || !h.awaitingTurn || c !in p.hand) return
		if (checkCard(p.hand, c)) h.submitTurn(TurnAction.Play(c))
		else message(GameMessage.CardNoGood(seat, whyInvalid(p.hand, c)))
	}

	/** Why [c] cannot be played from [h] now (assumes [checkCard] said no). */
	fun whyInvalid(h: Hand, c: Card): NoGoodReason {
		val curr = lastPlayedCard
		if (penalty.isActive) {
			return if (curr?.type == CardType.GREEN_3_AIDS) NoGoodReason.Aids
			else NoGoodReason.UnderAttack(penalty.numCards)
		}
		if (c.type == CardType.YELLOW_0_SHITTER) return NoGoodReason.Shitter
		if (standardRules && c.value == CardValue.WILD_DRAW && h.hasColorMatch(currColor)) return NoGoodReason.HoldsColor(currColor)
		return NoGoodReason.NoMatch(currColor, curr?.type)
	}

	/** Answers the open prompt, if it belongs to [seat]. */
	fun submitColor(seat: Seat, c: CardColor) {
		if (promptSeat == seat && c != CardColor.WILD) humanController(seat)?.submitColor(c)
	}

	fun submitVictim(seat: Seat, victim: Seat) {
		val p = pendingPrompt as? Prompt.Victim ?: return
		if (promptSeat == seat && victim in p.options) humanController(seat)?.submitVictim(victim)
	}

	fun submitNumCardsToDeal(seat: Seat, n: Int) {
		if (promptSeat == seat) humanController(seat)?.submitNumCardsToDeal(n)
	}

	/** A person left: a computer plays that seat from now on. */
	fun handOverToComputer(seat: Seat) {
		val h = humanController(seat) ?: return
		h.handOver()
		if (!anyHumanActive && roundIsActive) setShowFastForward(true)
		changed()
	}

	/** A person (e.g. one who reconnected) plays [seat] again. */
	fun takeBack(seat: Seat) {
		val h = humanController(seat) ?: return
		if (h.autopilot == null) return
		h.takeBack()
		fastForward = false
		showFastForwardButton = false
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

	/** A person can now act on their turn. */
	internal fun turnStarted() = changed()

	internal fun startClock(seat: Seat, limitMs: Long) {
		clockSeat = seat
		clockDeadline = System.currentTimeMillis() + limitMs
	}

	internal fun stopClock() {
		if (clockSeat == null) return
		clockSeat = null
		changed()
	}

	internal fun timedOut(seat: Seat) = message(GameMessage.TimedOut(seat))

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

	private suspend fun animateDiscard(card: Card, from: Seat?) {
		emit(GameEvent.CardDiscarded(card, from, animMs()))
		waitABit(2)
		announceIndicator(direction, currColor)
	}

	private suspend fun announceIndicator(dir: Direction, color: CardColor?) {
		if (dir == indicatorDirection && color == indicatorColor) return
		indicatorDirection = dir
		indicatorColor = color
		emit(GameEvent.IndicatorChanged(dir, color, animMs()))
		waitABit(2)
	}

	private fun changeDirection() {
		direction = direction.reversed()
		logger("direction change → $direction")
	}

	private fun setShowNextRound(show: Boolean) {
		showNextRoundButton = show
		changed()
	}

	private fun setShowFastForward(show: Boolean) {
		showFastForwardButton = show
		changed()
	}

	private fun setShowMenu(show: Boolean) {
		showMenu = show
		changed()
	}

	private fun emit(e: GameEvent) {
		listener?.onEvent(e)
	}

	private fun changed() = emit(GameEvent.StateChanged)

	private fun message(m: GameMessage) {
		if (!fastForward) emit(GameEvent.Message(m))
	}

	// =========================================================================
	// Save / restore
	// =========================================================================

	private fun capture(): GameSnapshot = GameSnapshot(
		deckType = deckType,
		drawPile = drawPile.asList.map { it.deckIndex },
		discardPile = discardPile.asList.map { it.deckIndex },
		currColor = currColor?.id ?: 0,
		direction = direction.id,
		cardsPlayed = cardsPlayed,
		roundComplete = roundComplete,
		currCard = lastPlayedCard?.deckIndex ?: -1,
		prevCard = prevCard?.deckIndex ?: -1,
		currPlayer = currPlayer?.seat?.number ?: 0,
		dealer = dealer?.seat?.number ?: 0,
		nextPlayerPreset = nextPlayerPreset?.seat?.number ?: 0,
		players = players.map { p ->
			PlayerSnapshot(
				active = p.active,
				totalScore = p.totalScore,
				lastScore = p.lastScore,
				hasTriedDrawing = p.hasTriedDrawing,
				lastDrawn = p.lastDrawn?.deckIndex ?: -1,
				virusPenalty = p.virusPenalty,
				lastVirusPenalty = p.lastVirusPenalty,
				tableCards = p.hand.tableCards.map { it.deckIndex },
				heldCards = p.hand.heldCards.map { it.deckIndex },
				revealed = p.hand.revealedCards.map { it.deckIndex },
				faceUp = p.hand.faceUp,
			)
		},
		penalty = PenaltySnapshot(
			type = penalty.type.id,
			numCards = penalty.numCards,
			hasHotDeath = penalty.hasHotDeath,
			generatingPlayer = penalty.generatingPlayer?.seat?.number ?: 0,
			victim = penalty.victim?.seat?.number ?: 0,
			secondaryVictim = penalty.secondaryVictim?.seat?.number ?: 0,
			origCard = penalty.origCard?.deckIndex ?: -1,
		),
	)

	companion object {
		/** Plays in one round after which it is called a stalemate (far more than a real round needs). */
		const val STALEMATE_PLAYS = 1000

		fun newGame(config: GameConfig, random: Random = Random.Default, pacing: Boolean = true): Game =
			Game(config, CardDeck(config.deckType), random, pacing)

		/** Rebuilds a game from a snapshot; throws [IllegalArgumentException] if it is unusable. */
		fun restore(s: GameSnapshot, config: GameConfig, random: Random = Random.Default, pacing: Boolean = true): Game {
			val g = Game(config, CardDeck(s.deckType), random, pacing)
			fun card(i: Int): Card? = g.deck[i]
			fun seatPlayer(n: Int): Player? = Seat.fromNumber(n)?.let { g.player(it) }

			g.drawPile.addAll(s.drawPile.mapNotNull(::card))
			g.discardPile.addAll(s.discardPile.mapNotNull(::card))
			g.currColor = CardColor.fromId(s.currColor)
			g.direction = Direction.fromId(s.direction)
			g.cardsPlayed = s.cardsPlayed
			g.roundComplete = s.roundComplete
			g.lastPlayedCard = card(s.currCard)
			g.prevCard = card(s.prevCard)

			require(s.players.size == 4) { "expected 4 players" }
			for ((i, ps) in s.players.withIndex()) {
				val p = g.players[i]
				val faceUp = ps.faceUp ?: config.faceUp
				val h = Hand(faceUp).also { it.keepSorted = p.controller is HumanController }
				h.restore(ps.tableCards.mapNotNull(::card), ps.heldCards.mapNotNull(::card), ps.revealed.mapNotNull(::card))
				p.replaceHand(h)
				p.active = ps.active
				p.totalScore = ps.totalScore
				p.lastScore = ps.lastScore
				p.virusPenalty = ps.virusPenalty
				p.lastVirusPenalty = ps.lastVirusPenalty
				p.hasTriedDrawing = ps.hasTriedDrawing
				p.lastDrawn = card(ps.lastDrawn)
			}

			val pen = s.penalty
			g.penalty.restore(PenaltyType.fromId(pen.type), pen.numCards, pen.hasHotDeath, card(pen.origCard),
				seatPlayer(pen.generatingPlayer), seatPlayer(pen.victim), seatPlayer(pen.secondaryVictim))

			g.currPlayer = requireNotNull(seatPlayer(s.currPlayer)) { "no current player" }
			g.dealer = requireNotNull(seatPlayer(s.dealer)) { "no dealer" }
			g.nextPlayerPreset = seatPlayer(s.nextPlayerPreset)
			if (!g.roundComplete) requireNotNull(g.lastPlayedCard) { "no card in play" }

			g.snapshot = s
			g.resuming = true
			return g
		}
	}
}

data class GameSnapshot(
	val deckType: DeckType,
	val drawPile: List<Int>,
	val discardPile: List<Int>,
	val currColor: Int,
	val direction: Int,
	val cardsPlayed: Int,
	val roundComplete: Boolean,
	val currCard: Int,
	val prevCard: Int,
	val currPlayer: Int,
	val dealer: Int,
	val nextPlayerPreset: Int,
	val players: List<PlayerSnapshot>,
	val penalty: PenaltySnapshot,
)

data class PlayerSnapshot(
	val active: Boolean,
	val totalScore: Int,
	val lastScore: Int,
	val hasTriedDrawing: Boolean,
	val lastDrawn: Int,
	val virusPenalty: Int,
	val lastVirusPenalty: Int,
	val tableCards: List<Int>,
	val heldCards: List<Int>,
	val revealed: List<Int>,
	/** Null in saves from older versions. */
	val faceUp: Boolean?,
)

data class PenaltySnapshot(
	val type: Int,
	val numCards: Int,
	val hasHotDeath: Boolean,
	val generatingPlayer: Int,
	val victim: Int,
	val secondaryVictim: Int,
	val origCard: Int,
)
