package com.smorgasbork.hotdeath.engine

import kotlin.random.Random

/** One physical card: its position in the deck plus its (immutable) type. */
class Card(val deckIndex: Int, val type: CardType) {
	val color: CardColor get() = type.color
	val face: Face get() = type.face
	val special: Special? get() = type.special

	override fun toString(): String = "${type.key}#$deckIndex"
}

/**
 * The deck: four standard Uno decks, with the Constitution's new cards
 * replacing the standard cards they are printed over. Every game uses the
 * same deck, so deck indices are stable across saves and devices.
 */
class CardDeck private constructor() {
	val cards: List<Card> = buildTypes().mapIndexed { i, t -> Card(i, t) }

	val size: Int get() = cards.size

	operator fun get(deckIndex: Int): Card? = cards.getOrNull(deckIndex)

	/** Distinct card types, in deck order. */
	val types: List<CardType> get() = cards.map { it.type }.distinct()

	fun firstOf(s: Special): Card? = cards.firstOrNull { it.special == s }

	companion object {
		const val STANDARD_DECKS = 4

		/** The one deck every game uses. */
		val STANDARD: CardDeck by lazy { CardDeck() }

		private fun buildTypes(): List<CardType> {
			val types = ArrayList<CardType>()
			repeat(STANDARD_DECKS) {
				for (c in CardColor.SUITS) {
					for (f in Face.COLORED) {
						// one 0 per color per deck; two of everything else
						repeat(if (f == Face.ZERO) 1 else 2) { types += CardType.plain(c, f) }
					}
				}
				repeat(4) { types += CardType.plain(CardColor.WILD, Face.WILD) }
				repeat(4) { types += CardType.plain(CardColor.WILD, Face.WILD_DRAW_FOUR) }
			}
			for (s in Special.entries) {
				for (color in s.replaces) {
					val plain = CardType.plain(color, s.face)
					val i = types.indexOf(plain)
					check(i >= 0) { "not enough ${plain.key} to make $s" }
					types[i] = CardType.special(s, color)
				}
			}
			// Sort like a hand: by color, then face, plain cards before specials.
			val order = CardType.ALL.withIndex().associate { it.value to it.index }
			types.sortWith(compareBy({ it.color.id }, { it.face.ordinal }, { order[it] }))
			return types
		}
	}
}

/** An ordered stack of cards (draw pile or discard pile). Index 0 is the bottom. */
class CardPile {
	private val cards = ArrayList<Card>()

	val size: Int get() = cards.size
	val isEmpty: Boolean get() = cards.isEmpty()
	val top: Card? get() = cards.lastOrNull()
	val asList: List<Card> get() = cards

	operator fun get(i: Int): Card? = cards.getOrNull(i)
	operator fun contains(c: Card): Boolean = cards.contains(c)

	fun add(c: Card) {
		cards.add(c)
	}

	fun addAll(cs: Collection<Card>) {
		cards.addAll(cs)
	}

	/** Puts a card at the bottom. */
	fun addBottom(c: Card) {
		cards.add(0, c)
	}

	/** Removes and returns the top card, or null if empty. */
	fun draw(): Card? = if (cards.isEmpty()) null else cards.removeAt(cards.size - 1)

	fun remove(c: Card): Boolean = cards.remove(c)

	/** Removes everything, returning the cards bottom to top. */
	fun takeAll(): List<Card> = cards.toList().also { cards.clear() }

	fun clear() = cards.clear()

	fun shuffle(random: Random) {
		cards.shuffle(random)
	}
}
