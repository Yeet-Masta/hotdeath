package com.smorgasbork.hotdeath.engine

/** Settings the engine reads while a game runs (backed by preferences on Android). */
interface GameConfig {
	val deckType: DeckType
	val faceUp: Boolean
	/** Seats played by people; every other seat is a computer. */
	val humanSeats: Set<Seat>
	val cheatLevel: Int
	/** 0 (fastest) .. 4 (slowest). */
	val pauseLength: Int
	fun skill(seat: Seat): Int
	fun aggression(seat: Seat): Int
	/** Seconds a person has for each decision before a computer makes it for them; 0 = no limit. */
	val turnSeconds: Int get() = 0
}

/** Why a card the player tapped cannot be played. */
sealed interface NoGoodReason {
	data object NotYourTurn : NoGoodReason
	/** Under attack: only a defender (or taking the penalty) will do. */
	data class UnderAttack(val count: Int) : NoGoodReason
	/** Nothing defends against AIDS. */
	data object Aids : NoGoodReason
	data object Shitter : NoGoodReason
	/** Standard rules: no Wild Draw 4 while holding a card of the current color. */
	data class HoldsColor(val color: CardColor?) : NoGoodReason
	/** Needs the current [color], or something that plays on [top]. */
	data class NoMatch(val color: CardColor?, val top: CardType?) : NoGoodReason
}

/** Something the UI should show the player. Formatting into text is the UI's job. */
sealed interface GameMessage {
	data class Dealing(val dealer: Seat, val count: Int) : GameMessage
	data object TapDrawPile : GameMessage
	/** Shown only to [seat]. */
	data class YourPlay(val seat: Seat) : GameMessage
	/** Shown only to [seat]. */
	data class CardNoGood(val seat: Seat, val why: NoGoodReason? = null) : GameMessage
	/** [seat] ran out of time; a computer made the move. */
	data class TimedOut(val seat: Seat) : GameMessage
	data class PlayerDrawsCard(val seat: Seat) : GameMessage
	data class TakesPenalty(val seat: Seat, val count: Int, val reason: CardType?) : GameMessage
	data class FaceUp(val seat: Seat) : GameMessage
	data class EjectedBy(val seat: Seat, val by: Seat?) : GameMessage
	data class Ejected(val seat: Seat) : GameMessage
	data class RoundWinner(val seat: Seat) : GameMessage
	/** `count` cards from the discard pile were shuffled into the draw pile. */
	data class ShufflingDiscard(val count: Int) : GameMessage
	data object DiscardEmpty : GameMessage
	data object LuckOfIrish : GameMessage
	data class Drawing(val seat: Seat, val count: Int) : GameMessage
	data class AllBastardCards(val seat: Seat) : GameMessage
	/** The round went on so long nobody could win it; the lowest hand takes it. */
	data object Stalemate : GameMessage
	data class HasBlueShield(val seat: Seat) : GameMessage
	data class HolyDefender(val victim: Seat) : GameMessage
	data object Magic5 : GameMessage
	data class SendingPenalty(val victim: Seat) : GameMessage
	data class Swap(val a: Seat, val b: Seat) : GameMessage
	data class Clone(val card: CardType) : GameMessage
	data class Ping(val from: Seat, val to: Seat) : GameMessage
}

/** A decision the human player must make before the game can continue. */
sealed interface Prompt {
	data object NumCardsToDeal : Prompt
	data object Color : Prompt
	data class Victim(val options: List<Seat>) : Prompt
}

/**
 * Everything the engine tells the UI. Animation events carry the duration the
 * engine will wait for; [StateChanged] means "redraw / re-read game state".
 */
sealed interface GameEvent {
	/** A new round started: reset the indicator and color chooser. */
	data object RoundReset : GameEvent
	data class CardDealt(val card: Card, val dealer: Seat, val to: Seat, val faceUp: Boolean, val durationMs: Long) : GameEvent
	data class CardDrawn(val card: Card, val to: Seat, val faceUp: Boolean, val durationMs: Long) : GameEvent
	/** Card stays where it is and turns face-up. */
	data class CardFlipped(val card: Card, val durationMs: Long) : GameEvent
	/** [from] is the seat that played it; null when it was turned up from the draw pile. */
	data class CardDiscarded(val card: Card, val from: Seat?, val durationMs: Long) : GameEvent
	/** Cheat: `newCard` goes from the draw pile to [seat], `oldCard` back to the pile. */
	data class CheatSwap(val seat: Seat, val newCard: Card, val oldCard: Card, val durationMs: Long) : GameEvent
	/** Turn pointer moves to `seat`; [Direction.NONE] means jump instead of sweep. */
	data class PointerMoved(val seat: Seat, val direction: Direction, val durationMs: Long) : GameEvent
	/** `color` null hides the indicator. */
	data class IndicatorChanged(val direction: Direction, val color: CardColor?, val durationMs: Long) : GameEvent
	data class ColorChooserShown(val show: Boolean, val durationMs: Long) : GameEvent
	data class Message(val message: GameMessage) : GameEvent
	data object StateChanged : GameEvent
}

fun interface GameListener {
	fun onEvent(event: GameEvent)
}
