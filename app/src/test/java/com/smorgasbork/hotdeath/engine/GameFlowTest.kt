package com.smorgasbork.hotdeath.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Plays whole computer-only games with no animation pauses. */
class GameFlowTest {

	/**
	 * Runs [g] to the end, checking card conservation at every state change and
	 * tapping "next round" whenever asked. Returns the number of checks made.
	 */
	private fun play(g: Game): Int {
		var checks = 0
		g.listener = GameListener { e ->
			if (e == GameEvent.StateChanged) {
				g.assertCardsConserved()
				checks++
				if (g.waitingToStartRound) g.requestNextRound()
			}
		}
		runBlocking { withTimeout(120_000) { g.run() } }
		return checks
	}

	@Test
	fun everyDeckTypePlaysToAWinner() {
		for (deckType in DeckType.entries) {
			for (seed in 1..3) {
				val label = "$deckType/$seed"
				val g = Game.newGame(TestConfig(deckType), Random(seed), pacing = false)
				assertTrue(label, play(g) > 0)
				assertTrue(label, g.gameOver)
				val winner = g.gameWinner
				assertNotNull(label, winner)
				assertEquals(label, g.players.minOf { it.totalScore }, g.player(winner!!).totalScore)
				val endScore = if (deckType == DeckType.VANILLA) 500 else 1000
				assertTrue(label, g.players.any { it.totalScore >= endScore })
			}
		}
	}

	@Test
	fun expertComputersAlsoFinish() {
		val g = Game.newGame(TestConfig(DeckType.EXTENDED, skill = 2), Random(7), pacing = false)
		play(g)
		assertTrue(g.gameOver)
	}

	@Test
	fun snapshotSurvivesJsonAndRestoredGameFinishes() {
		val g = Game.newGame(TestConfig(DeckType.EXTENDED), Random(42), pacing = false)
		var saved: GameSnapshot? = null
		var n = 0
		g.listener = GameListener { e ->
			if (e == GameEvent.StateChanged) {
				if (++n == 200) saved = g.snapshot
				if (g.waitingToStartRound) g.requestNextRound()
			}
		}
		runBlocking { withTimeout(120_000) { g.run() } }

		val s = saved
		assertNotNull(s)
		val json = SnapshotJson.toJson(s!!)
		assertEquals(s, SnapshotJson.fromJson(json))

		val restored = Game.restore(SnapshotJson.fromJson(json), TestConfig(DeckType.EXTENDED), Random(1), pacing = false)
		restored.assertCardsConserved()
		play(restored)
		assertTrue(restored.gameOver)
	}

	@Test(expected = IllegalArgumentException::class)
	fun restoreRejectsASnapshotWithoutPlayers() {
		val s = gameAt(DeckType.STANDARD, CardType.RED_1, CardColor.RED, listOf(CardType.RED_2)).snapshot!!
		Game.restore(s.copy(players = emptyList()), TestConfig(), Random(1), pacing = false)
	}
}
