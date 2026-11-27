package com.smorgasbork.hotdeath.net

import com.smorgasbork.hotdeath.engine.Card
import com.smorgasbork.hotdeath.engine.CardColor
import com.smorgasbork.hotdeath.engine.DeckType
import com.smorgasbork.hotdeath.engine.Direction
import com.smorgasbork.hotdeath.engine.Game
import com.smorgasbork.hotdeath.engine.GameEvent
import com.smorgasbork.hotdeath.engine.GameMessage
import com.smorgasbork.hotdeath.engine.PenaltyType
import com.smorgasbork.hotdeath.engine.Player
import com.smorgasbork.hotdeath.engine.Prompt
import com.smorgasbork.hotdeath.engine.Seat

/*
 * What one player is allowed to see of a game, and what they may do.
 *
 * The table is always drawn from a TableView, locally or over the network,
 * so a remote device only ever receives what its player could see at a real
 * table. Card ids: a card the viewer can see is its deck index (>= 0); a
 * face-down card in someone else's hand is a negative id that says nothing
 * about the card (see [hiddenId]).
 */

data class PlayerView(
	val seat: Seat,
	val active: Boolean,
	val isHuman: Boolean,
	val totalScore: Int,
	val lastScore: Int,
	val lastVirusPenalty: Int,
	val table: List<Int>,
	val held: List<Int>,
) {
	val size: Int get() = table.size + held.size
}

data class PenaltyView(
	val type: PenaltyType,
	val numCards: Int,
	val origCard: Int,
	val victim: Seat?,
	val generator: Seat?,
)

data class TableView(
	val me: Seat,
	val deckType: DeckType,
	/** Display name per seat index; null means "use the seat name". */
	val names: List<String?>,
	val players: List<PlayerView>,
	val drawPileSize: Int,
	/** Discard pile, bottom to top (always face-up). */
	val discard: List<Int>,
	val currPlayer: Seat?,
	val currColor: CardColor?,
	val lastPlayed: Int,
	val roundComplete: Boolean,
	val waitingToStartRound: Boolean,
	val gameOver: Boolean,
	val gameWinner: Seat?,
	val fastForward: Boolean,
	val showNextRound: Boolean,
	val showFastForward: Boolean,
	/** The Draw / Pass / Help panel (shown on the viewer's turn). */
	val showMenu: Boolean,
	val canDraw: Boolean,
	val canPass: Boolean,
	/** Ids of the viewer's held cards they may play right now. */
	val legal: List<Int>,
	/** Whose decision is on the clock, or null when none is. */
	val clockSeat: Seat? = null,
	/** Time [clockSeat] had left when this view was made. */
	val clockLeftMs: Long = 0,
	/** A question only the viewer must answer, or null. */
	val prompt: Prompt?,
	val penalty: PenaltyView?,
	val indicatorDirection: Direction,
	val indicatorColor: CardColor?,
	val delayMs: Long,
) {
	fun player(s: Seat): PlayerView = players[s.index]
	val myTurn: Boolean get() = currPlayer == me && player(me).isHuman
	val roundIsActive: Boolean get() = !waitingToStartRound && !gameOver
	fun name(s: Seat): String? = names.getOrNull(s.index)
}

/** One animation or announcement, as the viewer may see it. */
sealed interface ViewEvent {
	data object RoundReset : ViewEvent
	data class Dealt(val card: Int, val dealer: Seat, val to: Seat, val durationMs: Long) : ViewEvent
	data class Drawn(val card: Int, val to: Seat, val durationMs: Long) : ViewEvent
	data class Flipped(val card: Int, val durationMs: Long) : ViewEvent
	data class Discarded(val card: Int, val from: Seat?, val durationMs: Long) : ViewEvent
	data class CheatSwap(val newCard: Int, val oldCard: Int, val durationMs: Long) : ViewEvent
	data class PointerMoved(val seat: Seat, val direction: Direction, val durationMs: Long) : ViewEvent
	data class IndicatorChanged(val direction: Direction, val color: CardColor?, val durationMs: Long) : ViewEvent
	data class ColorChooser(val show: Boolean, val durationMs: Long) : ViewEvent
	data class Message(val message: GameMessage) : ViewEvent
}

/** Id of the k-th face-down card in [seat]'s hand. Says nothing about which card it is. */
fun hiddenId(seat: Seat, k: Int): Int = -(1 + seat.index * 1000 + k)

private fun Player.canSee(viewer: Seat, c: Card): Boolean = seat == viewer || hand.isVisible(c)

/** The viewer's ids for [p]'s held cards: visible ones first, then the face-down ones. */
private fun heldIds(p: Player, viewer: Seat): List<Int> {
	val (shown, hidden) = p.hand.heldCards.partition { p.canSee(viewer, it) }
	return shown.map { it.deckIndex } + List(hidden.size) { hiddenId(p.seat, it) }
}

/** The viewer's id for [c] wherever it is now. */
private fun Game.idFor(c: Card, viewer: Seat): Int {
	val owner = players.firstOrNull { c in it.hand } ?: return c.deckIndex
	if (owner.canSee(viewer, c)) return c.deckIndex
	// A newly arrived face-down card is shown as the last face-down card.
	val hidden = owner.hand.heldCards.count { !owner.canSee(viewer, it) }
	return hiddenId(owner.seat, hidden - 1)
}

private fun Game.visibleTo(c: Card, viewer: Seat): Boolean =
	players.firstOrNull { c in it.hand }?.canSee(viewer, c) ?: discardPile.asList.contains(c)

fun Game.viewFor(me: Seat, names: List<String?> = List(4) { null }): TableView {
	val cp = currPlayer
	// Only offer moves once the engine is waiting for this person's turn.
	val myTurn = cp?.seat == me && cp.isHuman && humanController(me)?.awaitingTurn == true
	val canDraw = myTurn && !penalty.isActive && !currPlayerHasDrawn
	return TableView(
		me = me,
		deckType = deckType,
		names = names,
		players = players.map { p ->
			PlayerView(
				seat = p.seat,
				active = p.active,
				isHuman = p.isHuman,
				totalScore = p.totalScore,
				lastScore = p.lastScore,
				lastVirusPenalty = p.lastVirusPenalty,
				table = p.hand.tableCards.map { it.deckIndex },
				held = heldIds(p, me),
			)
		},
		drawPileSize = drawPile.size,
		discard = discardPile.asList.map { it.deckIndex },
		currPlayer = cp?.seat,
		currColor = currColor,
		lastPlayed = lastPlayedCard?.deckIndex ?: -1,
		roundComplete = roundComplete,
		waitingToStartRound = waitingToStartRound,
		gameOver = gameOver,
		gameWinner = gameWinner,
		fastForward = fastForward,
		showNextRound = showNextRoundButton,
		showFastForward = showFastForwardButton,
		showMenu = showMenu && myTurn,
		canDraw = canDraw,
		canPass = myTurn && !canDraw,
		clockSeat = clockSeat,
		clockLeftMs = if (clockSeat == null) 0 else (clockDeadline - System.currentTimeMillis()).coerceAtLeast(0),
		legal = if (!myTurn) emptyList() else player(me).hand.cards.filter { checkCard(player(me).hand, it) }.map { it.deckIndex },
		prompt = if (promptSeat == me) pendingPrompt else null,
		penalty = if (!penalty.isActive) null else PenaltyView(
			type = penalty.type,
			numCards = penalty.numCards,
			origCard = penalty.origCard?.deckIndex ?: -1,
			victim = penalty.victim?.seat,
			generator = penalty.generatingPlayer?.seat,
		),
		indicatorDirection = indicatorDirection,
		indicatorColor = indicatorColor,
		delayMs = delayMs(),
	)
}

/** [e] as [viewer] may see it; null when they should not see it at all. */
fun Game.eventFor(e: GameEvent, viewer: Seat): ViewEvent? = when (e) {
	GameEvent.RoundReset -> ViewEvent.RoundReset
	is GameEvent.CardDealt -> ViewEvent.Dealt(idFor(e.card, viewer), e.dealer, e.to, e.durationMs)
	is GameEvent.CardDrawn -> ViewEvent.Drawn(idFor(e.card, viewer), e.to, e.durationMs)
	is GameEvent.CardFlipped -> if (visibleTo(e.card, viewer)) ViewEvent.Flipped(e.card.deckIndex, e.durationMs) else null
	is GameEvent.CardDiscarded -> ViewEvent.Discarded(e.card.deckIndex, e.from, e.durationMs)
	is GameEvent.CheatSwap ->
		if (e.seat == viewer) ViewEvent.CheatSwap(e.newCard.deckIndex, e.oldCard.deckIndex, e.durationMs) else null
	is GameEvent.PointerMoved -> ViewEvent.PointerMoved(e.seat, e.direction, e.durationMs)
	is GameEvent.IndicatorChanged -> ViewEvent.IndicatorChanged(e.direction, e.color, e.durationMs)
	is GameEvent.ColorChooserShown -> if (promptSeat == viewer) ViewEvent.ColorChooser(e.show, e.durationMs) else null
	is GameEvent.Message -> if (shows(e.message, viewer)) ViewEvent.Message(e.message) else null
	GameEvent.StateChanged -> null
}

private fun shows(m: GameMessage, viewer: Seat): Boolean = when (m) {
	is GameMessage.YourPlay -> m.seat == viewer
	is GameMessage.CardNoGood -> m.seat == viewer
	is GameMessage.PlayerDrawsCard -> m.seat != viewer
	else -> true
}
