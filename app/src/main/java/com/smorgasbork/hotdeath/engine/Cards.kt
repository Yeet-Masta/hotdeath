package com.smorgasbork.hotdeath.engine

import kotlin.math.min
import kotlin.random.Random

/** One physical card: its position in the deck plus its (immutable) type. */
class Card(val deckIndex: Int, val type: CardType) {
	val color: CardColor get() = type.color
	val value: CardValue get() = type.value
	val points: Int get() = type.points

	override fun toString(): String = "$type#$deckIndex"
}

enum class DeckType(
	val key: String,
	private val baseDeckCount: Int,
	private val modifyDecks: Boolean,
	private val addModifiedCards: Boolean,
	private val addExtendedCards: Boolean,
) {
	STANDARD("standard", 2, true, false, false),
	HALF("half", 1, true, false, false),
	HYBRID("hybrid", 1, false, true, false),
	EXTENDED("extended", 2, true, false, true),
	VANILLA("vanilla", 1, false, false, false);

	/** Vanilla rules (classic 7-card deal, no Hot Death specials). */
	val standardRules: Boolean get() = this == VANILLA

	/** Builds the ordered list of card types for this deck. */
	fun buildTypes(): List<CardType> {
		val types = ArrayList<CardType>()
		addVanillaCards(types, baseDeckCount)
		if (modifyDecks) {
			swapInHotDeathCards(types, baseDeckCount)
			if (addExtendedCards) types.addAll(EXTENDED_CARDS)
		} else if (addModifiedCards) {
			for (row in HD_CARDS) repeat(row.maxCount) { types.add(row.newType) }
		}
		// Same order as the original deck: color, then value, then card ID.
		types.sortWith(compareBy({ it.color.id }, { it.value.legacyValue }, { it.legacyId }))
		return types
	}

	companion object {
		fun fromKey(key: String?): DeckType = entries.firstOrNull { it.key == key } ?: STANDARD

		private class HdRow(val replaces: CardType, val maxCount: Int, val newType: CardType)

		/** Each row: the vanilla card to replace, how many copies at most, and the Hot Death card. */
		private val HD_CARDS = listOf(
			HdRow(CardType.RED_0, 1, CardType.RED_0_HD),
			HdRow(CardType.RED_2, 1, CardType.RED_2_GLASNOST),
			HdRow(CardType.RED_5, 1, CardType.RED_5_MAGIC),
			HdRow(CardType.RED_D, 2, CardType.RED_D_SPREADER),
			HdRow(CardType.RED_S, 2, CardType.RED_S_DOUBLE),
			HdRow(CardType.RED_R, 2, CardType.RED_R_SKIP),
			HdRow(CardType.GREEN_0, 1, CardType.GREEN_0_QUITTER),
			HdRow(CardType.GREEN_3, 1, CardType.GREEN_3_AIDS),
			HdRow(CardType.GREEN_4, 1, CardType.GREEN_4_IRISH),
			HdRow(CardType.GREEN_D, 2, CardType.GREEN_D_SPREADER),
			HdRow(CardType.GREEN_S, 2, CardType.GREEN_S_DOUBLE),
			HdRow(CardType.GREEN_R, 2, CardType.GREEN_R_SKIP),
			HdRow(CardType.BLUE_0, 1, CardType.BLUE_0_FUCK_YOU),
			HdRow(CardType.BLUE_2, 1, CardType.BLUE_2_SHIELD),
			HdRow(CardType.BLUE_D, 2, CardType.BLUE_D_SPREADER),
			HdRow(CardType.BLUE_S, 2, CardType.BLUE_S_DOUBLE),
			HdRow(CardType.BLUE_R, 2, CardType.BLUE_R_SKIP),
			HdRow(CardType.YELLOW_0, 1, CardType.YELLOW_0_SHITTER),
			HdRow(CardType.YELLOW_1, 1, CardType.YELLOW_1_MAD),
			HdRow(CardType.YELLOW_6, 1, CardType.YELLOW_69),
			HdRow(CardType.YELLOW_D, 2, CardType.YELLOW_D_SPREADER),
			HdRow(CardType.YELLOW_S, 2, CardType.YELLOW_S_DOUBLE),
			HdRow(CardType.YELLOW_R, 2, CardType.YELLOW_R_SKIP),
			HdRow(CardType.WILD, 1, CardType.WILD_DB),
			HdRow(CardType.WILD, 1, CardType.WILD_MYSTERY),
			HdRow(CardType.WILD, 1, CardType.WILD_HD),
			HdRow(CardType.WILD, 1, CardType.WILD_HOS),
		)

		/** Extra v3 cards added on top of a two-deck Hot Death deck. */
		private val EXTENDED_CARDS = listOf(
			CardType.RED_R_BACKSTAB, CardType.GREEN_R_BACKSTAB,
			CardType.BLUE_R_BACKSTAB, CardType.YELLOW_R_BACKSTAB,
			CardType.GREEN_2_CLONE, CardType.YELLOW_2_CLONE,
			CardType.BLUE_1_PING,
			CardType.GREEN_R_SWAP, CardType.YELLOW_R_SWAP,
		)

		private val VANILLA_VALUES = CardValue.NUMBERS + listOf(CardValue.DRAW, CardValue.SKIP, CardValue.REVERSE)

		private fun addVanillaCards(types: MutableList<CardType>, copies: Int) {
			for (color in CardColor.SUITS) {
				for (value in VANILLA_VALUES) {
					// 0s appear once per deck copy; 1-9 and actions twice per copy
					val count = if (value == CardValue.ZERO) copies else copies * 2
					repeat(count) { types.add(CardType.standard(color, value)) }
				}
			}
			repeat(4 * copies) { types.add(CardType.WILD) }
			repeat(4 * copies) { types.add(CardType.WILD_DRAW_FOUR) }
		}

		private fun swapInHotDeathCards(types: MutableList<CardType>, copies: Int) {
			for (row in HD_CARDS) {
				val allowed = min(row.maxCount, copies)
				var replaced = 0
				// bottom to top, replacing up to `allowed` occurrences
				var i = types.size - 1
				while (i >= 0 && replaced < allowed) {
					if (types[i] == row.replaces) {
						types[i] = row.newType
						replaced++
					}
					i--
				}
			}
		}
	}
}

/** The full set of cards for one game. */
class CardDeck(val deckType: DeckType) {
	val cards: List<Card> = deckType.buildTypes().mapIndexed { i, t -> Card(i, t) }

	val size: Int get() = cards.size

	operator fun get(deckIndex: Int): Card? = cards.getOrNull(deckIndex)

	/** Distinct card types present in this deck. */
	val types: Set<CardType> get() = cards.mapTo(LinkedHashSet()) { it.type }

	fun firstOfType(type: CardType): Card? = cards.firstOrNull { it.type == type }
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

	/** Removes and returns the top card, or null if empty. */
	fun draw(): Card? = if (cards.isEmpty()) null else cards.removeAt(cards.size - 1)

	/** Removes `c`, returning its former index or -1. */
	fun remove(c: Card): Int {
		val idx = cards.indexOf(c)
		if (idx != -1) cards.removeAt(idx)
		return idx
	}

	fun insert(c: Card, idx: Int) {
		cards.add(idx, c)
	}

	fun shuffle(random: Random) {
		cards.shuffle(random)
	}
}
