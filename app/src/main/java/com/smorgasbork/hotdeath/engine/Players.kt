package com.smorgasbork.hotdeath.engine

/** A pending attack waiting to be assessed against its victim(s). */
class Penalty {
	var type: PenaltyType = PenaltyType.NONE
	var origCard: Card? = null
		private set
	var numCards: Int = 0
		private set
	var hasHotDeath: Boolean = false
		private set
	var generatingPlayer: Player? = null
	var victim: Player? = null
	var secondaryVictim: Player? = null

	val isActive: Boolean get() = type != PenaltyType.NONE

	fun reset() {
		origCard = null
		numCards = 0
		type = PenaltyType.NONE
		hasHotDeath = false
		generatingPlayer = null
		victim = null
		secondaryVictim = null
	}

	fun addCards(c: Card?, n: Int, generator: Player?, victim: Player?) {
		if (c != null) {
			origCard = c
			if (c.type == CardType.WILD_HD) hasHotDeath = true
		}
		numCards += n
		type = PenaltyType.CARD
		generatingPlayer = generator
		this.victim = victim
	}

	fun removeHotDeath() {
		numCards -= 8
		hasHotDeath = false
	}

	fun setEject(c: Card?, generator: Player?, victim: Player?) {
		if (c != null) origCard = c
		type = PenaltyType.EJECT
		generatingPlayer = generator
		this.victim = victim
	}

	fun setFaceup(c: Card?, generator: Player?, victim: Player?) {
		if (c != null) origCard = c
		type = PenaltyType.FACEUP
		generatingPlayer = generator
		this.victim = victim
	}

	/** Restores saved state. */
	fun restore(type: PenaltyType, numCards: Int, hasHotDeath: Boolean, origCard: Card?,
				generator: Player?, victim: Player?, secondaryVictim: Player?) {
		this.type = type
		this.numCards = numCards
		this.hasHotDeath = hasHotDeath
		this.origCard = origCard
		this.generatingPlayer = generator
		this.victim = victim
		this.secondaryVictim = secondaryVictim
	}
}

/** What a player decided to do on their turn. */
sealed interface TurnAction {
	data class Play(val card: Card) : TurnAction
	data object Draw : TurnAction
	data object Pass : TurnAction
}

/** Why a victim is being chosen; lets the AI target Ping differently. */
enum class VictimReason { GLASNOST, MAD, PING }

/**
 * Makes a player's decisions. Implemented by the AI ([ComputerController])
 * and by the touch UI ([HumanController]).
 */
interface PlayerController {
	suspend fun chooseNumCardsToDeal(game: Game, me: Player): Int
	suspend fun takeTurn(game: Game, me: Player): TurnAction
	suspend fun chooseColor(game: Game, me: Player): CardColor
	suspend fun chooseVictim(game: Game, me: Player, reason: VictimReason): Seat
}

/** Seat, hand and score bookkeeping for one player. */
class Player(val seat: Seat, val controller: PlayerController) {

	var hand: Hand = Hand(false)
		private set
	var active: Boolean = true

	var hasTriedDrawing: Boolean = false
	var lastDrawn: Card? = null

	var lastScore: Int = 0
	var lastVirusPenalty: Int = 0
	var totalScore: Int = 0
	var virusPenalty: Int = 0

	/** A person plays this seat (and it has not been handed over to a computer). */
	val isHuman: Boolean get() = (controller as? HumanController)?.let { it.autopilot == null } ?: false

	fun resetRound(faceUp: Boolean) {
		hand = Hand(faceUp).also { it.keepSorted = controller is HumanController }
		active = true
	}

	fun resetGame(faceUp: Boolean) {
		lastScore = 0
		totalScore = 0
		virusPenalty = 0
		resetRound(faceUp)
	}

	fun resetLastDrawn() {
		hasTriedDrawing = false
		lastDrawn = null
	}

	internal fun replaceHand(h: Hand) {
		hand = h
	}

	override fun toString(): String = "Player($seat)"
}
