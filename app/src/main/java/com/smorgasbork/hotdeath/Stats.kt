package com.smorgasbork.hotdeath

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.smorgasbork.hotdeath.engine.CardDeck
import com.smorgasbork.hotdeath.engine.CardType
import com.smorgasbork.hotdeath.engine.GameMessage
import com.smorgasbork.hotdeath.engine.Seat
import com.smorgasbork.hotdeath.net.TableView
import com.smorgasbork.hotdeath.net.ViewEvent

/** Lifetime totals for the person at this device, kept in shared preferences. */
class Stats(context: Context) {

	private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

	val games: Int get() = prefs.getInt(GAMES, 0)
	val gamesWon: Int get() = prefs.getInt(GAMES_WON, 0)
	val rounds: Int get() = prefs.getInt(ROUNDS, 0)
	val roundsWon: Int get() = prefs.getInt(ROUNDS_WON, 0)
	/** Lowest final score in a finished game, or null before the first one. */
	val bestScore: Int? get() = prefs.getInt(BEST_SCORE, -1).takeIf { it >= 0 }
	val penaltyCards: Int get() = prefs.getInt(PENALTY_CARDS, 0)

	/** The card type played most often, with its count. */
	val favourite: Pair<CardType, Int>?
		get() = CardType.entries
			.map { it to prefs.getInt(PLAYED + it.name, 0) }
			.filter { it.second > 0 }
			.maxByOrNull { it.second }

	private fun bump(key: String, by: Int = 1) {
		prefs.edit().putInt(key, prefs.getInt(key, 0) + by).apply()
	}

	fun roundPlayed() = bump(ROUNDS)
	fun roundWon() = bump(ROUNDS_WON)
	fun penaltyTaken(cards: Int) = bump(PENALTY_CARDS, cards)
	fun played(t: CardType) = bump(PLAYED + t.name)

	fun gameFinished(won: Boolean, score: Int) {
		val e = prefs.edit().putInt(GAMES, games + 1)
		if (won) e.putInt(GAMES_WON, gamesWon + 1)
		val best = bestScore
		if (best == null || score < best) e.putInt(BEST_SCORE, score)
		e.apply()
	}

	fun reset() {
		val e = prefs.edit()
		for (k in prefs.all.keys) if (k.startsWith(PREFIX)) e.remove(k)
		e.apply()
	}

	companion object {
		private const val PREFIX = "stats_"
		private const val GAMES = PREFIX + "games"
		private const val GAMES_WON = PREFIX + "games_won"
		private const val ROUNDS = PREFIX + "rounds"
		private const val ROUNDS_WON = PREFIX + "rounds_won"
		private const val BEST_SCORE = PREFIX + "best_score"
		private const val PENALTY_CARDS = PREFIX + "penalty_cards"
		private const val PLAYED = PREFIX + "played_"
	}
}

/** One card on the discard pile and who put it there (null: turned up from the draw pile). */
data class PlayRecord(val card: Int, val from: Seat?)

/**
 * Watches one game's updates and keeps [Stats] and the recent-plays list.
 * The first view is only a baseline, so a resumed or rotated game is not
 * counted twice.
 */
class GameRecorder(private val stats: Stats) {

	private val plays = ArrayDeque<PlayRecord>()
	private var deck: CardDeck? = null
	private var lastRoundComplete: Boolean? = null
	private var lastGameOver: Boolean? = null

	/** Most recent first. */
	val recent: List<PlayRecord> get() = plays.toList()

	fun record(v: TableView, e: ViewEvent?) {
		if (deck?.deckType != v.deckType) deck = CardDeck(v.deckType)
		val me = v.me
		when (e) {
			ViewEvent.RoundReset -> plays.clear()
			is ViewEvent.Discarded -> {
				plays.addFirst(PlayRecord(e.card, e.from))
				while (plays.size > MAX_HISTORY) plays.removeLast()
				if (e.from == me) deck?.get(e.card)?.type?.let(stats::played)
			}
			is ViewEvent.Message -> when (val m = e.message) {
				is GameMessage.RoundWinner -> if (m.seat == me) stats.roundWon()
				is GameMessage.TakesPenalty -> if (m.seat == me) stats.penaltyTaken(m.count)
				else -> {}
			}
			else -> {}
		}
		if (lastRoundComplete == false && v.roundComplete) stats.roundPlayed()
		lastRoundComplete = v.roundComplete
		if (lastGameOver == false && v.gameOver) stats.gameFinished(v.gameWinner == me, v.player(me).totalScore)
		lastGameOver = v.gameOver
	}

	companion object {
		/** Most plays kept (the most the history setting offers). */
		const val MAX_HISTORY = 20
	}
}
