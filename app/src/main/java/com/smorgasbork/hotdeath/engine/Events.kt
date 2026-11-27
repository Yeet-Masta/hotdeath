package com.smorgasbork.hotdeath.engine

/** Settings the engine reads while a game runs (backed by preferences on Android). */
interface GameConfig {
	val faceUp: Boolean
	/** Seats played by people; every other seat is a computer. */
	val humanSeats: Set<Seat>
	/** 0 (fastest) .. 4 (slowest). */
	val pauseLength: Int
	/** Last player holding cards loses, instead of first out wins. */
	val eliminationMode: Boolean
	/** Call UNO automatically for people. */
	val autoUno: Boolean
	/** How long people get to jump in. */
	val jumpInMs: Long
	/** 0 (easy) .. 2 (hard). */
	fun skill(seat: Seat): Int
}

/** Every kind of announcement. Which fields of [GameMessage] are used is noted per kind. */
enum class Msg {
	DEALING,            // seat = dealer, n = cards each
	TAP_DRAW_PILE,
	YOUR_PLAY,          // seat (private)
	YOUR_ANSWER,        // seat (private), card = attack card, n = cards
	JUMP_IN_NOW,        // seat (private), card
	CARD_NO_GOOD,       // seat (private)
	DRAWS_CARD,         // seat
	DRAWING,            // seat, n
	TAKES_ATTACK,       // seat, n, card
	JUMPED_IN,          // seat, card
	WRONG_JUMP_IN,      // seat
	CALLS_UNO,          // seat
	CAUGHT_NO_UNO,      // seat = caught, other = caller
	FALSE_UNO,          // seat
	CANNOT_WIN_NO_UNO,  // seat
	ROUND_WINNER,       // seat
	ROUND_LOSER,        // seat (elimination mode)
	ROUND_NO_WINNER,
	PLAYER_OUT,         // seat (finished, elimination mode)
	REMOVED,            // seat, other = by whom (nullable), card = how
	REVIVED,            // seat, other = by whom
	REVIVE_BLOCKED,     // seat = who blocked, other = target
	SHUFFLING_DISCARD,  // n
	DISCARD_EMPTY,
	BLOCKED,            // seat = blocker, card = blocking card
	SHIELD_ABSORBS,     // seat
	SHIELD_BREAKS,      // seat
	IMMUNE,             // seat (Yin and Yang)
	SENT_BACK,          // seat = new victim
	REDIRECTED,         // seat = new victim, other = by whom
	DODGED,             // seat = dodger, other = new victim
	SPLIT,              // seat = AIDS player, other = partner, n = total
	MAGIC_5,            // seat, n = cards removed
	STACKED,            // seat, n = total
	PENN_STATE,         // seat
	SKIPPED,            // seat
	FACE_UP,            // seat
	PING,               // seat = from, other = to
	GIFT,               // seat = from, other = to
	GOLD_COIN_UP,       // seat
	GOLD_COIN_DOWN,     // seat
	SWAP,               // seat, other
	CLONE,              // card = what was cloned
	COMMUNISM,
	I_DONT_LIKE_THESE,  // seat
	CHOSEN_ONE,         // seat
	FLOOD,
	FIELD_ACTIVE,       // seat (null = neutral), card
	FIELD_REMOVED,      // seat (null = neutral), card
	VIRUS,              // seat = infected
	CURED,              // seat
	DEATH_CLOCK,        // seat, n = turns left
	FAMINE_DRAW,        // seat, n
	WAR_COLOR,          // seat, card = the card that sets the banned color
	PLAGUE_DRAW,        // seat, n
	PLAGUE_OVER,        // seat = carrier
	TEAM_UP,            // seat = keeps hand, other = joins
	INVASION,           // seat = attacker, other = defender
	INVASION_WON,       // seat = winner, other = loser
	INVASION_TIE,
	CONQUEROR,          // seat
	COLOR_CHANGE,       // seat
	PROPHET_SEES,       // seat = prophet, other = whose hand
	PROPHET_QUESTION,   // seat = asker, other = about, card = what
	PROPHET_YES,
	PROPHET_NO,
	ACCUSES,            // seat = accuser, other = accused
	ACCUSATION_RIGHT,   // seat = accused
	ACCUSATION_WRONG,   // seat = accuser
	CAUGHT_QUITTER,     // seat = holder
	RECYCLING,          // seat = who started it
	RECYCLE_DUMP,       // seat, n
	APOCALYPSE,
	DIRECTION,          // reverse, for announcements
}

/**
 * Something the UI should show. [to] non-null means only that seat sees it.
 * Formatting into text is the UI's job (see the field notes on [Msg]).
 */
data class GameMessage(
	val kind: Msg,
	val seat: Seat? = null,
	val other: Seat? = null,
	val n: Int = 0,
	val card: CardType? = null,
	val to: Seat? = null,
)

/** Why a choice is being asked for. */
enum class Reason {
	PING, GLASNOST, GIFT, DEATH, VIRUS, MAD, MARTYR, FTPIP, GOLD_COIN, PROPHET, NECROMANCER,
	INVADE, ACCUSE, GIFT_CARD, ARMY, CHOSEN_ONE, REMOVE_HORSEMAN,
}

/** A decision a person must make before the game can continue. */
sealed interface Prompt {
	data object Color : Prompt
	data class Victim(val reason: Reason, val options: List<Seat>, val optional: Boolean = false) : Prompt
	/** [options] are deck indices; [optional] adds "none". */
	data class ChooseCard(val reason: Reason, val options: List<Int>, val optional: Boolean = false) : Prompt
}

/**
 * Everything the engine tells the UI. Animation events carry the duration the
 * engine will wait for; [StateChanged] means "redraw / re-read game state".
 */
sealed interface GameEvent {
	/** A new round started: reset the indicator and color chooser. */
	data object RoundReset : GameEvent
	data class CardDealt(val card: Card, val dealer: Seat, val to: Seat, val durationMs: Long) : GameEvent
	data class CardDrawn(val card: Card, val to: Seat, val durationMs: Long) : GameEvent
	/** Card stays where it is and turns face-up. */
	data class CardFlipped(val card: Card, val durationMs: Long) : GameEvent
	/** [from] is the seat that played it; null when it was turned up from the draw pile. */
	data class CardDiscarded(val card: Card, val from: Seat?, val durationMs: Long) : GameEvent
	/** A card moved in front of a player ([to]) or into neutral play ([to] null). */
	data class CardToField(val card: Card, val from: Seat?, val to: Seat?, val durationMs: Long) : GameEvent
	/** A card went from one hand to another (Gift, Martyr, Swap). */
	data class CardPassed(val card: Card, val from: Seat, val to: Seat, val durationMs: Long) : GameEvent
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
