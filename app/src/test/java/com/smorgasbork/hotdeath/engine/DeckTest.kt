package com.smorgasbork.hotdeath.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckTest {
	private val deck = CardDeck.STANDARD

	@Test
	fun fourStandardDecks() {
		assertEquals(4 * 108, deck.size)
		assertEquals(deck.size, deck.cards.map { it.deckIndex }.toSet().size)
		deck.cards.forEachIndexed { i, c -> assertEquals(i, c.deckIndex) }
	}

	@Test
	fun everySpecialHasTheConstitutionsCount() {
		for (s in Special.entries) {
			assertEquals(s.name, s.replaces.size, deck.cards.count { it.special == s })
			for (color in s.replaces.distinct()) {
				val n = deck.cards.count { it.special == s && it.color == color }
				assertEquals("$s $color", s.replaces.count { it == color }, n)
			}
		}
	}

	@Test
	fun specialsReplaceTheirBaseCards() {
		// Red 4: 8 per four decks, four of them Horsemen.
		assertEquals(8, deck.cards.count { it.color == CardColor.RED && it.face == Face.FOUR })
		assertEquals(4, deck.cards.count { it.type === CardType.RED_4 })
		// Wilds: 16, of which 8 are new cards.
		assertEquals(16, deck.cards.count { it.face == Face.WILD })
		assertEquals(8, deck.cards.count { it.face == Face.WILD && it.special != null })
		assertEquals(4, deck.cards.count { it.face == Face.WILD_DRAW_FOUR && it.special != null })
		// Zeros: four per color.
		for (c in CardColor.SUITS) assertEquals(4, deck.cards.count { it.color == c && it.face == Face.ZERO })
	}

	@Test
	fun typeKeysRoundTrip() {
		for (t in CardType.ALL) assertTrue(t.key, CardType.fromKey(t.key) === t)
		assertEquals("red_4_death", t(Special.DEATH).key)
		assertEquals("wild_yin", t(Special.YIN).key)
		assertEquals("wild_drawfour_hot_death", t(Special.HOT_DEATH).key)
		assertEquals("blue_r", plain(CardColor.BLUE, Face.REVERSE).key)
	}

	@Test
	fun sixtyNine() {
		val t69 = t(Special.SIXTY_NINE, CardColor.YELLOW)
		assertEquals(69, t69.number)
		assertEquals(setOf(Face.SIX, Face.NINE), t69.suits)
		assertNotNull(deck.firstOf(Special.SIXTY_NINE))
		assertFalse(plain(CardColor.RED, Face.SKIP).isNumber)
	}
}
