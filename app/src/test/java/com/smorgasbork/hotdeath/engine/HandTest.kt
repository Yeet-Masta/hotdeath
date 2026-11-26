package com.smorgasbork.hotdeath.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.smorgasbork.hotdeath.engine.CardType.*

class HandTest {

	private fun value(vararg t: CardType) = handOf(*t).calculateValue().total

	@Test
	fun emptyHandIsWorthNothing() = assertEquals(0, value())

	@Test
	fun plainCardsAddUp() = assertEquals(3 + 7 + 20 + 50, value(RED_3, GREEN_7, RED_D, WILD))

	@Test
	fun fuckYouDoublesTheTotal() = assertEquals((3 + 7) * 2, value(RED_3, GREEN_7, BLUE_0_FUCK_YOU))

	@Test
	fun holyDefenderHalvesRoundingUp() = assertEquals(2, value(RED_3, RED_0_HD))

	@Test
	fun fullMontyIsAThousand() = assertEquals(1000 + 4, value(BLUE_0_FUCK_YOU, GREEN_0_QUITTER, RED_4))

	@Test
	fun shitterIsValued150ButLeftOutOfTheTotal() {
		// At round end the Shitter takes the table's highest score instead.
		val h = handOf(YELLOW_0_SHITTER, RED_4)
		val v = h.calculateValue()
		assertEquals(4, v.total)
		assertEquals(150, v.valueOf(h.cards.first { it.type == YELLOW_0_SHITTER }))
	}

	@Test
	fun sixtyNinePegsTheTotal() = assertEquals(69, value(YELLOW_69, RED_9, GREEN_9, WILD))

	@Test
	fun magicFiveStillCountsAgainstSixtyNine() = assertEquals(64, value(YELLOW_69, RED_5_MAGIC, WILD))

	@Test
	fun mysteryIsTenTimesTheHighestNumber() = assertEquals(70 + 7 + 2, value(WILD_MYSTERY, RED_7, GREEN_2))

	@Test
	fun blueShieldCopiesTheHighestCard() = assertEquals(50 + 1 + 50, value(BLUE_2_SHIELD, WILD, RED_1))

	@Test
	fun withoutCardIgnoresThatCard() {
		val h = handOf(RED_3, GREEN_7)
		assertEquals(3, h.calculateValue(h.cards.first { it.type == GREEN_7 }).total)
	}

	@Test
	fun countsAids() = assertEquals(1, handOf(GREEN_3_AIDS, RED_3).calculateValue().aidsCount)

	@Test
	fun allBastardCards() {
		assertTrue(handOf(RED_0_HD, GREEN_0_QUITTER, BLUE_0_FUCK_YOU, YELLOW_0_SHITTER, RED_1).hasAllBastardCards())
		assertFalse(handOf(RED_0_HD, GREEN_0_QUITTER, BLUE_0_FUCK_YOU, RED_1).hasAllBastardCards())
	}

	@Test
	fun visibilityOfFaceDownHand() {
		val p = CardPicker(CardDeck(DeckType.STANDARD))
		val a = p.take(RED_1)
		val b = p.take(RED_2)
		val h = Hand(false).apply { add(a); add(b) }
		assertFalse(h.isVisible(a))
		h.reveal(a)
		assertTrue(h.isVisible(a))
		assertFalse(h.isVisible(b))
		h.moveAllToTable()
		assertTrue(h.isVisible(b))
	}

	@Test
	fun growsPastAnyFixedSize() {
		// The original Hand used a fixed array; a hand must hold any number of cards.
		val h = Hand(true)
		CardDeck(DeckType.EXTENDED).cards.forEach(h::add)
		assertEquals(225, h.size)
	}
}
