package com.smorgasbork.hotdeath.engine

import kotlin.random.Random

/**
 * The cards a player holds. Cards in front of a player (field cards) are
 * kept on [Player.field], not here.
 *
 * @param faceUp whether every card is visible (face-up option, or revealed at round end).
 */
class Hand(faceUp: Boolean) {

	var faceUp: Boolean = faceUp
		private set

	/** Keep cards in order even while face-down (a person's own hand). */
	var keepSorted: Boolean = false

	private val held = ArrayList<Card>()
	/** Cards shown to everyone (Glasnost, a shield coming into play...). */
	private val revealed = HashSet<Card>()

	val cards: List<Card> get() = held
	val revealedCards: Set<Card> get() = revealed

	val size: Int get() = held.size
	val isEmpty: Boolean get() = held.isEmpty()

	operator fun contains(c: Card): Boolean = c in held

	fun isVisible(c: Card): Boolean = faceUp || c in revealed

	fun add(c: Card) {
		held.add(c)
		if (faceUp || keepSorted) sort()
	}

	fun remove(c: Card): Boolean {
		revealed.remove(c)
		return held.remove(c)
	}

	/** Removes every card, returning them. */
	fun takeAll(): List<Card> = held.toList().also { clear() }

	fun clear() {
		held.clear()
		revealed.clear()
	}

	fun reveal(c: Card) {
		if (c in held) revealed.add(c)
	}

	/** Shows every card currently held (later cards stay hidden); returns the newly shown ones. */
	fun revealCurrent(): List<Card> = held.filter { revealed.add(it) && !faceUp }

	/** Turns the whole hand face-up for good; returns the cards that were hidden until now. */
	fun revealAll(): List<Card> {
		if (faceUp) return emptyList()
		faceUp = true
		sort()
		return held.filter { it !in revealed }
	}

	fun random(random: Random): Card? = if (held.isEmpty()) null else held[random.nextInt(held.size)]

	/** Restores saved contents. */
	fun restore(heldCards: List<Card>, revealedCards: Collection<Card>) {
		clear()
		held.addAll(heldCards)
		revealed.addAll(revealedCards.filter { it in held })
		if (faceUp || keepSorted) sort()
	}

	fun sort() {
		held.sortBy { it.deckIndex }
	}

	fun has(s: Special): Boolean = held.any { it.special == s }

	fun first(s: Special): Card? = held.firstOrNull { it.special == s }

	fun countColor(color: CardColor?): Int = held.count { it.color == color }
}
