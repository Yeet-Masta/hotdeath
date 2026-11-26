package com.smorgasbork.hotdeath.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckTest {

	private fun deck(t: DeckType) = CardDeck(t)

	@Test
	fun vanillaIsTheClassic108CardDeck() {
		val d = deck(DeckType.VANILLA)
		assertEquals(108, d.size)
		assertEquals(4, d.cards.count { it.type == CardType.WILD })
		assertEquals(4, d.cards.count { it.type == CardType.WILD_DRAW_FOUR })
		assertEquals(1, d.cards.count { it.type == CardType.RED_0 })
		assertEquals(2, d.cards.count { it.type == CardType.RED_7 })
		assertTrue(d.cards.none { it.type.legacyId > CardType.WILD_DRAW_FOUR.legacyId })
	}

	@Test
	fun deckSizes() {
		assertEquals(108, deck(DeckType.HALF).size)
		assertEquals(216, deck(DeckType.STANDARD).size)
		assertEquals(216 + 9, deck(DeckType.EXTENDED).size)
		assertTrue(deck(DeckType.HYBRID).size > 108)
	}

	@Test
	fun deckIndexIsPosition() {
		for (t in DeckType.entries) {
			deck(t).cards.forEachIndexed { i, c -> assertEquals(i, c.deckIndex) }
		}
	}

	@Test
	fun hotDeathDecksHoldAllFourBastards() {
		for (t in DeckType.entries - DeckType.VANILLA) {
			val types = deck(t).types
			assertTrue("$t", types.containsAll(CardType.entries.filter { it.isBastard }))
		}
	}

	@Test
	fun onlyExtendedHasVersion3Cards() {
		val v3 = setOf(CardType.RED_R_BACKSTAB, CardType.GREEN_2_CLONE, CardType.BLUE_1_PING, CardType.YELLOW_R_SWAP)
		assertTrue(deck(DeckType.EXTENDED).types.containsAll(v3))
		for (t in DeckType.entries - DeckType.EXTENDED) {
			assertTrue("$t", deck(t).types.none { it in v3 })
		}
	}

	@Test
	fun swappedHotDeathCardsReplaceVanillaOnes() {
		// HALF swaps cards in, so its size stays 108 but it loses a plain red 0.
		val d = deck(DeckType.HALF)
		assertEquals(0, d.cards.count { it.type == CardType.RED_0 })
		assertEquals(1, d.cards.count { it.type == CardType.RED_0_HD })
	}

	@Test
	fun fromKeyRoundTripsAndFallsBack() {
		for (t in DeckType.entries) assertEquals(t, DeckType.fromKey(t.key))
		assertEquals(DeckType.STANDARD, DeckType.fromKey("no-such-deck"))
		assertEquals(DeckType.STANDARD, DeckType.fromKey(null))
	}
}
