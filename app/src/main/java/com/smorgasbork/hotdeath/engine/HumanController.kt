package com.smorgasbork.hotdeath.engine

import kotlinx.coroutines.CompletableDeferred

/**
 * Decisions for a person, at this device or a remote one. Each decision
 * suspends until an answer is submitted, and publishes what is being asked
 * on the game ([Game.deciding] or a [Prompt]) so a recreated screen (or a
 * reconnecting client) can show it again.
 *
 * [handOver] gives the seat to a computer player, e.g. when a remote player
 * disconnects; a decision already waiting is then made by the computer.
 */
class HumanController : PlayerController {

	/** Null while a person plays this seat. Completing a decision with null means "ask the autopilot". */
	var autopilot: PlayerController? = null
		private set

	private var action: CompletableDeferred<TurnAction?>? = null
	private var color: CompletableDeferred<CardColor?>? = null
	private var victim: CompletableDeferred<Seat?>? = null
	private var card: CompletableDeferred<Card?>? = null
	/** Distinguishes "chose none" from "handed over" for optional prompts. */
	private var handedOver = false

	val awaitingAction: Boolean get() = action != null

	fun handOver(computer: PlayerController = ComputerController()) {
		if (autopilot != null) return
		autopilot = computer
		handedOver = true
		action?.complete(null)
		color?.complete(null)
		victim?.complete(null)
		card?.complete(null)
	}

	override suspend fun takeTurn(game: Game, me: Player): TurnAction {
		autopilot?.let { return it.takeTurn(game, me) }
		return awaitAction(game) ?: autopilot!!.takeTurn(game, me)
	}

	override suspend fun answer(game: Game, me: Player, attack: Attack): TurnAction {
		autopilot?.let { return it.answer(game, me, attack) }
		return awaitAction(game) ?: autopilot!!.answer(game, me, attack)
	}

	private suspend fun awaitAction(game: Game): TurnAction? {
		val d = CompletableDeferred<TurnAction?>()
		action = d
		game.turnStarted()
		try {
			return d.await()
		} finally {
			action = null
		}
	}

	override suspend fun chooseColor(game: Game, me: Player): CardColor {
		autopilot?.let { return it.chooseColor(game, me) }
		return prompt(game, me, Prompt.Color, CompletableDeferred<CardColor?>().also { color = it }) { color = null }
			?: autopilot!!.chooseColor(game, me)
	}

	override suspend fun chooseVictim(game: Game, me: Player, reason: Reason, options: List<Seat>, optional: Boolean): Seat? {
		autopilot?.let { return it.chooseVictim(game, me, reason, options, optional) }
		// Only one choice: take it.
		if (options.size == 1 && !optional) return options[0]
		val r = prompt(game, me, Prompt.Victim(reason, options, optional), CompletableDeferred<Seat?>().also { victim = it }) { victim = null }
		if (r == null && (autopilot != null && handedOver)) return autopilot!!.chooseVictim(game, me, reason, options, optional)
		return r ?: if (optional) null else options.first()
	}

	override suspend fun chooseCard(game: Game, me: Player, reason: Reason, options: List<Card>, optional: Boolean): Card? {
		autopilot?.let { return it.chooseCard(game, me, reason, options, optional) }
		if (options.size == 1 && !optional) return options[0]
		val p = Prompt.ChooseCard(reason, options.map { it.deckIndex }, optional)
		val r = prompt(game, me, p, CompletableDeferred<Card?>().also { card = it }) { card = null }
		if (r == null && (autopilot != null && handedOver)) return autopilot!!.chooseCard(game, me, reason, options, optional)
		return r ?: if (optional) null else options.first()
	}

	/** People jump in by tapping the card while the window is open. */
	override fun jumpInDelay(game: Game, me: Player, card: Card, windowMs: Long): Long? =
		autopilot?.jumpInDelay(game, me, card, windowMs)

	private suspend fun <T> prompt(game: Game, me: Player, p: Prompt, d: CompletableDeferred<T?>, clear: () -> Unit): T? {
		game.beginPrompt(p, me.seat)
		try {
			return d.await()
		} finally {
			clear()
			game.endPrompt()
		}
	}

	// ── answers (main thread) ─────────────────────────────────────────────────

	fun submit(a: TurnAction): Boolean = action?.complete(a) ?: false

	fun submitColor(c: CardColor): Boolean = color?.complete(c) ?: false

	fun submitVictim(seat: Seat?): Boolean = victim?.complete(seat) ?: false

	fun submitCard(c: Card?): Boolean = card?.complete(c) ?: false
}
