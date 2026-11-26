package com.smorgasbork.hotdeath.net

import com.smorgasbork.hotdeath.engine.Game
import com.smorgasbork.hotdeath.engine.GameConfig
import com.smorgasbork.hotdeath.engine.GameEvent
import com.smorgasbork.hotdeath.engine.GameListener
import com.smorgasbork.hotdeath.engine.Seat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A two-way text channel to one other device. Implementations deliver
 * [onMessage] and [onClosed] on the main thread, and call [onClosed] at most
 * once (not after a local [close]).
 */
interface Link {
	fun send(text: String)
	fun close()
	var onMessage: ((String) -> Unit)?
	var onClosed: (() -> Unit)?
}

fun interface SessionListener {
	fun onUpdate(view: TableView, event: ViewEvent?)
}

sealed interface SessionStatus {
	/** A remote player disconnected; a computer now plays [seat]. */
	data class PlayerLeft(val seat: Seat, val name: String?) : SessionStatus
	/** The connection to the host is gone. */
	data class HostLost(val reason: String?) : SessionStatus
}

/** The game as one player at this device sees and plays it. */
interface GameSession {
	val me: Seat
	/** The latest table, or null before the first update. */
	val view: TableView?
	var listener: SessionListener?
	var onStatus: ((SessionStatus) -> Unit)?
	/** Only a game with no remote players can be saved and resumed. */
	val savable: Boolean
	fun start()
	fun act(a: PlayerAction)
	fun pause()
	fun resume()
	fun close()
}

/**
 * Runs the game on this device. With no [remotes] it is a single-player game;
 * otherwise every remote seat is played over its [Link] and receives only
 * its own [TableView].
 */
class HostSession(
	val game: Game,
	private val scope: CoroutineScope,
	override val me: Seat = Seat.SOUTH,
	private val names: List<String?> = List(4) { null },
	remotes: Map<Seat, Link> = emptyMap(),
) : GameSession {

	private val live = LinkedHashMap(remotes)
	private var job: Job? = null
	override val savable = remotes.isEmpty()
	override var view: TableView? = null
		private set
	override var listener: SessionListener? = null
	override var onStatus: ((SessionStatus) -> Unit)? = null

	init {
		game.listener = GameListener(::publish)
		for ((seat, link) in remotes) {
			link.onMessage = { text -> fromRemote(seat, text) }
			link.onClosed = { left(seat) }
		}
	}

	override fun start() {
		if (job != null) return
		publish(null)
		job = scope.launch { game.run() }
	}

	override fun act(a: PlayerAction) = game.apply(me, a)

	override fun pause() = game.pause()

	override fun resume() = game.unpause()

	override fun close() {
		job?.cancel()
		game.listener = null
		val bye = Wire.encode(WireMessage.Bye("host left"))
		for (link in live.values) {
			link.send(bye)
			link.close()
		}
		live.clear()
	}

	private fun publish(e: GameEvent?) {
		val v = game.viewFor(me, names)
		view = v
		listener?.onUpdate(v, e?.let { game.eventFor(it, me) })
		for ((seat, link) in live) {
			link.send(Wire.encode(WireMessage.Update(game.viewFor(seat, names), e?.let { game.eventFor(it, seat) })))
		}
	}

	private fun fromRemote(seat: Seat, text: String) {
		when (val m = runCatching { Wire.decode(text) }.getOrNull()) {
			is WireMessage.Action -> game.apply(seat, m.action)
			is WireMessage.Bye -> {
				live[seat]?.close()
				left(seat)
			}
			// Garbage or a message that makes no sense mid-game: ignore it.
			else -> {}
		}
	}

	private fun left(seat: Seat) {
		if (live.remove(seat) == null) return
		game.handOverToComputer(seat)
		onStatus?.invoke(SessionStatus.PlayerLeft(seat, names[seat.index]))
	}
}

/** Plays a game hosted on another device. */
class ClientSession(private val link: Link, override val me: Seat) : GameSession {

	override var view: TableView? = null
		private set
	override var listener: SessionListener? = null
	override var onStatus: ((SessionStatus) -> Unit)? = null
	override val savable = false
	private var closed = false

	init {
		link.onMessage = ::onText
		link.onClosed = { lost(null) }
	}

	override fun start() {}

	override fun act(a: PlayerAction) {
		if (!closed) link.send(Wire.encode(WireMessage.Action(a)))
	}

	override fun pause() {}

	override fun resume() {}

	override fun close() {
		if (closed) return
		closed = true
		link.send(Wire.encode(WireMessage.Bye("left")))
		link.close()
	}

	private fun onText(text: String) {
		when (val m = runCatching { Wire.decode(text) }.getOrNull()) {
			is WireMessage.Update -> {
				// A view for another seat would mean a confused host; ignore it.
				if (m.view.me != me) return
				view = m.view
				listener?.onUpdate(m.view, m.event)
			}
			is WireMessage.Bye -> {
				link.close()
				lost(m.reason)
			}
			else -> {}
		}
	}

	private fun lost(reason: String?) {
		if (closed) return
		closed = true
		onStatus?.invoke(SessionStatus.HostLost(reason))
	}
}

/** Host settings with the seats people play; cheats and open hands are off in multiplayer. */
class MultiplayerConfig(private val base: GameConfig, override val humanSeats: Set<Seat>) : GameConfig by base {
	override val cheatLevel: Int get() = 0
	override val faceUp: Boolean get() = false
}

/** Collects players before a hosted game starts. The host always plays SOUTH. */
class HostLobby(private val hostName: String) {

	private val seated = LinkedHashMap<Seat, Pair<String, Link>>()
	private val waiting = HashSet<Link>()

	/** Seat-indexed names; null for a seat a computer will play. */
	var onChanged: ((List<String?>) -> Unit)? = null

	val names: List<String?>
		get() = Seat.entries.map { if (it == Seat.SOUTH) hostName else seated[it]?.first }

	val playerCount: Int get() = 1 + seated.size

	fun accept(link: Link) {
		waiting += link
		link.onMessage = { text -> onHello(link, text) }
		link.onClosed = { drop(link) }
	}

	private fun onHello(link: Link, text: String) {
		if (link !in waiting) return
		val m = runCatching { Wire.decode(text) }.getOrNull()
		if (m !is WireMessage.Hello) return
		waiting -= link
		if (m.version != Wire.PROTOCOL) return refuse(link, "incompatible version")
		val seat = JOIN_ORDER.firstOrNull { it !in seated } ?: return refuse(link, "game is full")
		seated[seat] = m.name to link
		broadcast()
	}

	private fun refuse(link: Link, reason: String) {
		link.send(Wire.encode(WireMessage.Bye(reason)))
		link.close()
	}

	private fun drop(link: Link) {
		waiting -= link
		val seat = seated.entries.firstOrNull { it.value.second === link }?.key ?: return
		seated.remove(seat)
		broadcast()
	}

	private fun broadcast() {
		val n = names
		val msg = Wire.encode(WireMessage.Lobby(n))
		seated.values.forEach { it.second.send(msg) }
		onChanged?.invoke(n)
	}

	/** Starts the game; empty seats are played by computers. */
	fun start(config: GameConfig, scope: CoroutineScope, pacing: Boolean = true): HostSession {
		val remotes = seated.mapValues { it.value.second }
		waiting.forEach { refuse(it, "game started") }
		waiting.clear()
		val game = Game.newGame(MultiplayerConfig(config, setOf(Seat.SOUTH) + remotes.keys), pacing = pacing)
		val session = HostSession(game, scope, Seat.SOUTH, names, remotes)
		for ((seat, link) in remotes) link.send(Wire.encode(WireMessage.Start(seat)))
		seated.clear()
		return session
	}

	fun close() {
		(waiting + seated.values.map { it.second }).forEach { refuse(it, "host left") }
		waiting.clear()
		seated.clear()
	}

	companion object {
		/** Opponents fill the seat across first, then the sides. */
		val JOIN_ORDER = listOf(Seat.NORTH, Seat.WEST, Seat.EAST)
	}
}

/** A client waiting in a host's lobby. */
class ClientLobby(private val link: Link, name: String) {

	var onLobby: ((List<String?>) -> Unit)? = null
	var onStart: ((ClientSession) -> Unit)? = null
	var onEnded: ((String?) -> Unit)? = null
	private var done = false

	init {
		link.onMessage = ::onText
		link.onClosed = { end(null) }
		link.send(Wire.encode(WireMessage.Hello(Wire.PROTOCOL, Wire.cleanName(name))))
	}

	private fun onText(text: String) {
		when (val m = runCatching { Wire.decode(text) }.getOrNull()) {
			is WireMessage.Lobby -> onLobby?.invoke(m.names)
			is WireMessage.Start -> {
				done = true
				onStart?.invoke(ClientSession(link, m.seat))
			}
			is WireMessage.Bye -> {
				link.close()
				end(m.reason)
			}
			else -> {}
		}
	}

	private fun end(reason: String?) {
		if (done) return
		done = true
		onEnded?.invoke(reason)
	}

	fun leave() {
		if (done) return
		done = true
		link.send(Wire.encode(WireMessage.Bye("left")))
		link.close()
	}
}
