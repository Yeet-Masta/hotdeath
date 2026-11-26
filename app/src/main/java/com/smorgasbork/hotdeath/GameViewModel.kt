package com.smorgasbork.hotdeath

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.smorgasbork.hotdeath.engine.Game
import com.smorgasbork.hotdeath.engine.SnapshotJson
import com.smorgasbork.hotdeath.net.GameSession
import com.smorgasbork.hotdeath.net.HostSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * Hands a multiplayer session from the lobby screen to the game screen.
 * Holds it only between the two; the game screen's ViewModel then owns it.
 */
object Multiplayer {
	class Handoff(val session: GameSession, val scope: CoroutineScope?, val cleanup: () -> Unit)

	private var pending: Handoff? = null

	fun handOff(h: Handoff) {
		pending?.let { it.session.close(); it.scope?.cancel(); it.cleanup() }
		pending = h
	}

	fun take(): Handoff? = pending.also { pending = null }
}

/**
 * Owns the [GameSession] so it survives configuration changes. A local game
 * runs in [viewModelScope] on the main thread; a multiplayer session comes
 * from [Multiplayer] and is closed when the game screen is finished for good.
 */
class GameViewModel(app: Application, mode: Int) : AndroidViewModel(app) {

	val config = PrefsConfig(app)
	private val handoff: Multiplayer.Handoff? =
		if (mode == GameActivity.STARTUP_MODE_MULTIPLAYER) Multiplayer.take() else null

	/** Null only when a multiplayer screen was recreated after the process died. */
	val session: GameSession? = when {
		handoff != null -> handoff.session
		mode == GameActivity.STARTUP_MODE_MULTIPLAYER -> null
		else -> {
			val game = (if (mode == GameActivity.STARTUP_MODE_CONTINUE) loadSaved() else null) ?: Game.newGame(config)
			game.logger = { Log.d(TAG, it) }
			HostSession(game, viewModelScope)
		}
	}

	val multiplayer: Boolean get() = session?.savable == false

	fun start() = session?.start()

	fun pause() {
		// A multiplayer host keeps running for the other players.
		if (session?.savable == true) session.pause()
	}

	fun resume() = session?.resume()

	/** Writes the last turn-boundary snapshot of a local game; a finished game clears the save. */
	fun save() {
		val host = session as? HostSession ?: return
		if (!host.savable) return
		if (host.game.gameOver) {
			config.savedGame = null
			return
		}
		host.game.snapshot?.let { config.savedGame = SnapshotJson.toJson(it) }
	}

	override fun onCleared() {
		session?.close()
		handoff?.scope?.cancel()
		handoff?.cleanup?.invoke()
	}

	private fun loadSaved(): Game? {
		val json = config.savedGame ?: return null
		return try {
			Game.restore(SnapshotJson.fromJson(json), config)
		} catch (e: Exception) {
			// A corrupt or incompatible save starts a new game instead.
			Log.w(TAG, "could not restore saved game", e)
			null
		}
	}

	companion object {
		private const val TAG = "HDU"

		fun factory(app: Application, mode: Int) = object : ViewModelProvider.Factory {
			@Suppress("UNCHECKED_CAST")
			override fun <T : ViewModel> create(modelClass: Class<T>): T = GameViewModel(app, mode) as T
		}
	}
}
