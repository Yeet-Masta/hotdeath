package com.smorgasbork.hotdeath.engine

/** What a player decided to do when asked to act. */
sealed interface TurnAction {
	/** Play a card (on your turn, as an answer to an attack, or as a jump-in). */
	data class Play(val card: Card) : TurnAction
	/** Draw one card and end the turn. */
	data object Draw : TurnAction
	/** Take the attack (answers), or end the turn (after drawing, Apocalypse rules). */
	data object Pass : TurnAction
	/** Conquest: invade instead of playing. */
	data class Invade(val target: Seat) : TurnAction

	// ── free actions: do not use the turn ─────────────────────────────────────
	data object CallUno : TurnAction
	data class Accuse(val target: Seat) : TurnAction
	data class AskProphet(val about: CardType) : TurnAction
	data class ChangeColor(val color: CardColor) : TurnAction

	val isFree: Boolean get() = this is CallUno || this is Accuse || this is AskProphet || this is ChangeColor
}

/**
 * Makes a player's decisions. Implemented by the AI ([ComputerController])
 * and by the touch UI ([HumanController]).
 */
interface PlayerController {
	/** Your turn: play, draw, invade, or a free action. */
	suspend fun takeTurn(game: Game, me: Player): TurnAction
	/** [attack] is aimed at you: play one of [Game.answers] or [TurnAction.Pass] to take it. */
	suspend fun answer(game: Game, me: Player, attack: Attack): TurnAction
	suspend fun chooseColor(game: Game, me: Player): CardColor
	/** Null only when [optional]. */
	suspend fun chooseVictim(game: Game, me: Player, reason: Reason, options: List<Seat>, optional: Boolean = false): Seat?
	/** Null only when [optional]. */
	suspend fun chooseCard(game: Game, me: Player, reason: Reason, options: List<Card>, optional: Boolean = false): Card?
	/**
	 * A computer's jump-in decision: after how many ms it jumps in with [card], or null for never.
	 * People jump in by tapping the card while the window is open.
	 */
	fun jumpInDelay(game: Game, me: Player, card: Card, windowMs: Long): Long?
}

/** Seat, hand and table bookkeeping for one player. */
class Player(val seat: Seat, val controller: PlayerController) {

	var hand: Hand = Hand(false)
		private set
	var status: Status = Status.ACTIVE
	val active: Boolean get() = status == Status.ACTIVE

	/** Cards in play in front of this player (field cards, Death's clock, armies, War card). */
	val field = ArrayList<Card>()
	/** Turns left before Death removes this player; -1 when Death is not on them. */
	var deathClock: Int = -1
	/** The card turned up for Death's clock. */
	var clockCard: Card? = null
	/** Armies captured by this player's Conquest (also on [field]). */
	val armies = ArrayList<Card>()
	/** The card turned up for War: this player may not play its color. */
	var warCard: Card? = null
	/** Players whose army this player's Conquest has captured. */
	val conquered = HashSet<Seat>()
	/** Yin/Yang partner (the one who dropped out, or the one who kept the hand). */
	var teammate: Player? = null

	var calledUno: Boolean = false
	/** Played their last card without having called UNO: they cannot win with it. */
	var emptiedWithoutUno: Boolean = false
	/** Cards in other hands this player has seen (Prophet, Gift). */
	val peeked = HashSet<Card>()
	/** The card drawn this turn (Apocalypse rules let you play it). */
	var drawnThisTurn: Card? = null
	var askedProphetThisTurn: Boolean = false

	/** Rounds won (normal mode) and lost (elimination mode). */
	var wins: Int = 0
	var losses: Int = 0

	/** A person plays this seat (and it has not been handed over to a computer). */
	val isHuman: Boolean get() = (controller as? HumanController)?.let { it.autopilot == null } ?: false

	fun hasField(s: Special): Boolean = field.any { it.special == s }
	fun fieldCard(s: Special): Card? = field.firstOrNull { it.special == s }
	fun fieldCount(s: Special): Int = field.count { it.special == s }

	val virusCount: Int get() = fieldCount(Special.VIRUS)
	val luckCount: Int get() = this.field.count { it.special?.isLucky == true }
	val hasYinAndYang: Boolean get() = hasField(Special.YIN) && hasField(Special.YANG)

	/** The War ban, if any. */
	val bannedColor: CardColor? get() = warCard?.color?.takeIf { it != CardColor.WILD }

	fun resetRound(faceUp: Boolean) {
		hand = Hand(faceUp).also { it.keepSorted = controller is HumanController }
		status = Status.ACTIVE
		field.clear()
		deathClock = -1
		clockCard = null
		armies.clear()
		emptiedWithoutUno = false
		warCard = null
		conquered.clear()
		teammate = null
		calledUno = false
		peeked.clear()
		drawnThisTurn = null
		askedProphetThisTurn = false
	}

	fun resetGame(faceUp: Boolean) {
		wins = 0
		losses = 0
		resetRound(faceUp)
	}

	internal fun replaceHand(h: Hand) {
		hand = h
	}

	override fun toString(): String = "Player($seat)"
}
