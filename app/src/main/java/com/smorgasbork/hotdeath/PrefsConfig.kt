package com.smorgasbork.hotdeath

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
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

	override val faceUp: Boolean get() = prefs.getBoolean(FACE_UP, false)
	override val humanSeats: Set<Seat>
		get() = if (prefs.getBoolean(COMPUTER_4TH, false)) emptySet() else setOf(Seat.SOUTH)
	override val eliminationMode: Boolean get() = prefs.getBoolean(ELIMINATION, false)
	override val autoUno: Boolean get() = prefs.getBoolean(AUTO_UNO, false)
	override val jumpInMs: Long get() = int(JUMP_IN_MS, "2000").toLong()
	override val pauseLength: Int get() = int(GAME_SPEED, "1")
	val familyFriendly: Boolean get() = prefs.getBoolean(FAMILY_FRIENDLY, true)

	/** WEST is opponent 1, NORTH 2, EAST 3; a computer in SOUTH mirrors opponent 2. */
	private fun opponentNumber(seat: Seat): Int = when (seat) {
		Seat.WEST -> 1
		Seat.EAST -> 3
		else -> 2
	}

	override fun skill(seat: Seat): Int = int("p${opponentNumber(seat)}_skill", "1")

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
		private const val COMPUTER_4TH = "computer_4th"
		private const val FACE_UP = "face_up"
		private const val ELIMINATION = "elimination"
		private const val AUTO_UNO = "auto_uno"
		private const val JUMP_IN_MS = "jump_in_ms"
		private const val FAMILY_FRIENDLY = "family_friendly"
		private const val GAME_STATE = "gamestate"
	}
}
