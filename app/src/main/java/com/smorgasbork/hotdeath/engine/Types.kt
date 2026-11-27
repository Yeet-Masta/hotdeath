package com.smorgasbork.hotdeath.engine

/**
 * Core value types for the Hot Death Uno Redux rules engine (Constitution v3.1).
 *
 * Everything in the `engine` package is plain Kotlin with no Android
 * dependencies, so the rules can be exercised by local JVM unit tests.
 * docs/RULES.md explains how each rule is implemented.
 */

enum class CardColor(val id: Int) {
	RED(1), GREEN(2), BLUE(3), YELLOW(4), WILD(5);

	companion object {
		/** The four playable colors. */
		val SUITS = listOf(RED, GREEN, BLUE, YELLOW)

		fun fromId(id: Int): CardColor? = entries.firstOrNull { it.id == id }
	}
}

/** The base face ("suit") of a card: what it is in a standard Uno deck. */
enum class Face(val number: Int?, val key: String) {
	ZERO(0, "0"), ONE(1, "1"), TWO(2, "2"), THREE(3, "3"), FOUR(4, "4"),
	FIVE(5, "5"), SIX(6, "6"), SEVEN(7, "7"), EIGHT(8, "8"), NINE(9, "9"),
	SKIP(null, "s"), REVERSE(null, "r"), DRAW_TWO(null, "d"),
	WILD(null, "wild"), WILD_DRAW_FOUR(null, "wild_drawfour");

	val isWild: Boolean get() = this == WILD || this == WILD_DRAW_FOUR

	companion object {
		val NUMBERS = listOf(ZERO, ONE, TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE)
		val COLORED = NUMBERS + listOf(SKIP, REVERSE, DRAW_TWO)
	}
}

/** How the Constitution groups the new cards. */
enum class Category { PLAIN, FIELD, DEFENSE, ATTACK, GLOBAL, TECHNICAL }

/**
 * Every new card in the Constitution. [replaces] lists, per copy, the color
 * of the standard card it replaces (the face is [face]).
 */
enum class Special(
	val face: Face,
	val replaces: List<CardColor>,
	val category: Category,
	/** Marked * in the Constitution: its player picks the target. */
	val directed: Boolean = false,
) {
	SIXTY_NINE(Face.SIX, listOf(CardColor.RED, CardColor.YELLOW), Category.TECHNICAL),
	AIDS(Face.THREE, listOf(CardColor.GREEN, CardColor.GREEN), Category.DEFENSE),
	BACKSTAB(Face.REVERSE, CardColor.SUITS, Category.ATTACK),
	BLUE_SHIELD(Face.TWO, listOf(CardColor.BLUE, CardColor.BLUE), Category.FIELD),
	CLONE(Face.TWO, listOf(CardColor.GREEN, CardColor.GREEN), Category.TECHNICAL),
	COMMUNISM(Face.SEVEN, listOf(CardColor.RED), Category.GLOBAL),
	CONQUEST(Face.FOUR, listOf(CardColor.RED), Category.FIELD),
	CURE(Face.ONE, CardColor.SUITS, Category.TECHNICAL),
	DEATH(Face.FOUR, listOf(CardColor.RED), Category.FIELD, directed = true),
	DELAYED_BLAST(Face.WILD, listOf(CardColor.WILD, CardColor.WILD), Category.ATTACK),
	DODGE(Face.EIGHT, CardColor.SUITS, Category.DEFENSE),
	DOUBLE_SKIP(Face.SKIP, CardColor.SUITS, Category.TECHNICAL),
	FAMINE(Face.FOUR, listOf(CardColor.RED), Category.FIELD),
	FTPIP(Face.ZERO, listOf(CardColor.BLUE), Category.DEFENSE, directed = true),
	FUCK_YOU(Face.ZERO, listOf(CardColor.BLUE), Category.DEFENSE),
	GIFT(Face.FIVE, CardColor.SUITS, Category.TECHNICAL, directed = true),
	GLASNOST(Face.TWO, listOf(CardColor.RED, CardColor.RED), Category.ATTACK, directed = true),
	GOLD_COIN(Face.ZERO, listOf(CardColor.YELLOW), Category.TECHNICAL, directed = true),
	HARVESTER(Face.WILD_DRAW_FOUR, listOf(CardColor.WILD, CardColor.WILD), Category.ATTACK),
	HOLY_DEFENDER(Face.ZERO, listOf(CardColor.RED, CardColor.YELLOW), Category.DEFENSE),
	HOT_DEATH(Face.WILD_DRAW_FOUR, listOf(CardColor.WILD, CardColor.WILD), Category.ATTACK),
	I_DONT_LIKE_THESE(Face.ONE, listOf(CardColor.YELLOW), Category.TECHNICAL),
	LUCK(Face.FOUR, listOf(CardColor.GREEN), Category.FIELD),
	MAD(Face.TWO, listOf(CardColor.YELLOW), Category.ATTACK, directed = true),
	MAGIC_5(Face.FIVE, listOf(CardColor.RED), Category.DEFENSE),
	MARTYR(Face.ONE, listOf(CardColor.RED), Category.TECHNICAL, directed = true),
	MYSTERY_DRAW(Face.WILD, listOf(CardColor.WILD, CardColor.WILD, CardColor.WILD), Category.ATTACK),
	NECROMANCER(Face.SEVEN, listOf(CardColor.GREEN), Category.TECHNICAL),
	PENN_STATE(Face.TWO, listOf(CardColor.BLUE, CardColor.BLUE), Category.DEFENSE),
	PING(Face.ONE, listOf(CardColor.BLUE, CardColor.BLUE, CardColor.BLUE), Category.ATTACK, directed = true),
	PLAGUE(Face.SIX, listOf(CardColor.GREEN), Category.FIELD),
	PROPHET(Face.ZERO, listOf(CardColor.RED), Category.FIELD),
	QUITTER(Face.NINE, listOf(CardColor.RED), Category.ATTACK),
	RECYCLER(Face.REVERSE, listOf(CardColor.BLUE), Category.TECHNICAL),
	REVERSE_SKIP(Face.SKIP, CardColor.SUITS, Category.TECHNICAL),
	SPREADER(Face.DRAW_TWO, CardColor.SUITS, Category.GLOBAL),
	SWAP(Face.REVERSE, listOf(CardColor.GREEN, CardColor.YELLOW), Category.TECHNICAL),
	TASTE_THE_RAINBOW(Face.WILD, listOf(CardColor.WILD), Category.ATTACK),
	CHOSEN_ONE(Face.ONE, listOf(CardColor.YELLOW), Category.TECHNICAL),
	FLOOD(Face.EIGHT, listOf(CardColor.BLUE), Category.GLOBAL),
	VIRUS(Face.ZERO, listOf(CardColor.GREEN, CardColor.GREEN), Category.FIELD, directed = true),
	WAR(Face.FOUR, listOf(CardColor.RED), Category.FIELD),
	YIN(Face.WILD, listOf(CardColor.WILD), Category.FIELD),
	YANG(Face.WILD, listOf(CardColor.WILD), Category.FIELD);

	val key: String get() = name.lowercase()

	val isHorseman: Boolean get() = this == DEATH || this == FAMINE || this == WAR || this == CONQUEST

	/** Luck-like: one card less on every draw. */
	val isLucky: Boolean get() = this == LUCK || this == YIN || this == YANG

	/** Field cards that sit in neutral play rather than in front of a player. */
	val isGlobalField: Boolean get() = this == FAMINE || this == WAR

	/** Field cards that come into play the moment they are drawn. */
	val activatesWhenDrawn: Boolean get() = this == YIN || this == YANG || this == PLAGUE
}

/**
 * One distinct card: color, base face and (for new cards) its special.
 * Equal types are identical cards for jump-ins.
 */
class CardType private constructor(val color: CardColor, val face: Face, val special: Special?) {

	/** Stable name, used in saves, on the wire and for artwork (`card_<key>`). */
	val key: String = buildString {
		if (color != CardColor.WILD) append(color.name.lowercase()).append('_')
		append(face.key)
		special?.let { append('_').append(it.key) }
	}

	/** The plain card this one counts as when specials stop working (the Apocalypse). */
	val base: CardType get() = if (special == null) this else of(color, face, null)

	val isNumber: Boolean get() = face.number != null

	/** Numeric value: the face number, or 69 for the 69 card; null for action cards. */
	val number: Int? get() = if (special == Special.SIXTY_NINE) 69 else face.number

	/** Faces this card counts as for matching (the 69 is both a 6 and a 9). */
	val suits: Set<Face> get() = if (special == Special.SIXTY_NINE) setOf(Face.SIX, Face.NINE) else setOf(face)

	override fun toString(): String = key

	companion object {
		private val all = LinkedHashMap<String, CardType>()

		/** Every card type that can exist, in deck order. */
		val ALL: List<CardType>

		init {
			for (c in CardColor.SUITS) for (f in Face.COLORED) add(CardType(c, f, null))
			add(CardType(CardColor.WILD, Face.WILD, null))
			add(CardType(CardColor.WILD, Face.WILD_DRAW_FOUR, null))
			for (s in Special.entries) for (c in s.replaces.distinct()) add(CardType(c, s.face, s))
			ALL = all.values.toList()
		}

		private fun add(t: CardType) {
			all[t.key] = t
		}

		fun of(color: CardColor, face: Face, special: Special?): CardType {
			val col = if (face.isWild) CardColor.WILD else color
			return requireNotNull(all[CardType(col, face, special).key]) { "no card $col $face $special" }
		}

		fun plain(color: CardColor, face: Face): CardType = of(color, face, null)

		fun special(s: Special, color: CardColor = s.replaces.first()): CardType = of(color, s.face, s)

		fun fromKey(key: String): CardType? = all[key]

		val RED_4: CardType get() = plain(CardColor.RED, Face.FOUR)
	}
}

enum class Seat(val number: Int) {
	SOUTH(1), WEST(2), NORTH(3), EAST(4);

	/** 0-based position in the player list. */
	val index: Int get() = number - 1

	companion object {
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

/** Where a player stands in the current round. */
enum class Status {
	ACTIVE,
	/** Went out (normal mode: the winner; elimination mode: safe). */
	FINISHED,
	/** Removed by a card or an accusation. */
	REMOVED,
	/** Dropped out to join a Yin/Yang team; shares [Player.teammate]'s result. */
	TEAMED,
}
