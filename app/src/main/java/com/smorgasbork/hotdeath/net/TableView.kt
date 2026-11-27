package com.smorgasbork.hotdeath.net

import com.smorgasbork.hotdeath.engine.AttackKind
import com.smorgasbork.hotdeath.engine.Card
import com.smorgasbork.hotdeath.engine.CardColor
import com.smorgasbork.hotdeath.engine.CardType
import com.smorgasbork.hotdeath.engine.Decision
import com.smorgasbork.hotdeath.engine.Direction
import com.smorgasbork.hotdeath.engine.Game
import com.smorgasbork.hotdeath.engine.GameEvent
import com.smorgasbork.hotdeath.engine.GameMessage
import com.smorgasbork.hotdeath.engine.Player
import com.smorgasbork.hotdeath.engine.Prompt
import com.smorgasbork.hotdeath.engine.Seat
import com.smorgasbork.hotdeath.engine.Status
import com.smorgasbork.hotdeath.engine.answers

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
	val status: Status,
	val isHuman: Boolean,
	val wins: Int,
	val losses: Int,
	/** Cards in play in front of the player (always face up). */
	val field: List<Int>,
	val held: List<Int>,
	val calledUno: Boolean,
	/** Turns Death leaves them; -1 if Death is not on them. */
	val deathClock: Int,
	val teammate: Seat?,
) {
	val size: Int get() = held.size
	val active: Boolean get() = status == Status.ACTIVE
}

data class AttackView(
	val kind: AttackKind,
	val card: CardType,
	val amount: Int,
	val pile: Int,
	val victim: Seat,
	val source: Seat,
)

data class TableView(
	val me: Seat,
	/** Display name per seat index; null means "use the seat name". */
	val names: List<String?>,
	val players: List<PlayerView>,
	val drawPileSize: Int,
	/** Discard pile, bottom to top (always face-up). */
	val discard: List<Int>,
	/** The open recycle pile (innermost), bottom to top, and how many are open. */
	val recycle: List<Int>,
	val recyclePiles: Int,
	/** Famine, War and Horsemen in neutral play. */
	val neutral: List<Int>,
	val currPlayer: Seat?,
	val currColor: CardColor?,
	/** What the next card must match (may be a field card, or a "Red 4"). */
	val top: CardType?,
	val apocalypse: Boolean,
	val elimination: Boolean,
	val roundComplete: Boolean,
	val waitingToStartRound: Boolean,
	val roundWinner: Seat?,
	val roundLoser: Seat?,
	val fastForward: Boolean,
	val showNextRound: Boolean,
	val showFastForward: Boolean,
	/** What the game is waiting for the viewer to do. */
	val decision: Decision,
	/** Cards the viewer may play right now (turn, answer or jump-in). */
	val legal: List<Int>,
	val canDraw: Boolean,
	val canPass: Boolean,
	val jumpIn: Boolean,
	/** A question only the viewer must answer, or null. */
	val prompt: Prompt?,
	val attack: AttackView?,
	val canAccuse: Boolean,
	val invadeTargets: List<Seat>,
	val canAskProphet: Boolean,
	val prophet: Seat?,
	val prophetTarget: Seat?,
	val canChangeColor: Boolean,
	val indicatorDirection: Direction,
	val indicatorColor: CardColor?,
	val delayMs: Long,
) {
	fun player(s: Seat): PlayerView = players[s.index]
	val myTurn: Boolean get() = decision == Decision.TURN
	val answering: Boolean get() = decision == Decision.ANSWER
	/** The Draw / Take it / UNO panel. */
	val showMenu: Boolean get() = roundIsActive && player(me).active && player(me).isHuman
	val roundIsActive: Boolean get() = !waitingToStartRound && !roundComplete
	fun name(s: Seat): String? = names.getOrNull(s.index)
	/** The card shown on top of the play area. */
	val topCard: Int get() = recycle.lastOrNull() ?: discard.lastOrNull() ?: -1
}

/** One animation or announcement, as the viewer may see it. */
sealed interface ViewEvent {
	data object RoundReset : ViewEvent
	data class Dealt(val card: Int, val dealer: Seat, val to: Seat, val durationMs: Long) : ViewEvent
	data class Drawn(val card: Int, val to: Seat, val durationMs: Long) : ViewEvent
	data class Flipped(val card: Int, val durationMs: Long) : ViewEvent
	data class Discarded(val card: Int, val from: Seat?, val durationMs: Long) : ViewEvent
	/** A card went in front of [to] (null: neutral play). */
	data class ToField(val card: Int, val from: Seat?, val to: Seat?, val durationMs: Long) : ViewEvent
	data class Passed(val card: Int, val from: Seat, val to: Seat, val durationMs: Long) : ViewEvent
	data class PointerMoved(val seat: Seat, val direction: Direction, val durationMs: Long) : ViewEvent
	data class IndicatorChanged(val direction: Direction, val color: CardColor?, val durationMs: Long) : ViewEvent
	data class ColorChooser(val show: Boolean, val durationMs: Long) : ViewEvent
	data class Message(val message: GameMessage) : ViewEvent
}

/** Id of the k-th face-down card in [seat]'s hand. Says nothing about which card it is. */
fun hiddenId(seat: Seat, k: Int): Int = -(1 + seat.index * 1000 + k)

private fun Game.canSee(owner: Player, viewer: Seat, c: Card): Boolean =
	owner.seat == viewer || owner.hand.isVisible(c) || c in player(viewer).peeked

/** The viewer's ids for [p]'s held cards: visible ones first, then the face-down ones. */
private fun Game.heldIds(p: Player, viewer: Seat): List<Int> {
	val (shown, hidden) = p.hand.cards.partition { canSee(p, viewer, it) }
	return shown.map { it.deckIndex } + List(hidden.size) { hiddenId(p.seat, it) }
}

/** The viewer's id for [c] wherever it is now. */
private fun Game.idFor(c: Card, viewer: Seat): Int {
	val owner = players.firstOrNull { c in it.hand } ?: return c.deckIndex
	if (canSee(owner, viewer, c)) return c.deckIndex
	// A newly arrived face-down card is shown as the last face-down card.
	val hidden = owner.hand.cards.count { !canSee(owner, viewer, it) }
	return hiddenId(owner.seat, hidden - 1)
}

private fun Game.visibleTo(c: Card, viewer: Seat): Boolean {
	val owner = players.firstOrNull { c in it.hand } ?: return true
	return canSee(owner, viewer, c)
}

fun Game.viewFor(me: Seat, names: List<String?> = List(4) { null }): TableView {
	val p = player(me)
	val deciding = deciding === p && p.isHuman && humanController(me)?.awaitingAction == true
	val myDecision = if (deciding) decision else Decision.NONE
	val window = jumpWindow?.eligible?.get(me)
	val legal = when {
		window != null -> window
		myDecision == Decision.TURN -> legalPlays(p)
		myDecision == Decision.ANSWER -> attack?.let { answers(p, it) } ?: emptyList()
		else -> emptyList()
	}.map { it.deckIndex }
	val canDraw = myDecision == Decision.TURN && p.drawnThisTurn == null && !(famineActive && legal.isNotEmpty())
	val a = attack
	return TableView(
		me = me,
		names = names,
		players = players.map { pl ->
			PlayerView(
				seat = pl.seat,
				status = pl.status,
				isHuman = pl.isHuman,
				wins = pl.wins,
				losses = pl.losses,
				field = pl.field.map { it.deckIndex },
				held = heldIds(pl, me),
				calledUno = pl.calledUno,
				deathClock = if (pl.deathClock >= 0) pl.deathClock + pl.luckCount - pl.virusCount else -1,
				teammate = pl.teammate?.seat,
			)
		},
		drawPileSize = drawPile.size,
		discard = discardPile.asList.map { it.deckIndex },
		recycle = recyclePiles.lastOrNull()?.pile?.asList?.map { it.deckIndex } ?: emptyList(),
		recyclePiles = recyclePiles.size,
		neutral = neutral.map { it.deckIndex },
		currPlayer = currPlayer?.seat,
		currColor = currColor,
		top = topType,
		apocalypse = apocalypse,
		elimination = config.eliminationMode,
		roundComplete = roundComplete,
		waitingToStartRound = waitingToStartRound,
		roundWinner = roundWinner,
		roundLoser = roundLoser,
		fastForward = fastForward,
		showNextRound = showNextRoundButton,
		showFastForward = showFastForwardButton,
		decision = myDecision,
		legal = legal,
		canDraw = canDraw,
		canPass = myDecision == Decision.ANSWER || (myDecision == Decision.TURN && p.drawnThisTurn != null),
		jumpIn = window != null,
		prompt = if (promptSeat == me) pendingPrompt else null,
		attack = a?.let { AttackView(it.kind, it.card, it.amount, it.pile.size, it.victim.seat, it.source.seat) },
		canAccuse = roundIsActive && p.active && !apocalypse,
		invadeTargets = if (myDecision == Decision.TURN) invadeTargets(p) else emptyList(),
		canAskProphet = myDecision == Decision.TURN && canAskProphet(p),
		prophet = prophet?.seat,
		prophetTarget = prophetTarget?.seat,
		canChangeColor = myDecision == Decision.TURN && canChangeColor(p),
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
	is GameEvent.CardToField -> ViewEvent.ToField(e.card.deckIndex, e.from, e.to, e.durationMs)
	is GameEvent.CardPassed -> ViewEvent.Passed(idFor(e.card, viewer), e.from, e.to, e.durationMs)
	is GameEvent.PointerMoved -> ViewEvent.PointerMoved(e.seat, e.direction, e.durationMs)
	is GameEvent.IndicatorChanged -> ViewEvent.IndicatorChanged(e.direction, e.color, e.durationMs)
	is GameEvent.ColorChooserShown -> if (promptSeat == viewer) ViewEvent.ColorChooser(e.show, e.durationMs) else null
	is GameEvent.Message -> if (e.message.to == null || e.message.to == viewer) ViewEvent.Message(e.message) else null
	GameEvent.StateChanged -> null
}
