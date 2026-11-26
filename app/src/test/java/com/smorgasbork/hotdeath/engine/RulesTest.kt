package com.smorgasbork.hotdeath.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import com.smorgasbork.hotdeath.engine.CardType.*

class RulesTest {

	private fun standard(top: CardType, color: CardColor, vararg south: CardType) =
		gameAt(DeckType.STANDARD, top, color, south.toList())

	@Test
	fun colorMatchIsValid() = assertEquals(PlayCheck.VALID, standard(RED_7, CardColor.RED, RED_2).check(RED_2))

	@Test
	fun otherColorAndValueIsInvalid() = assertEquals(PlayCheck.INVALID, standard(RED_7, CardColor.RED, GREEN_2).check(GREEN_2))

	@Test
	fun valueMatchIsValid() = assertEquals(PlayCheck.VALID, standard(RED_7, CardColor.RED, GREEN_7).check(GREEN_7))

	@Test
	fun wildIsAlwaysValid() = assertEquals(PlayCheck.VALID, standard(RED_7, CardColor.RED, WILD).check(WILD))

	@Test
	fun chosenColorCountsAfterAWild() {
		val g = standard(WILD, CardColor.BLUE, BLUE_4, RED_4)
		assertEquals(PlayCheck.VALID, g.check(BLUE_4))
		assertEquals(PlayCheck.INVALID, g.check(RED_4))
	}

	@Test
	fun drawVariantsPlayOnEachOther() =
		assertEquals(PlayCheck.VALID, standard(RED_D, CardColor.RED, BLUE_D_SPREADER).check(BLUE_D_SPREADER))

	@Test
	fun magicFivePlaysOnAnything() =
		assertEquals(PlayCheck.VALID, standard(GREEN_8, CardColor.GREEN, RED_5_MAGIC).check(RED_5_MAGIC))

	@Test
	fun shitterOnlyGoesOnHolyDefenderOrMagicFiveOrAsLastCard() {
		assertEquals(PlayCheck.INVALID, standard(YELLOW_4, CardColor.YELLOW, YELLOW_0_SHITTER, RED_1).check(YELLOW_0_SHITTER))
		assertEquals(PlayCheck.VALID, standard(RED_0_HD, CardColor.RED, YELLOW_0_SHITTER, RED_1).check(YELLOW_0_SHITTER))
		assertEquals(PlayCheck.VALID, standard(YELLOW_4, CardColor.YELLOW, YELLOW_0_SHITTER).check(YELLOW_0_SHITTER))
	}

	@Test
	fun vanillaWildDrawFourNeedsNoColorMatch() {
		assertEquals(PlayCheck.INVALID,
			gameAt(DeckType.VANILLA, RED_7, CardColor.RED, listOf(WILD_DRAW_FOUR, RED_1)).check(WILD_DRAW_FOUR))
		assertEquals(PlayCheck.VALID,
			gameAt(DeckType.VANILLA, RED_7, CardColor.RED, listOf(WILD_DRAW_FOUR, BLUE_1)).check(WILD_DRAW_FOUR))
		// Hot Death decks have no such restriction.
		assertEquals(PlayCheck.VALID, standard(RED_7, CardColor.RED, WILD_DRAW_FOUR, RED_1).check(WILD_DRAW_FOUR))
	}

	@Test
	fun cardNotInHandIsInvalid() {
		val g = standard(RED_7, CardColor.RED, RED_2)
		val elsewhere = g.drawPile.asList.first { it.color == CardColor.RED }
		assertEquals(PlayCheck.INVALID, g.evaluate(g.southPlayer.hand, elsewhere))
	}

	private fun underHotDeath(vararg south: CardType) =
		gameAt(DeckType.STANDARD, WILD_HD, CardColor.RED, south.toList()) { _, top ->
			PenaltySnapshot(PenaltyType.CARD.id, 8, true, Seat.EAST.number, Seat.SOUTH.number, 0, top.deckIndex)
		}

	@Test
	fun defendersBeatHotDeath() {
		val g = underHotDeath(BLUE_0_FUCK_YOU, RED_0_HD, RED_5_MAGIC, WILD_DRAW_FOUR, RED_7)
		assertEquals(PlayCheck.DEFENDER, g.check(BLUE_0_FUCK_YOU))
		assertEquals(PlayCheck.DEFENDER, g.check(RED_0_HD))
		assertEquals(PlayCheck.DEFENDER, g.check(RED_5_MAGIC))
		assertEquals(PlayCheck.DEFENDER, g.check(WILD_DRAW_FOUR))
		assertEquals(PlayCheck.INVALID, g.check(RED_7))
	}

	@Test
	fun nothingDefendsAgainstAids() {
		val g = gameAt(DeckType.STANDARD, GREEN_3_AIDS, CardColor.GREEN, listOf(BLUE_0_FUCK_YOU)) { _, top ->
			PenaltySnapshot(PenaltyType.CARD.id, 3, false, Seat.EAST.number, Seat.SOUTH.number, 0, top.deckIndex)
		}
		assertEquals(PlayCheck.INVALID, g.check(BLUE_0_FUCK_YOU))
	}
}
