package com.smorgasbork.hotdeath.engine

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Plays whole computer-only rounds with no animation pauses. */
class GameFlowTest {

	/** Plays [rounds] rounds of [g], checking card conservation at every state change. */
	private fun play(g: Game, rounds: Int, onState: () -> Unit = {}): Int {
		var done = 0
		var job: Job? = null
		g.listener = GameListener { e ->
			if (e == GameEvent.StateChanged) {
				g.assertCardsConserved()
				onState()
				if (g.waitingToStartRound) {
					done++
					if (done >= rounds) job?.cancel() else g.requestNextRound()
				}
			}
		}
		runBlocking {
			withTimeout(120_000) {
				job = launch { g.run() }
				job!!.join()
			}
		}
		return done
	}

	@Test
	fun normalRoundsEndWithAWinner() {
		for (seed in 1..6) {
			val g = Game.newGame(TestConfig(skill = seed % 3), Random(seed), pacing = false)
			assertEquals(10, play(g, 10))
			// a Yin/Yang team win counts for both partners
			assertTrue(g.players.sumOf { it.wins } in 1..20)
		}
	}

	@Test
	fun eliminationRoundsEndWithALoser() {
		for (seed in 1..6) {
			val g = Game.newGame(TestConfig(eliminationMode = true, skill = seed % 3), Random(100 + seed), pacing = false)
			assertEquals(10, play(g, 10))
			assertTrue(g.players.sumOf { it.losses } in 1..20)
		}
	}

	@Test
	fun restoredGameCarriesOn() {
		val g = Game.newGame(TestConfig(), Random(42), pacing = false)
		var saved: String? = null
		var n = 0
		play(g, 2) { if (++n == 300) saved = g.snapshot }
		assertNotNull(saved)
		val restored = Game.restore(saved!!, TestConfig(), Random(1), pacing = false)
		restored.assertCardsConserved()
		assertEquals(3, play(restored, 3))
	}
}
