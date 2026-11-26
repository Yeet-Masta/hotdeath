package com.smorgasbork.hotdeath.engine

import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * AI player. Skill levels (per seat, from [GameConfig.skill]):
 *   0 = Weak    – plays the highest-value valid card; naive victim choice
 *   1 = Strong  – holds wilds while opponents have many cards; smarter heuristics;
 *                 targets the opponent closest to winning
 *   2 = Expert  – all of Strong, plus color-balance optimisation, endgame pressure
 *                 (skip cards to protect its own win), and smarter v3 card use
 *
 * Aggression biases attacks toward seats played by people.
 */
class ComputerController(private val random: Random = Random.Default) : PlayerController {

	private companion object {
		/** Hand size at or below which the AI is in "endgame" mode. */
		const val ENDGAME_HAND_SIZE = 3
		/** Bonus for skip-family cards that protect the AI's own endgame. */
		const val ENDGAME_SKIP_BONUS = 60
		/** Draw-penalty size at which the AI strongly prefers defenders. */
		const val DEFEND_THRESHOLD = 4
	}

	private var skill = 1
	private var aggression = 0

	private fun readSettings(game: Game, me: Player) {
		skill = game.config.skill(me.seat)
		aggression = game.config.aggression(me.seat)
	}

	// -------------------------------------------------------------------------
	// Decisions
	// -------------------------------------------------------------------------

	override suspend fun chooseNumCardsToDeal(game: Game, me: Player): Int = random.nextInt(11) + 5

	override suspend fun chooseColor(game: Game, me: Player): CardColor {
		game.waitABit()
		// Pick the color we hold the most of so follow-up plays are more likely.
		return CardColor.SUITS
			.map { it to me.hand.countSuit(it) }
			.filter { it.second > 0 }
			.maxByOrNull { it.second }?.first
			?: CardColor.RED
	}

	override suspend fun chooseVictim(game: Game, me: Player, reason: VictimReason): Seat {
		readSettings(game, me)
		game.waitABit()
		// Ping is unblockable, so always aim it at the most threatening opponent.
		if (skill == 0 && reason != VictimReason.PING) return chooseVictimWeak(game, me)
		return fewestCardsOpponent(game, me) ?: fallbackVictim(game, me)
	}

	/**
	 * The active opponent with the fewest cards (closest to winning), treating
	 * people as having fewer cards the more aggressive this AI is.
	 */
	private fun fewestCardsOpponent(game: Game, me: Player): Seat? {
		var minCards = Int.MAX_VALUE
		var best: Seat? = null
		for (p in game.players.asReversed()) {
			if (p === me || !p.active) continue
			var count = p.hand.size
			if (p.isHuman) count -= aggression * 2
			if (count < minCards) {
				minCards = count
				best = p.seat
			}
		}
		return best
	}

	private fun fallbackVictim(game: Game, me: Player): Seat =
		game.players.firstOrNull { it !== me && it.active }?.seat ?: me.seat

	/** Clockwise from SOUTH when aggressive, otherwise counter-clockwise from EAST. */
	private fun chooseVictimWeak(game: Game, me: Player): Seat {
		val order = if (aggression > 3) game.players else game.players.asReversed()
		return order.firstOrNull { it !== me && it.active }?.seat ?: fallbackVictim(game, me)
	}

	// -------------------------------------------------------------------------
	// Turn
	// -------------------------------------------------------------------------

	override suspend fun takeTurn(game: Game, me: Player): TurnAction {
		readSettings(game, me)
		game.waitABit()

		val hand = me.hand
		if (!game.hasValidCards(hand)) {
			return if (me.hasTriedDrawing) TurnAction.Pass else TurnAction.Draw
		}

		// If we just drew, play it if legal – otherwise pass.
		me.lastDrawn?.let { drawn ->
			return if (game.checkCard(hand, drawn)) TurnAction.Play(drawn) else TurnAction.Pass
		}

		val values = hand.estimateValue()
		val ctx = Context(
			game = game,
			me = me,
			values = values,
			opponentMinCards = minCardsRemaining(game, me),
			wildCount = hand.countSuit(CardColor.WILD),
			penaltyCards = game.penalty.numCards,
			baseBalance = colorBalance(hand, null),
		)

		var maxTestVal = Int.MIN_VALUE
		var best: Card? = null
		for (tc in hand.cards) {
			val check = game.evaluate(hand, tc)
			if (check == PlayCheck.INVALID) continue
			// Always play a defender immediately when under a heavy penalty.
			if (check == PlayCheck.DEFENDER && ctx.penaltyCards >= DEFEND_THRESHOLD) {
				best = tc
				break
			}
			// TODO: improve this AI
			//   - throw the mystery draw on numbered cards
			//   - don't throw MAD when point count in hand is too high (unless player count < 4)
			//   - consider the damage of offensive cards; look to hit players with low card counts
			//   - advanced: throw MAD to catch an opponent and force them over 1000 points
			val testVal = scoreCandidate(ctx, tc)
			if (testVal >= maxTestVal) {
				maxTestVal = testVal
				best = tc
			}
		}

		return best?.let { TurnAction.Play(it) } ?: TurnAction.Draw
	}

	private class Context(
		val game: Game,
		val me: Player,
		/** Card values of the whole hand, computed once per turn. */
		val values: HandValue,
		val opponentMinCards: Int,
		val wildCount: Int,
		val penaltyCards: Int,
		/** Color balance of the whole hand, computed once per turn. */
		val baseBalance: Double,
	)

	/** Heuristic value of playing `tc`. Higher = more desirable. */
	private fun scoreCandidate(ctx: Context, tc: Card): Int {
		val base = ctx.values.valueOf(tc)
		if (skill == 0) return base

		var testVal = if (tc.color == CardColor.WILD) {
			// Hold wilds while opponents still have many cards AND the draw pile is healthy.
			val drawPileHealthy = ctx.game.drawPile.size > 15
			val opponentsHaveCards = ctx.wildCount < ctx.opponentMinCards - 1
			if (opponentsHaveCards && drawPileHealthy) 0 else base
		} else {
			base
		}

		testVal = adjustForMad(ctx, tc, testVal)
		testVal = adjustForSixtyNine(ctx, tc, testVal)
		testVal = adjustForMysteryDraw(ctx, tc, testVal)
		testVal = adjustForV3Cards(ctx, tc, testVal)

		if (skill >= 2) {
			testVal = adjustForColorBalance(ctx, tc, testVal)
			testVal = adjustForEndgamePressure(ctx, tc, testVal)
		}

		if (ctx.penaltyCards >= DEFEND_THRESHOLD) testVal = adjustForDefender(ctx, tc, testVal)
		return testVal
	}

	// -------------------------------------------------------------------------
	// Heuristic adjustors
	// -------------------------------------------------------------------------

	private fun adjustForMad(ctx: Context, tc: Card, testVal: Int): Int {
		if (tc.type != CardType.YELLOW_1_MAD || ctx.game.activePlayerCount <= 3) return testVal
		val newHandValue = ctx.me.hand.estimateValue(tc).total
		return when {
			newHandValue < 10 -> testVal + 100
			newHandValue < 20 -> testVal + 70
			newHandValue < 50 -> 0
			else -> testVal - 20
		}
	}

	private fun adjustForSixtyNine(ctx: Context, tc: Card, testVal: Int): Int {
		if (tc.type != CardType.YELLOW_69) return testVal
		return ctx.values.total - ctx.me.hand.estimateValue(tc).total
	}

	private fun adjustForMysteryDraw(ctx: Context, tc: Card, testVal: Int): Int {
		if (tc.type != CardType.WILD_MYSTERY) return testVal
		val lpc = ctx.game.lastPlayedCard ?: return testVal
		if (lpc.type == CardType.YELLOW_69) return testVal + 200
		val n = lpc.value.number ?: return testVal
		return when {
			n <= 0 -> testVal
			n < 5 -> testVal + 15
			n < 8 -> testVal + 30
			else -> testVal + 50
		}
	}

	/**
	 *  - Backstab: bonus when the player before us is close to winning (they draw 2)
	 *  - Swap:     bonus when we hold more cards than the player before us
	 *  - Clone:    bonus proportional to the strength of the previous card
	 *  - Ping:     bonus the fewer cards the most threatening opponent holds
	 */
	private fun adjustForV3Cards(ctx: Context, tc: Card, testValIn: Int): Int {
		var testVal = testValIn
		val game = ctx.game
		when (tc.value) {
			CardValue.REVERSE_BACKSTAB -> {
				val before = game.getPlayerBefore(ctx.me)
				if (before !== ctx.me) {
					val theirCards = before.hand.size
					if (theirCards <= 3) testVal += 50 else if (theirCards <= 6) testVal += 25
				}
			}
			CardValue.REVERSE_SWAP -> {
				val before = game.getPlayerBefore(ctx.me)
				if (before !== ctx.me) {
					val delta = before.hand.size - ctx.me.hand.size // positive = we'd gain cards
					testVal += when {
						delta < -3 -> 60
						delta < 0 -> 30
						delta > 3 -> -40
						else -> -10
					}
				}
			}
			CardValue.CLONE -> {
				val prev = game.lastPlayedCard ?: return testVal - 10
				testVal += when (prev.value) {
					CardValue.WILD_DRAW -> 80
					CardValue.DRAW -> 40
					CardValue.DRAW_SPREAD -> 70
					CardValue.SKIP, CardValue.SKIP_DOUBLE -> 30
					else -> 10
				}
			}
			else -> if (tc.type == CardType.BLUE_1_PING) {
				testVal += 15
				testVal += when {
					ctx.opponentMinCards <= 1 -> 40
					ctx.opponentMinCards <= 2 -> 25
					ctx.opponentMinCards <= 3 -> 10
					else -> 0
				}
			}
		}
		return testVal
	}

	/** Near our own win, prefer cards that stop opponents from going out first. */
	private fun adjustForEndgamePressure(ctx: Context, tc: Card, testValIn: Int): Int {
		var testVal = testValIn
		if (ctx.me.hand.size > ENDGAME_HAND_SIZE) return testVal
		val v = tc.value
		if (v.isSkipFamily || v == CardValue.REVERSE_SKIP) testVal += ENDGAME_SKIP_BONUS
		if (v.isDrawFamily) testVal += ENDGAME_SKIP_BONUS / 2
		// Save the wild if a matching color card will do.
		if (tc.color == CardColor.WILD && ctx.me.hand.countSuit(ctx.game.currColor) > 0) testVal -= 20
		return testVal
	}

	/** Under a draw penalty, strongly reward a legal defender. */
	private fun adjustForDefender(ctx: Context, tc: Card, testVal: Int): Int {
		val isDefender = tc.type == CardType.RED_0_HD || tc.type == CardType.BLUE_0_FUCK_YOU
				|| tc.type == CardType.GREEN_3_AIDS || tc.type == CardType.RED_5_MAGIC
		val isStack = tc.value == CardValue.WILD_DRAW && ctx.game.penalty.origCard?.value == CardValue.WILD_DRAW
		return if (isDefender || isStack) testVal + ctx.penaltyCards * 15 else testVal
	}

	private fun adjustForColorBalance(ctx: Context, tc: Card, testVal: Int): Int {
		if ((ctx.opponentMinCards + aggression / 3) <= 3) return testVal
		if (ctx.baseBalance == 0.0) return testVal
		val change = (colorBalance(ctx.me.hand, tc) - ctx.baseBalance) / ctx.baseBalance
		return when {
			change < -0.5 -> testVal + 40
			change < -0.25 -> testVal + 20
			change > 0.5 -> testVal - 40
			change > 0.25 -> testVal - 20
			else -> testVal
		}
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	/** Spread of the four suit counts (lower = more balanced), optionally without one card. */
	internal fun colorBalance(hand: Hand, exclude: Card?): Double {
		val totals = IntArray(4)
		for (c in hand.cards) {
			if (c === exclude) continue
			val i = CardColor.SUITS.indexOf(c.color)
			if (i >= 0) totals[i]++
		}
		val avg = totals.sum() / 4.0
		return sqrt(totals.sumOf { (it - avg).pow(2) })
	}

	private fun minCardsRemaining(game: Game, me: Player): Int =
		game.players.filter { it !== me && it.active }.minOfOrNull { it.hand.size } ?: Int.MAX_VALUE
}
