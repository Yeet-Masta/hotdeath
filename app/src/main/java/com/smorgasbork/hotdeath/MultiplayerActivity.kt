package com.smorgasbork.hotdeath

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.smorgasbork.hotdeath.engine.Seat
import com.smorgasbork.hotdeath.mp.NearbyClient
import com.smorgasbork.hotdeath.mp.NearbyHost
import com.smorgasbork.hotdeath.mp.RelayClient
import com.smorgasbork.hotdeath.mp.RelayHost
import com.smorgasbork.hotdeath.mp.nearbyPermissions
import com.smorgasbork.hotdeath.net.ClientLobby
import com.smorgasbork.hotdeath.net.ClientSession
import com.smorgasbork.hotdeath.net.HostLobby
import com.smorgasbork.hotdeath.net.Link
import com.smorgasbork.hotdeath.net.Wire
import kotlinx.coroutines.MainScope

/**
 * Lobby: host or join a game, nearby (Google Nearby Connections) or online
 * (through the relay server). The host's phone runs the game; empty seats
 * are played by computers.
 */
class MultiplayerActivity : AppCompatActivity() {

	private lateinit var nameField: EditText
	private lateinit var relayField: EditText
	private lateinit var codeField: EditText
	private lateinit var choices: LinearLayout
	private lateinit var lobbyPanel: LinearLayout
	private lateinit var status: TextView
	private lateinit var players: TextView
	private lateinit var hostList: LinearLayout
	private lateinit var startButton: Button

	private var hostLobby: HostLobby? = null
	private var clientLobby: ClientLobby? = null
	private var nearbyHost: NearbyHost? = null
	private var nearbyClient: NearbyClient? = null
	private var relayHost: RelayHost? = null
	private var relayClient: RelayClient? = null
	/** True once the session was handed to the game screen (so leave it open). */
	private var handedOff = false

	private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

	private var afterPermission: (() -> Unit)? = null
	private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
		val next = afterPermission
		afterPermission = null
		if (result.values.all { it }) next?.invoke() else showStatus(getString(R.string.mp_permissions))
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		title = getString(R.string.mp_title)
		setContentView(buildLayout())
	}

	override fun onDestroy() {
		if (!handedOff) leave()
		super.onDestroy()
	}

	// ── layout ────────────────────────────────────────────────────────────────

	private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

	private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
		text = label
		setOnClickListener { onClick() }
	}

	private fun heading(res: Int) = TextView(this).apply {
		setText(res)
		textSize = 18f
		setPadding(0, dp(16), 0, dp(4))
	}

	private fun field(hint: Int, value: String?, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
		setHint(hint)
		setText(value)
		inputType = type
		isSingleLine = true
	}

	private fun buildLayout(): View {
		nameField = field(R.string.mp_name_hint, prefs.getString(PREF_NAME, null)).apply {
			filters = arrayOf(InputFilter.LengthFilter(Wire.MAX_NAME))
		}
		relayField = field(R.string.mp_relay_hint, prefs.getString(PREF_RELAY, null), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
		codeField = field(R.string.mp_code_hint, null, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)

		choices = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			addView(nameField)
			addView(heading(R.string.mp_nearby))
			addView(button(getString(R.string.mp_host_nearby)) { withNearbyPermissions(::hostNearby) })
			addView(button(getString(R.string.mp_join_nearby)) { withNearbyPermissions(::joinNearby) })
			addView(heading(R.string.mp_online))
			addView(relayField)
			addView(button(getString(R.string.mp_host_online), ::hostOnline))
			addView(codeField)
			addView(button(getString(R.string.mp_join_online), ::joinOnline))
		}

		status = TextView(this).apply { textSize = 16f }
		players = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
		hostList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
		startButton = button(getString(R.string.mp_start), ::startGame).apply { visibility = View.GONE }
		lobbyPanel = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			visibility = View.GONE
			addView(status)
			addView(hostList)
			addView(players)
			addView(startButton)
			addView(button(getString(R.string.mp_leave)) {
				leave()
				showChoices()
			})
		}

		val content = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(24), dp(16), dp(24), dp(16))
			addView(choices)
			addView(lobbyPanel)
		}
		return ScrollView(this).apply {
			fitsSystemWindows = true
			addView(content)
		}
	}

	private fun showChoices() {
		choices.visibility = View.VISIBLE
		lobbyPanel.visibility = View.GONE
		hostList.removeAllViews()
		players.text = ""
		startButton.visibility = View.GONE
	}

	private fun showLobby(statusText: String, host: Boolean) {
		choices.visibility = View.GONE
		lobbyPanel.visibility = View.VISIBLE
		startButton.visibility = if (host) View.VISIBLE else View.GONE
		showStatus(statusText)
	}

	private fun showStatus(text: String) {
		status.text = text
	}

	private fun showError(msg: String) {
		showStatus(getString(R.string.mp_error, msg))
	}

	private fun showPlayers(names: List<String?>) {
		val lines = Seat.entries.joinToString("\n") { s ->
			"${CardText.seatName(this, s)}: ${names[s.index] ?: getString(R.string.mp_computer)}"
		}
		players.text = getString(R.string.mp_players, lines)
	}

	private fun myName(): String {
		val n = Wire.cleanName(nameField.text.toString())
		prefs.edit().putString(PREF_NAME, n).apply()
		return n
	}

	private fun relayUrl(): String? {
		val url = relayField.text.toString().trim()
		if (!(url.startsWith("ws://") || url.startsWith("wss://"))) {
			showChoices()
			relayField.error = getString(R.string.mp_need_name_url)
			return null
		}
		prefs.edit().putString(PREF_RELAY, url).apply()
		return url
	}

	// ── nearby ────────────────────────────────────────────────────────────────

	private fun withNearbyPermissions(then: () -> Unit) {
		val missing = nearbyPermissions().filter {
			ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
		}
		if (missing.isEmpty()) return then()
		afterPermission = then
		permissionRequest.launch(missing.toTypedArray())
	}

	private fun hostNearby() {
		val name = myName()
		val lobby = newHostLobby(name)
		val host = NearbyHost(this, name)
		nearbyHost = host
		host.onLink = lobby::accept
		host.onError = ::showError
		host.start()
		showLobby(getString(R.string.mp_advertising), host = true)
	}

	private fun joinNearby() {
		val name = myName()
		val client = NearbyClient(this, name)
		nearbyClient = client
		val found = HashMap<String, Button>()
		client.onFound = { id, hostName ->
			if (id !in found) {
				val b = button(getString(R.string.mp_join_host, Wire.cleanName(hostName))) {
					hostList.removeAllViews()
					showStatus(getString(R.string.mp_connecting))
					client.connect(id)
				}
				found[id] = b
				hostList.addView(b)
			}
		}
		client.onLost = { id -> found.remove(id)?.let(hostList::removeView) }
		client.onError = ::showError
		client.onConnected = { link -> joined(link, name) }
		client.startDiscovery()
		showLobby(getString(R.string.mp_searching), host = false)
	}

	// ── online ────────────────────────────────────────────────────────────────

	private fun hostOnline() {
		val url = relayUrl() ?: return
		val name = myName()
		val lobby = newHostLobby(name)
		val host = RelayHost(url)
		relayHost = host
		host.onRoom = { code -> showStatus(getString(R.string.mp_room_code, code)) }
		host.onLink = lobby::accept
		host.onError = ::showError
		showLobby(getString(R.string.mp_connecting), host = true)
	}

	private fun joinOnline() {
		val url = relayUrl() ?: return
		val code = codeField.text.toString().trim()
		if (code.isEmpty()) {
			codeField.error = getString(R.string.mp_need_code)
			return
		}
		val name = myName()
		val client = RelayClient(url, code)
		relayClient = client
		client.onConnected = { link -> joined(link, name) }
		client.onError = ::showError
		showLobby(getString(R.string.mp_connecting), host = false)
	}

	// ── lobby ─────────────────────────────────────────────────────────────────

	private fun newHostLobby(name: String): HostLobby {
		val lobby = HostLobby(name)
		hostLobby = lobby
		lobby.onChanged = ::showPlayers
		showPlayers(lobby.names)
		return lobby
	}

	private fun joined(link: Link, name: String) {
		val lobby = ClientLobby(link, name)
		clientLobby = lobby
		showStatus(getString(R.string.mp_waiting_host))
		lobby.onLobby = ::showPlayers
		lobby.onEnded = { reason -> showError(reason ?: getString(R.string.mp_host_lost)) }
		lobby.onStart = ::clientStarted
	}

	private fun startGame() {
		val lobby = hostLobby ?: return
		nearbyHost?.stopAccepting()
		relayHost?.stopAccepting()
		val scope = MainScope()
		val session = lobby.start(PrefsConfig(this), scope)
		val nh = nearbyHost
		val rh = relayHost
		launchGame(Multiplayer.Handoff(session, scope) {
			nh?.close()
			rh?.close()
		})
	}

	private fun clientStarted(session: ClientSession) {
		val nc = nearbyClient
		val rc = relayClient
		launchGame(Multiplayer.Handoff(session, null) {
			nc?.close()
			rc?.close()
		})
	}

	private fun launchGame(h: Multiplayer.Handoff) {
		handedOff = true
		Multiplayer.handOff(h)
		startActivity(Intent(this, GameActivity::class.java)
			.putExtra(GameActivity.STARTUP_MODE, GameActivity.STARTUP_MODE_MULTIPLAYER))
		finish()
	}

	private fun leave() {
		hostLobby?.close()
		clientLobby?.leave()
		nearbyHost?.close()
		nearbyClient?.close()
		relayHost?.close()
		relayClient?.close()
		hostLobby = null
		clientLobby = null
		nearbyHost = null
		nearbyClient = null
		relayHost = null
		relayClient = null
	}

	companion object {
		private const val PREF_NAME = "player_name"
		private const val PREF_RELAY = "relay_url"
	}
}
