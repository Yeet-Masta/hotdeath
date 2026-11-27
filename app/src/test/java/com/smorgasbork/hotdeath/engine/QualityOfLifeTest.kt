package com.smorgasbork.hotdeath.engine

import com.smorgasbork.hotdeath.engine.CardType.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityOfLifeTest {

	private fun Game.why(t: CardType): NoGoodReason = whyInvalid(southPlayer.hand, southPlayer.hand.cards.first { it.type == t })

	@Test
	fun aMismatchSaysWhatIsNeeded() {
		val g = gameAt(DeckType.STANDARD, RED_7, CardColor.RED, listOf(GREEN_2))
		assertEquals(NoGoodReason.NoMatch(CardColor.RED, RED_7), g.why(GREEN_2))
	}

	@Test
	fun theShitterExplainsItself() {
		val g = gameAt(DeckType.STANDARD, YELLOW_4, CardColor.YELLOW, listOf(YELLOW_0_SHITTER, RED_1))
		assertEquals(NoGoodReason.Shitter, g.why(YELLOW_0_SHITTER))
	}

	@Test
	fun vanillaWildDrawFourNamesTheColorYouHold() {
		val g = gameAt(DeckType.VANILLA, RED_7, CardColor.RED, listOf(WILD_DRAW_FOUR, RED_1))
		assertEquals(NoGoodReason.HoldsColor(CardColor.RED), g.why(WILD_DRAW_FOUR))
	}

	@Test
	fun tappingABadCardSendsTheReason() {
		val g = gameAt(DeckType.STANDARD, RED_7, CardColor.RED, listOf(GREEN_2, RED_2))
		val messages = ArrayList<GameMessage>()
		g.listener = GameListener { e -> if (e is GameEvent.Message) messages += e.message }
		runBlocking {
			val job = launch { g.run() }
			withTimeout(5_000) { while (g.humanController(Seat.SOUTH)?.awaitingTurn != true) delay(1) }
			g.humanPlay(Seat.SOUTH, g.southPlayer.hand.cards.first { it.type == GREEN_2 })
			job.cancel()
		}
		assertTrue(messages.contains(GameMessage.CardNoGood(Seat.SOUTH, NoGoodReason.NoMatch(CardColor.RED, RED_7))))
	}

	@Test
	fun aPersonWhoRunsOutOfTimeHasAComputerMoveForThem() {
		val g = gameAt(DeckType.STANDARD, RED_7, CardColor.RED, listOf(RED_2, GREEN_3), turnSeconds = 1)
		val messages = ArrayList<GameMessage>()
		g.listener = GameListener { e -> if (e is GameEvent.Message) messages += e.message }
		runBlocking {
			val job = launch { g.run() }
			withTimeout(5_000) { while (g.clockSeat != Seat.SOUTH) delay(1) }
			assertTrue(g.clockDeadline > System.currentTimeMillis())
			withTimeout(5_000) { while (GameMessage.TimedOut(Seat.SOUTH) !in messages) delay(5) }
			// The stand-in played SOUTH's only legal card, and SOUTH is still a person.
			withTimeout(5_000) { while (g.southPlayer.hand.cards.any { it.type == RED_2 }) delay(5) }
			assertTrue(g.southPlayer.isHuman)
			job.cancel()
		}
	}

	@Test
	fun aSeatHandedToAComputerCanBeTakenBack() {
		val g = gameAt(DeckType.STANDARD, RED_7, CardColor.RED, listOf(RED_2))
		g.handOverToComputer(Seat.SOUTH)
		assertTrue(!g.southPlayer.isHuman)
		g.takeBack(Seat.SOUTH)
		assertTrue(g.southPlayer.isHuman)
	}

	@Test
	fun strongComputersStopAPlayerAboutToGoOut() = runBlocking {
		// WEST (next after SOUTH, clockwise) holds one card; SOUTH, a computer, can skip them.
		val g = gameAt(DeckType.STANDARD, RED_7, CardColor.RED, listOf(RED_S, WILD, GREEN_9), computerSouth = true, westCards = 1)
		val action = g.southPlayer.controller.takeTurn(g, g.southPlayer)
		assertEquals(RED_S, (action as TurnAction.Play).card.type)
	}
}
