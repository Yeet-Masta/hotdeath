package com.smorgasbork.hotdeath.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decisions for a person, at this device or a remote one. Each decision
 * suspends until an answer is submitted, and publishes a [Prompt] on the game
 * so a recreated screen (or a reconnecting client) can show it again.
 *
 * [handOver] gives the seat to a computer player, e.g. when a remote player
 * disconnects; a decision already waiting is then made by the computer.
 */
class HumanController : PlayerController {

	/** Null while a person plays this seat. Completing a decision with null means "ask the autopilot". */
	var autopilot: PlayerController? = null
		private set

	private var turn: CompletableDeferred<TurnAction?>? = null
	private var color: CompletableDeferred<CardColor?>? = null
	private var victim: CompletableDeferred<Seat?>? = null
	private var numCards: CompletableDeferred<Int?>? = null

	val awaitingTurn: Boolean get() = turn != null

	/** Makes a single decision for a person who ran out of time. */
	private val standIn = ComputerController()
	private val fallback: PlayerController get() = autopilot ?: standIn

	/** A person (e.g. a reconnecting player) plays this seat again. */
	fun takeBack() {
		autopilot = null
	}

	fun handOver(computer: PlayerController = ComputerController()) {
		if (autopilot != null) return
		autopilot = computer
		turn?.complete(null)
		color?.complete(null)
		victim?.complete(null)
		numCards?.complete(null)
	}

	override suspend fun takeTurn(game: Game, me: Player): TurnAction {
		autopilot?.let { return it.takeTurn(game, me) }
		val d = CompletableDeferred<TurnAction?>()
		turn = d
		val action = try {
			timed(game, me) { game.turnStarted(); d.await() }
		} finally {
			turn = null
		}
		return action ?: fallback.takeTurn(game, me)
	}

	override suspend fun chooseNumCardsToDeal(game: Game, me: Player): Int {
		autopilot?.let { return it.chooseNumCardsToDeal(game, me) }
		return prompt(game, me, Prompt.NumCardsToDeal, CompletableDeferred<Int?>().also { numCards = it }) { numCards = null }
			?: fallback.chooseNumCardsToDeal(game, me)
	}

	override suspend fun chooseColor(game: Game, me: Player): CardColor {
		autopilot?.let { return it.chooseColor(game, me) }
		return prompt(game, me, Prompt.Color, CompletableDeferred<CardColor?>().also { color = it }) { color = null }
			?: fallback.chooseColor(game, me)
	}

	override suspend fun chooseVictim(game: Game, me: Player, reason: VictimReason): Seat {
		autopilot?.let { return it.chooseVictim(game, me, reason) }
		val options = Seat.entries.filter { it != me.seat && game.player(it).active }
		// If only one other player is active, select automatically.
		if (options.size == 1) return options[0]
		return prompt(game, me, Prompt.Victim(options), CompletableDeferred<Seat?>().also { victim = it }) { victim = null }
			?: fallback.chooseVictim(game, me, reason)
	}

	private suspend fun <T> prompt(game: Game, me: Player, p: Prompt, d: CompletableDeferred<T?>, clear: () -> Unit): T? {
		try {
			return timed(game, me) { game.beginPrompt(p, me.seat); d.await() }
		} finally {
			clear()
			game.endPrompt()
		}
	}

	/**
	 * Runs [wait] under the game's per-decision time limit. Null when time ran
	 * out (or the seat was handed over), so the caller asks [fallback].
	 */
	private suspend fun <T> timed(game: Game, me: Player, wait: suspend () -> T?): T? {
		val limitMs = game.config.turnSeconds * 1000L
		if (limitMs <= 0) return wait()
		game.startClock(me.seat, limitMs)
		try {
			val r = withTimeoutOrNull(limitMs) { wait() }
			if (r == null && autopilot == null) game.timedOut(me.seat)
			return r
		} finally {
			game.stopClock()
		}
	}

	// ── answers (main thread) ─────────────────────────────────────────────────

	fun submitTurn(action: TurnAction): Boolean = turn?.complete(action) ?: false

	fun submitColor(c: CardColor): Boolean = color?.complete(c) ?: false

	fun submitVictim(seat: Seat): Boolean = victim?.complete(seat) ?: false

	fun submitNumCardsToDeal(n: Int): Boolean = numCards?.complete(n.coerceIn(5, 15)) ?: false
}
