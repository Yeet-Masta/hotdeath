package com.smorgasbork.hotdeath

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.smorgasbork.hotdeath.engine.DeckType
import com.smorgasbork.hotdeath.engine.GameConfig
import com.smorgasbork.hotdeath.engine.Seat

/**
 * [GameConfig] backed by the app's default shared preferences. Values are read
 * live, so changes made in the settings screen apply to a running game the
 * same way they did in the original app.
 */
class PrefsConfig(context: Context) : GameConfig {

	private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

	private fun int(key: String, def: String): Int = prefs.getString(key, def)?.toIntOrNull() ?: def.toInt()

	override val deckType: DeckType
		get() {
			prefs.getString(DECK_TYPE, null)?.let { return DeckType.fromKey(it) }
			// Migrate the old two_decks boolean preference.
			if (prefs.contains(LEGACY_TWO_DECKS)) {
				val twoDecks = prefs.getBoolean(LEGACY_TWO_DECKS, false)
				val t = if (twoDecks) DeckType.STANDARD else DeckType.HALF
				prefs.edit().putString(DECK_TYPE, t.key).remove(LEGACY_TWO_DECKS).apply()
				return t
			}
			return DeckType.fromKey(DECK_TYPE_DEF)
		}

	override val faceUp: Boolean get() = prefs.getBoolean(FACE_UP, false)
	override val humanSeats: Set<Seat>
		get() = if (prefs.getBoolean(COMPUTER_4TH, false)) emptySet() else setOf(Seat.SOUTH)
	override val cheatLevel: Int get() = int(CHEAT_LEVEL, "0")
	override val pauseLength: Int get() = int(GAME_SPEED, "1")
	val familyFriendly: Boolean get() = prefs.getBoolean(FAMILY_FRIENDLY, true)
	val haptics: Boolean get() = prefs.getBoolean(HAPTICS, true)
	/** How many recent plays tapping the discard pile shows. */
	val historyCount: Int get() = int(HISTORY_COUNT, "5")
	/** Seconds per decision in a multiplayer game this device hosts; 0 = no limit. */
	var mpTurnSeconds: Int
		get() = int(MP_TURN_TIMER, "0")
		set(v) = prefs.edit().putString(MP_TURN_TIMER, v.toString()).apply()

	/** WEST is opponent 1, NORTH 2, EAST 3; a computer in SOUTH mirrors opponent 2. */
	private fun opponentNumber(seat: Seat): Int = when (seat) {
		Seat.WEST -> 1
		Seat.EAST -> 3
		else -> 2
	}

	override fun skill(seat: Seat): Int = int("p${opponentNumber(seat)}_skill", "1")
	override fun aggression(seat: Seat): Int = int("p${opponentNumber(seat)}_aggression", "0")

	// ── saved game ────────────────────────────────────────────────────────────

	var savedGame: String?
		get() = prefs.getString(GAME_STATE, null)?.takeIf { it.isNotEmpty() }
		set(v) {
			val e = prefs.edit()
			if (v == null) e.remove(GAME_STATE) else e.putString(GAME_STATE, v)
			e.apply()
		}

	companion object {
		private const val GAME_SPEED = "game_speed"
		private const val DECK_TYPE = "deck_type"
		private const val DECK_TYPE_DEF = "hybrid"
		private const val LEGACY_TWO_DECKS = "two_decks"
		private const val COMPUTER_4TH = "computer_4th"
		private const val FACE_UP = "face_up"
		private const val CHEAT_LEVEL = "cheat_level"
		private const val FAMILY_FRIENDLY = "family_friendly"
		private const val GAME_STATE = "gamestate"
		private const val HAPTICS = "haptics"
		private const val HISTORY_COUNT = "history_count"
		private const val MP_TURN_TIMER = "mp_turn_timer"
	}
}
