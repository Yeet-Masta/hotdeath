package com.smorgasbork.hotdeath.engine

import com.smorgasbork.hotdeath.engine.CardColor.BLUE
import com.smorgasbork.hotdeath.engine.CardColor.GREEN
import com.smorgasbork.hotdeath.engine.CardColor.RED
import com.smorgasbork.hotdeath.engine.CardColor.YELLOW
import com.smorgasbork.hotdeath.engine.Seat.EAST
import com.smorgasbork.hotdeath.engine.Seat.NORTH
import com.smorgasbork.hotdeath.engine.Seat.SOUTH
import com.smorgasbork.hotdeath.engine.Seat.WEST
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RulesTest {
	private val filler = List(4) { plain(YELLOW, Face.NUMBERS[it + 3]) }

	@Test
	fun anyOfColorSuitOrTextMatches() {
		val tb = Table(plain(RED, Face.FIVE), hands = mapOf(SOUTH to listOf(
			plain(BLUE, Face.FIVE), plain(GREEN, Face.SEVEN), t(Special.FUCK_YOU), t(Special.FTPIP),
			t(Special.CURE, BLUE), plain(RED, Face.TWO),
		)))
		val legal = tb.game.legalPlays(tb.south).map { it.type }.toSet()
		assertEquals(setOf(plain(BLUE, Face.FIVE), t(Special.FUCK_YOU), plain(RED, Face.TWO)), legal)

		val text = Table(t(Special.CURE, RED), hands = mapOf(SOUTH to listOf(t(Special.CURE, BLUE))))
		assertEquals(1, text.game.legalPlays(text.south).size)

		val sixNine = Table(plain(RED, Face.NINE), hands = mapOf(SOUTH to listOf(t(Special.SIXTY_NINE, YELLOW))))
		assertEquals(1, sixNine.game.legalPlays(sixNine.south).size)
	}

	@Test
	fun warBansAColor() {
		val tb = Table(plain(RED, Face.FIVE), hands = mapOf(SOUTH to listOf(plain(RED, Face.TWO), t(Special.FUCK_YOU))))
		tb.south.warCard = tb.card(plain(RED, Face.EIGHT)).also { tb.south.field += it }
		assertEquals(listOf(t(Special.FUCK_YOU)), tb.game.legalPlays(tb.south).map { it.type })
	}

	@Test
	fun drawTwosStack() {
		val tb = Table(plain(RED, Face.THREE), hands = mapOf(
			SOUTH to listOf(plain(RED, Face.DRAW_TWO)) + filler,
			WEST to listOf(plain(GREEN, Face.DRAW_TWO)) + filler,
		))
		tb.script(WEST).plays += plain(GREEN, Face.DRAW_TWO)
		tb.play(plain(RED, Face.DRAW_TWO))
		assertEquals(5 + 4, tb.north.hand.size)
		assertEquals(4, tb.west.hand.size)
		assertSame(tb.east, tb.game.next)
	}

	@Test
	fun noTwosOnFours() {
		val tb = Table(plain(RED, Face.THREE), hands = mapOf(
			SOUTH to listOf(plain(CardColor.WILD, Face.WILD_DRAW_FOUR)) + filler,
			WEST to listOf(plain(GREEN, Face.DRAW_TWO)) + filler,
		))
		tb.script(WEST).plays += plain(GREEN, Face.DRAW_TWO)
		tb.play(plain(CardColor.WILD, Face.WILD_DRAW_FOUR))
		assertEquals(5 + 4, tb.west.hand.size)
		assertSame(tb.north, tb.game.next)
	}

	@Test
	fun holyDefenderBlocks() {
		val tb = Table(plain(RED, Face.THREE), hands = mapOf(
			SOUTH to listOf(plain(RED, Face.DRAW_TWO)) + filler,
			WEST to listOf(t(Special.HOLY_DEFENDER)) + filler,
		))
		tb.script(WEST).plays += t(Special.HOLY_DEFENDER)
		tb.play(plain(RED, Face.DRAW_TWO))
		assertEquals(4, tb.west.hand.size)
		assertSame(tb.north, tb.game.next)
	}

	@Test
	fun fuckYouSendsItBackAndReverses() {
		val tb = Table(plain(RED, Face.THREE), hands = mapOf(
			SOUTH to listOf(plain(CardColor.WILD, Face.WILD_DRAW_FOUR), plain(YELLOW, Face.THREE), plain(YELLOW, Face.FOUR)),
			WEST to listOf(t(Special.FUCK_YOU)) + filler,
		))
		tb.south.calledUno = true
		tb.script(WEST).plays += t(Special.FUCK_YOU)
		tb.play(plain(CardColor.WILD, Face.WILD_DRAW_FOUR))
		assertEquals(2 + 4, tb.south.hand.size)
		assertEquals(Direction.COUNTERCLOCKWISE, tb.game.direction)
		assertSame(tb.east, tb.game.next)
	}

	@Test
	fun aidsSplitsWithTheAttacker() {
		val tb = Table(plain(RED, Face.FIVE), hands = mapOf(
			SOUTH to listOf(t(Special.MYSTERY_DRAW)) + filler,
			WEST to listOf(t(Special.AIDS)) + filler,
		))
		tb.script(WEST).plays += t(Special.AIDS)
		tb.play(t(Special.MYSTERY_DRAW))
		assertEquals(4 + 3, tb.south.hand.size)
		assertEquals(4 + 2, tb.west.hand.size)
	}

	@Test
	fun luckAndVirusChangeDraws() {
		val lucky = Table(plain(RED, Face.THREE), hands = mapOf(SOUTH to listOf(plain(RED, Face.DRAW_TWO)) + filler))
		lucky.west.field += lucky.card(t(Special.LUCK))
		lucky.play(plain(RED, Face.DRAW_TWO))
		assertEquals(5 + 1, lucky.west.hand.size)

		val sick = Table(plain(RED, Face.THREE), hands = mapOf(SOUTH to listOf(plain(RED, Face.DRAW_TWO)) + filler))
		sick.west.field += sick.card(t(Special.VIRUS))
		sick.play(plain(RED, Face.DRAW_TWO))
		assertEquals(5 + 3, sick.west.hand.size)
	}

	@Test
	fun pingIsNeverChanged() {
		val tb = Table(plain(BLUE, Face.THREE), hands = mapOf(SOUTH to listOf(t(Special.PING)) + filler))
		tb.west.field += tb.card(t(Special.LUCK))
		tb.script(SOUTH).victim = WEST
		tb.play(t(Special.PING))
		assertEquals(5 + 1, tb.west.hand.size)
		assertSame(tb.west, tb.game.next)
	}

	@Test
	fun blueShield() {
		val small = Table(plain(RED, Face.THREE), hands = mapOf(SOUTH to listOf(plain(RED, Face.DRAW_TWO)) + filler))
		small.west.field += small.card(t(Special.BLUE_SHIELD))
		small.play(plain(RED, Face.DRAW_TWO))
		assertEquals(5, small.west.hand.size)
		assertTrue(small.west.hasField(Special.BLUE_SHIELD))
		assertSame(small.north, small.game.next)

		val big = Table(plain(RED, Face.THREE), hands = mapOf(SOUTH to listOf(plain(CardColor.WILD, Face.WILD_DRAW_FOUR)) + filler))
		big.west.field += big.card(t(Special.BLUE_SHIELD))
		big.play(plain(CardColor.WILD, Face.WILD_DRAW_FOUR))
		assertEquals(5, big.west.hand.size)
		assertFalse(big.west.hasField(Special.BLUE_SHIELD))

		val hos = Table(plain(RED, Face.THREE), hands = mapOf(SOUTH to listOf(t(Special.HARVESTER)) + filler))
		hos.west.field += hos.card(t(Special.BLUE_SHIELD))
		hos.play(t(Special.HARVESTER))
		assertEquals(5 + 4, hos.west.hand.size)
		assertFalse(hos.west.hasField(Special.BLUE_SHIELD))
	}

	@Test
	fun spreaderAndPennState() {
		val tb = Table(plain(RED, Face.THREE), hands = mapOf(SOUTH to listOf(t(Special.SPREADER)) + filler))
		tb.play(t(Special.SPREADER))
		for (s in listOf(WEST, NORTH, EAST)) assertEquals(7, tb.p(s).hand.size)
		assertSame(tb.south, tb.game.next)

		val ps = Table(plain(RED, Face.THREE), hands = mapOf(
			SOUTH to listOf(t(Special.SPREADER)) + filler,
			NORTH to listOf(t(Special.PENN_STATE)) + filler,
		))
		ps.play(t(Special.SPREADER))
		assertEquals(4 + 2, ps.south.hand.size)
		assertEquals(4, ps.north.hand.size)
		assertSame(ps.east, ps.game.next)
	}

	@Test
	fun quitter() {
		val tb = Table(plain(RED, Face.NINE), hands = mapOf(SOUTH to listOf(t(Special.QUITTER)) + filler))
		tb.play(t(Special.QUITTER))
		assertEquals(Status.REMOVED, tb.west.status)
		assertTrue(tb.west.hand.isEmpty)

		val elim = Table(plain(RED, Face.NINE), hands = mapOf(SOUTH to listOf(t(Special.QUITTER)) + filler),
			config = TestConfig(eliminationMode = true))
		elim.play(t(Special.QUITTER))
		assertEquals(Status.FINISHED, elim.south.status)
		assertEquals(Status.ACTIVE, elim.west.status)
	}

	@Test
	fun communismSharesEvenly() {
		val tb = Table(plain(RED, Face.THREE), hands = mapOf(SOUTH to listOf(t(Special.COMMUNISM), plain(YELLOW, Face.ONE), plain(YELLOW, Face.TWO))))
		tb.play(t(Special.COMMUNISM))
		val sizes = tb.game.players.map { it.hand.size }
		assertEquals(17, sizes.sum())
		assertTrue(sizes.max() - sizes.min() <= 1)
		assertEquals(5, tb.west.hand.size)
	}

	@Test
	fun fourHorsemenBringTheApocalypse() {
		val tb = Table(plain(RED, Face.THREE), hands = mapOf(SOUTH to listOf(
			t(Special.CONQUEST), t(Special.CURE, BLUE), plain(RED, Face.SEVEN), plain(YELLOW, Face.ONE),
		)))
		tb.west.field += tb.card(t(Special.DEATH))
		tb.game.neutral += tb.card(t(Special.FAMINE))
		tb.game.neutral += tb.card(t(Special.WAR))
		tb.play(t(Special.CONQUEST))
		assertTrue(tb.game.apocalypse)
		assertSame(CardType.RED_4, tb.game.topType)
		assertEquals(listOf(plain(RED, Face.SEVEN)), tb.game.legalPlays(tb.south).map { it.type })
	}

	@Test
	fun identicalCardsJumpIn() {
		val tb = Table(plain(RED, Face.THREE), hands = mapOf(
			SOUTH to listOf(plain(RED, Face.FIVE)) + filler,
			NORTH to listOf(plain(RED, Face.FIVE)) + filler,
		))
		tb.script(NORTH).jumpWith = plain(RED, Face.FIVE)
		tb.play(plain(RED, Face.FIVE))
		assertEquals(4, tb.north.hand.size)
		assertSame(tb.north, tb.game.currPlayer)
		assertSame(tb.east, tb.game.next)
	}

	@Test
	fun noUnoNoWin() {
		val tb = Table(plain(RED, Face.THREE), hands = mapOf(SOUTH to listOf(plain(RED, Face.FIVE))))
		tb.play(plain(RED, Face.FIVE))
		runBlocking { tb.game.checkRoundEnd() }
		assertFalse(tb.game.roundComplete)
		assertEquals(2, tb.south.hand.size)

		val ok = Table(plain(RED, Face.THREE), hands = mapOf(SOUTH to listOf(plain(RED, Face.FIVE))))
		ok.south.calledUno = true
		ok.play(plain(RED, Face.FIVE))
		runBlocking { ok.game.checkRoundEnd() }
		assertTrue(ok.game.roundComplete)
		assertEquals(SOUTH, ok.game.roundWinner)
	}

	@Test
	fun mysteryDrawUsesTheNumberBelow() {
		val tb = Table(plain(RED, Face.SEVEN), hands = mapOf(SOUTH to listOf(t(Special.MYSTERY_DRAW)) + filler))
		tb.play(t(Special.MYSTERY_DRAW))
		assertEquals(5 + 7, tb.west.hand.size)
	}

	@Test
	fun deathClockRunsOut() {
		val tb = Table(plain(RED, Face.THREE), hands = mapOf())
		tb.west.field += tb.card(t(Special.DEATH))
		tb.west.deathClock = 1
		runBlocking {
			assertFalse(tb.game.turnStartEffects(tb.west))
			assertTrue(tb.game.turnStartEffects(tb.west))
		}
		assertEquals(Status.REMOVED, tb.west.status)
		assertTrue(tb.game.neutral.any { it.special == Special.DEATH })
	}

	@Test
	fun recyclerPileGoesToTheNextPlayer() {
		val tb = Table(plain(BLUE, Face.THREE), hands = mapOf(
			SOUTH to listOf(t(Special.RECYCLER, BLUE)) + filler,
			EAST to listOf(plain(BLUE, Face.FIVE)) + filler,
			NORTH to listOf(plain(BLUE, Face.REVERSE)) + filler,
		))
		tb.play(t(Special.RECYCLER, BLUE))
		assertEquals(1, tb.game.recyclePiles.size)
		assertEquals(Direction.COUNTERCLOCKWISE, tb.game.direction)
		tb.play(plain(BLUE, Face.FIVE), by = EAST)
		tb.play(plain(BLUE, Face.REVERSE), by = NORTH)
		assertTrue(tb.game.recyclePiles.isEmpty())
		assertEquals(5, tb.east.hand.size)
		tb.game.assertCardsConserved()
	}

	@Test
	fun snapshotRoundTrip() {
		val tb = Table(plain(RED, Face.THREE), hands = mapOf(SOUTH to listOf(t(Special.LUCK)) + filler))
		tb.play(t(Special.LUCK))
		tb.west.deathClock = 3
		val json = SnapshotJson.capture(tb.game)
		val g = Game.restore(json, TestConfig(), Random(2), pacing = false)
		assertEquals(json, SnapshotJson.capture(g))
		g.assertCardsConserved()
	}

	@Test(expected = IllegalArgumentException::class)
	fun oldSavesAreRefused() {
		Game.restore("{\"drawPile\":[]}", TestConfig(), Random(2), pacing = false)
	}
}
