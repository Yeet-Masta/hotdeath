package com.smorgasbork.hotdeath.engine

/**
 * Core value types for the Hot Death rules engine.
 *
 * Everything in the `engine` package is plain Kotlin with no Android
 * dependencies, so the rules can be exercised by local JVM unit tests.
 */

enum class CardColor(val id: Int) {
	RED(1), GREEN(2), BLUE(3), YELLOW(4), WILD(5);

	companion object {
		/** The four playable suits, in their traditional order. */
		val SUITS = listOf(RED, GREEN, BLUE, YELLOW)

		fun fromId(id: Int): CardColor? = entries.firstOrNull { it.id == id }
	}
}

/**
 * Face value of a card.
 *
 * [number] is the numeric face for 0–9 cards (used by the 69 card, the
 * Mystery Draw and the AI), null for action cards.
 * [legacyValue] is the integer the original Java code used; it fixes the deck
 * sort order so deck indices in saved games stay valid.
 */
enum class CardValue(val number: Int?, val legacyValue: Int) {
	ZERO(0, 0), ONE(1, 1), TWO(2, 2), THREE(3, 3), FOUR(4, 4),
	FIVE(5, 5), SIX(6, 6), SEVEN(7, 7), EIGHT(8, 8), NINE(9, 9),
	DRAW(null, 11),
	DRAW_SPREAD(null, 12),
	SKIP(null, 13),
	SKIP_DOUBLE(null, 14),
	REVERSE(null, 15),
	REVERSE_SKIP(null, 16),
	REVERSE_BACKSTAB(null, 17),
	REVERSE_SWAP(null, 18),
	WILD(null, 20),
	WILD_DRAW(null, 21),
	CLONE(null, 30);

	val isDrawFamily: Boolean get() = this == DRAW || this == DRAW_SPREAD
	val isSkipFamily: Boolean get() = this == SKIP || this == SKIP_DOUBLE
	val isReverseFamily: Boolean
		get() = this == REVERSE || this == REVERSE_SKIP || this == REVERSE_BACKSTAB || this == REVERSE_SWAP

	companion object {
		val NUMBERS = listOf(ZERO, ONE, TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE)
	}
}

/**
 * Every distinct card in the game. Color, value and point value are fixed per
 * type, so a [Card] is just a deck position plus a type.
 *
 * [legacyId] is the original Java card-ID constant; with [CardValue.legacyValue]
 * it reproduces the original deck order.
 */
enum class CardType(val color: CardColor, val value: CardValue, val points: Int, val legacyId: Int) {
	RED_0(CardColor.RED, CardValue.ZERO, 0, 100),
	RED_1(CardColor.RED, CardValue.ONE, 1, 101),
	RED_2(CardColor.RED, CardValue.TWO, 2, 102),
	RED_3(CardColor.RED, CardValue.THREE, 3, 103),
	RED_4(CardColor.RED, CardValue.FOUR, 4, 104),
	RED_5(CardColor.RED, CardValue.FIVE, 5, 105),
	RED_6(CardColor.RED, CardValue.SIX, 6, 106),
	RED_7(CardColor.RED, CardValue.SEVEN, 7, 107),
	RED_8(CardColor.RED, CardValue.EIGHT, 8, 108),
	RED_9(CardColor.RED, CardValue.NINE, 9, 109),
	RED_D(CardColor.RED, CardValue.DRAW, 20, 110),
	RED_S(CardColor.RED, CardValue.SKIP, 20, 111),
	RED_R(CardColor.RED, CardValue.REVERSE, 20, 112),

	GREEN_0(CardColor.GREEN, CardValue.ZERO, 0, 113),
	GREEN_1(CardColor.GREEN, CardValue.ONE, 1, 114),
	GREEN_2(CardColor.GREEN, CardValue.TWO, 2, 115),
	GREEN_3(CardColor.GREEN, CardValue.THREE, 3, 116),
	GREEN_4(CardColor.GREEN, CardValue.FOUR, 4, 117),
	GREEN_5(CardColor.GREEN, CardValue.FIVE, 5, 118),
	GREEN_6(CardColor.GREEN, CardValue.SIX, 6, 119),
	GREEN_7(CardColor.GREEN, CardValue.SEVEN, 7, 120),
	GREEN_8(CardColor.GREEN, CardValue.EIGHT, 8, 121),
	GREEN_9(CardColor.GREEN, CardValue.NINE, 9, 122),
	GREEN_D(CardColor.GREEN, CardValue.DRAW, 20, 123),
	GREEN_S(CardColor.GREEN, CardValue.SKIP, 20, 124),
	GREEN_R(CardColor.GREEN, CardValue.REVERSE, 20, 125),

	BLUE_0(CardColor.BLUE, CardValue.ZERO, 0, 126),
	BLUE_1(CardColor.BLUE, CardValue.ONE, 1, 127),
	BLUE_2(CardColor.BLUE, CardValue.TWO, 2, 128),
	BLUE_3(CardColor.BLUE, CardValue.THREE, 3, 129),
	BLUE_4(CardColor.BLUE, CardValue.FOUR, 4, 130),
	BLUE_5(CardColor.BLUE, CardValue.FIVE, 5, 131),
	BLUE_6(CardColor.BLUE, CardValue.SIX, 6, 132),
	BLUE_7(CardColor.BLUE, CardValue.SEVEN, 7, 133),
	BLUE_8(CardColor.BLUE, CardValue.EIGHT, 8, 134),
	BLUE_9(CardColor.BLUE, CardValue.NINE, 9, 135),
	BLUE_D(CardColor.BLUE, CardValue.DRAW, 20, 136),
	BLUE_S(CardColor.BLUE, CardValue.SKIP, 20, 137),
	BLUE_R(CardColor.BLUE, CardValue.REVERSE, 20, 138),

	YELLOW_0(CardColor.YELLOW, CardValue.ZERO, 0, 139),
	YELLOW_1(CardColor.YELLOW, CardValue.ONE, 1, 140),
	YELLOW_2(CardColor.YELLOW, CardValue.TWO, 2, 141),
	YELLOW_3(CardColor.YELLOW, CardValue.THREE, 3, 142),
	YELLOW_4(CardColor.YELLOW, CardValue.FOUR, 4, 143),
	YELLOW_5(CardColor.YELLOW, CardValue.FIVE, 5, 144),
	YELLOW_6(CardColor.YELLOW, CardValue.SIX, 6, 145),
	YELLOW_7(CardColor.YELLOW, CardValue.SEVEN, 7, 146),
	YELLOW_8(CardColor.YELLOW, CardValue.EIGHT, 8, 147),
	YELLOW_9(CardColor.YELLOW, CardValue.NINE, 9, 148),
	YELLOW_D(CardColor.YELLOW, CardValue.DRAW, 20, 149),
	YELLOW_S(CardColor.YELLOW, CardValue.SKIP, 20, 150),
	YELLOW_R(CardColor.YELLOW, CardValue.REVERSE, 20, 151),

	WILD(CardColor.WILD, CardValue.WILD, 50, 152),
	WILD_DRAW_FOUR(CardColor.WILD, CardValue.WILD_DRAW, 50, 153),
	WILD_HOS(CardColor.WILD, CardValue.WILD_DRAW, 0, 154),
	WILD_HD(CardColor.WILD, CardValue.WILD_DRAW, 100, 155),
	WILD_MYSTERY(CardColor.WILD, CardValue.WILD_DRAW, 0, 156),
	WILD_DB(CardColor.WILD, CardValue.WILD_DRAW, 100, 157),

	RED_0_HD(CardColor.RED, CardValue.ZERO, 0, 158),
	RED_2_GLASNOST(CardColor.RED, CardValue.TWO, 75, 159),
	RED_5_MAGIC(CardColor.RED, CardValue.FIVE, -5, 160),
	RED_D_SPREADER(CardColor.RED, CardValue.DRAW_SPREAD, 60, 161),
	RED_S_DOUBLE(CardColor.RED, CardValue.SKIP_DOUBLE, 40, 162),
	RED_R_SKIP(CardColor.RED, CardValue.REVERSE_SKIP, 40, 163),

	GREEN_0_QUITTER(CardColor.GREEN, CardValue.ZERO, 100, 164),
	GREEN_3_AIDS(CardColor.GREEN, CardValue.THREE, 3, 165),
	GREEN_4_IRISH(CardColor.GREEN, CardValue.FOUR, 75, 166),
	GREEN_D_SPREADER(CardColor.GREEN, CardValue.DRAW_SPREAD, 60, 167),
	GREEN_S_DOUBLE(CardColor.GREEN, CardValue.SKIP_DOUBLE, 40, 168),
	GREEN_R_SKIP(CardColor.GREEN, CardValue.REVERSE_SKIP, 40, 169),

	BLUE_0_FUCK_YOU(CardColor.BLUE, CardValue.ZERO, 0, 170),
	BLUE_2_SHIELD(CardColor.BLUE, CardValue.TWO, 0, 171),
	BLUE_D_SPREADER(CardColor.BLUE, CardValue.DRAW_SPREAD, 60, 172),
	BLUE_S_DOUBLE(CardColor.BLUE, CardValue.SKIP_DOUBLE, 40, 173),
	BLUE_R_SKIP(CardColor.BLUE, CardValue.REVERSE_SKIP, 40, 174),

	YELLOW_0_SHITTER(CardColor.YELLOW, CardValue.ZERO, 0, 175),
	YELLOW_1_MAD(CardColor.YELLOW, CardValue.ONE, 100, 176),
	YELLOW_69(CardColor.YELLOW, CardValue.SIX, 69, 177),
	YELLOW_D_SPREADER(CardColor.YELLOW, CardValue.DRAW_SPREAD, 60, 178),
	YELLOW_S_DOUBLE(CardColor.YELLOW, CardValue.SKIP_DOUBLE, 40, 179),
	YELLOW_R_SKIP(CardColor.YELLOW, CardValue.REVERSE_SKIP, 40, 180),

	// v3 cards
	RED_R_BACKSTAB(CardColor.RED, CardValue.REVERSE_BACKSTAB, 20, 181),
	GREEN_R_BACKSTAB(CardColor.GREEN, CardValue.REVERSE_BACKSTAB, 20, 182),
	BLUE_R_BACKSTAB(CardColor.BLUE, CardValue.REVERSE_BACKSTAB, 20, 183),
	YELLOW_R_BACKSTAB(CardColor.YELLOW, CardValue.REVERSE_BACKSTAB, 20, 184),
	GREEN_2_CLONE(CardColor.GREEN, CardValue.CLONE, 20, 189),
	YELLOW_2_CLONE(CardColor.YELLOW, CardValue.CLONE, 20, 190),
	BLUE_1_PING(CardColor.BLUE, CardValue.ONE, 1, 191),
	GREEN_R_SWAP(CardColor.GREEN, CardValue.REVERSE_SWAP, 20, 192),
	YELLOW_R_SWAP(CardColor.YELLOW, CardValue.REVERSE_SWAP, 20, 193);

	/** The four "bastard" zeros; holding all four ends the round. */
	val isBastard: Boolean
		get() = this == RED_0_HD || this == GREEN_0_QUITTER || this == BLUE_0_FUCK_YOU || this == YELLOW_0_SHITTER

	companion object {
		/** The standard (vanilla) card for a suit and value. */
		fun standard(color: CardColor, value: CardValue): CardType =
			entries.first { it.color == color && it.value == value && it.legacyId < 152 }
	}
}

enum class Seat(val number: Int) {
	SOUTH(1), WEST(2), NORTH(3), EAST(4);

	/** 0-based position in the player list. */
	val index: Int get() = number - 1

	companion object {
		/** The three computer opponents of the human in the SOUTH seat. */
		val OPPONENTS = listOf(WEST, NORTH, EAST)

		fun fromNumber(n: Int): Seat? = entries.getOrNull(n - 1)
	}
}

enum class Direction(val id: Int) {
	NONE(0), CLOCKWISE(1), COUNTERCLOCKWISE(2);

	fun reversed(): Direction = if (this == CLOCKWISE) COUNTERCLOCKWISE else CLOCKWISE

	companion object {
		fun fromId(id: Int): Direction = entries.firstOrNull { it.id == id } ?: NONE
	}
}

enum class PenaltyType(val id: Int) {
	NONE(0), CARD(1), EJECT(2), FACEUP(3);

	companion object {
		fun fromId(id: Int): PenaltyType = entries.firstOrNull { it.id == id } ?: NONE
	}
}

/** Result of checking whether a card may be played right now. */
enum class PlayCheck { INVALID, VALID, DEFENDER }
