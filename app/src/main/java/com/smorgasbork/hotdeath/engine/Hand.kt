package com.smorgasbork.hotdeath.engine

import kotlin.random.Random

/** Result of scoring a hand. */
class HandValue(
	val total: Int,
	/** What each card contributes to [total] (used for display and by the AI). */
	val cardValues: Map<Card, Int>,
	/** Number of AIDS cards held (each adds a virus penalty at round end). */
	val aidsCount: Int,
) {
	fun valueOf(c: Card): Int = cardValues[c] ?: 0
}

/**
 * A player's hand. "Table" cards were forced face-up onto the table
 * (Glasnost); "held" cards are the rest.
 *
 * @param faceUp whether held cards are visible (human player, face-up option,
 *               or revealed at round end).
 */
class Hand(faceUp: Boolean) {

	var faceUp: Boolean = faceUp
		private set

	/** Keep held cards in order even while face-down (a person's own hand). */
	var keepSorted: Boolean = false

	private val table = ArrayList<Card>()
	private val held = ArrayList<Card>()
	/** Individual held cards shown face-up (Luck of the Irish, Blue Shield). */
	private val revealed = HashSet<Card>()

	val tableCards: List<Card> get() = table
	val heldCards: List<Card> get() = held
	val revealedCards: Set<Card> get() = revealed

	/** All cards: table cards first, then held cards. */
	val cards: List<Card> get() = table + held

	val size: Int get() = table.size + held.size
	val isEmpty: Boolean get() = size == 0

	operator fun contains(c: Card): Boolean = c in held || c in table

	fun isVisible(c: Card): Boolean = faceUp || c in table || c in revealed

	// ── mutation ──────────────────────────────────────────────────────────────

	fun add(c: Card) {
		held.add(c)
		if (faceUp || keepSorted) sort()
	}

	fun remove(c: Card): Boolean {
		revealed.remove(c)
		return table.remove(c) || held.remove(c)
	}

	fun clear() {
		table.clear()
		held.clear()
		revealed.clear()
	}

	/** Shows a single card face-up. */
	fun reveal(c: Card) {
		if (c in this) revealed.add(c)
	}

	/** Turns the whole hand face-up; returns the cards that were hidden until now. */
	fun revealAll(): List<Card> {
		if (faceUp) return emptyList()
		faceUp = true
		sort()
		return held.filter { it !in revealed }
	}

	/** Moves every held card onto the table (Glasnost); returns the moved cards. */
	fun moveAllToTable(): List<Card> {
		sort()
		val moved = held.toList()
		table.addAll(moved)
		held.clear()
		sort()
		return moved
	}

	/** Replaces a random card with `newCard`; returns the evicted card. */
	fun swapRandom(newCard: Card, random: Random): Card {
		val evicted = cards[random.nextInt(size)]
		remove(evicted)
		add(newCard)
		return evicted
	}

	/** Restores saved contents. */
	fun restore(tableCards: List<Card>, heldCards: List<Card>, revealedCards: Collection<Card>) {
		clear()
		table.addAll(tableCards)
		held.addAll(heldCards)
		revealed.addAll(revealedCards.filter { it in this })
		if (faceUp) sort()
	}

	fun sort() {
		table.sortBy { it.deckIndex }
		held.sortBy { it.deckIndex }
	}

	// ── queries ───────────────────────────────────────────────────────────────

	fun has(type: CardType): Boolean = cards.any { it.type == type }

	fun hasColorMatch(color: CardColor?): Boolean = cards.any { it.color == color }

	fun countSuit(color: CardColor?): Int = cards.count { it.color == color }

	fun hasAllBastardCards(): Boolean = cards.map { it.type }.filter { it.isBastard }.toSet().size == 4

	// ── scoring ───────────────────────────────────────────────────────────────

	/**
	 * Calculates the point value of this hand according to the Hot Death rules.
	 *
	 * @param withoutCard if non-null, pretend this card is not in the hand (AI look-ahead).
	 */
	fun calculateValue(withoutCard: Card? = null): HandValue {
		val all = cards.filter { it !== withoutCard }
		val values = HashMap<Card, Int>()
		if (all.isEmpty()) return HandValue(0, values, 0)

		// Step 1: locate special cards
		val cFuckYou = all.lastOrNull { it.type == CardType.BLUE_0_FUCK_YOU }
		val cShitter = all.lastOrNull { it.type == CardType.YELLOW_0_SHITTER }
		val cQuitter = all.lastOrNull { it.type == CardType.GREEN_0_QUITTER }
		var cMystery: Card? = null
		var cBlueShield: Card? = null
		var cSixtyNine: Card? = null
		var cMagic5: Card? = null
		var cHolyDefender: Card? = null
		var aids = 0

		// Step 2: Full-Monty check (F.U. + Quitter = 1000)
		val fullMonty = cFuckYou != null && cQuitter != null
		var total = 0
		if (fullMonty) {
			total = 1000
			values[cFuckYou!!] = 500
			values[cQuitter!!] = 500
		} else if (cShitter != null) {
			values[cShitter] = 150
		}

		// Step 3: sum fixed-value cards
		var highest = -5
		var highestNum = -5
		for (c in all) {
			val t = c.type
			if (fullMonty && (t == CardType.BLUE_0_FUCK_YOU || t == CardType.YELLOW_0_SHITTER
						|| t == CardType.GREEN_0_QUITTER)) continue

			if (t == CardType.GREEN_3_AIDS) aids++

			// defer these for later steps
			when (t) {
				CardType.WILD_MYSTERY -> { cMystery = c; continue }
				CardType.BLUE_2_SHIELD -> { cBlueShield = c; continue }
				CardType.YELLOW_69 -> { cSixtyNine = c; continue }
				CardType.RED_0_HD -> { cHolyDefender = c; continue }
				CardType.BLUE_0_FUCK_YOU, CardType.YELLOW_0_SHITTER -> continue
				else -> {}
			}

			if (t == CardType.RED_5_MAGIC) cMagic5 = c

			val pv = c.points
			if (pv > highest) highest = pv
			if (pv in 1..9 && pv > highestNum) highestNum = pv
			values[c] = pv
			total += pv
		}

		// Step 4: Mystery Wild = 10× highest numeric card
		cMystery?.let {
			val pv = if (highestNum > 0) 10 * highestNum else 10
			if (pv > highest) highest = pv
			values[it] = pv
			total += pv
		}

		// Step 5: Blue Shield mirrors the highest card value
		cBlueShield?.let {
			values[it] = highest
			total += highest
		}

		// Step 6: 69 card pegs total at 69 (Magic 5 still subtracts)
		cSixtyNine?.let {
			values[it] = 69 - total
			total = 69
			cMagic5?.let { m ->
				values[m] = -5
				total -= 5
			}
		}

		// Step 7: F.U. doubles the total
		if (!fullMonty && cFuckYou != null) {
			values[cFuckYou] = total
			total *= 2
		}

		// Step 8: Holy Defender halves the total (rounded up)
		cHolyDefender?.let {
			val halved = (total + 1) / 2
			values[it] = halved - total
			total = halved
		}

		return HandValue(total, values, aids)
	}

	/**
	 * Mid-round estimate: the Shitter makes a hand worth at least 150.
	 * (At round end the Shitter instead inherits the table's highest score.)
	 */
	fun estimateValue(withoutCard: Card? = null): HandValue {
		val v = calculateValue(withoutCard)
		val shitter = cards.firstOrNull { it.type == CardType.YELLOW_0_SHITTER && it !== withoutCard }
		if (shitter != null && v.total < v.valueOf(shitter)) {
			return HandValue(v.valueOf(shitter), v.cardValues, v.aidsCount)
		}
		return v
	}
}
