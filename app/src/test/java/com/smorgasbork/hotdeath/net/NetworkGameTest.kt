package com.smorgasbork.hotdeath.net

import com.smorgasbork.hotdeath.engine.CardColor
import com.smorgasbork.hotdeath.engine.Decision
import com.smorgasbork.hotdeath.engine.Game
import com.smorgasbork.hotdeath.engine.GameEvent
import com.smorgasbork.hotdeath.engine.Prompt
import com.smorgasbork.hotdeath.engine.Seat
import com.smorgasbork.hotdeath.engine.TestConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
/** Two ends of an in-memory connection; delivery is asynchronous, like a real link. */
private class MemLink(private val scope: CoroutineScope) : Link {
	lateinit var peer: MemLink
	override var onMessage: ((String) -> Unit)? = null
	override var onClosed: (() -> Unit)? = null
	var open = true
	val sent = ArrayList<String>()

	override fun send(text: String) {
		if (!open) return
		sent += text
		scope.launch { if (peer.open) peer.onMessage?.invoke(text) }
	}

	override fun close() {
		if (!open) return
		open = false
		scope.launch {
			if (peer.open) {
				peer.open = false
				peer.onClosed?.invoke()
			}
		}
	}

	companion object {
		fun pair(scope: CoroutineScope): Pair<MemLink, MemLink> {
			val a = MemLink(scope)
			val b = MemLink(scope)
			a.peer = b
			b.peer = a
			return a to b
		}
	}
}

/**
 * Plays from what a [TableView] shows: plays the first card it may, else
 * draws, takes or passes; answers every prompt; presses Next round.
 */
private class Bot(private val session: GameSession) {
	private var lastKey: Any? = null
	var updates = 0
	var lastView: TableView? = null

	fun onUpdate(v: TableView, e: ViewEvent?) {
		updates++
		lastView = v
		// Every update must survive the wire unchanged.
		val back = Wire.decode(Wire.encode(WireMessage.Update(v, e))) as WireMessage.Update
		check(back.view == v && back.event == e) { "wire round trip changed an update" }
		check(v.player(v.me).held.all { it >= 0 }) { "a player cannot see their own card" }

		when (val p = v.prompt) {
			Prompt.Color -> return session.act(PlayerAction.Color(CardColor.BLUE))
			is Prompt.Victim -> return session.act(PlayerAction.Victim(if (p.optional) null else p.options.first()))
			is Prompt.ChooseCard -> return session.act(PlayerAction.ChooseCard(if (p.optional) null else p.options.first()))
			null -> {}
		}
		if (v.showNextRound) return session.act(PlayerAction.NextRound)
		if (v.decision == Decision.NONE || v.jumpIn) {
			// A new decision may look exactly like the last one (Luck can make a draw draw nothing).
			lastKey = null
			return
		}
		// Act once per decision.
		val key = listOf(v.decision, v.currPlayer, v.player(v.me).held, v.topCard, v.attack)
		if (key == lastKey) return
		lastKey = key
		when {
			v.legal.isNotEmpty() -> session.act(PlayerAction.Play(v.legal.first()))
			v.canDraw -> session.act(PlayerAction.Draw)
			else -> session.act(PlayerAction.Pass)
		}
	}
}

class NetworkGameTest {

	private class Table(scope: CoroutineScope) {
		val lobby = HostLobby("Host")
		val clients = HashMap<Seat, ClientSession>()
		val bots = HashMap<Seat, Bot>()
		lateinit var host: HostSession
		val config = TestConfig()

		init {
			for (name in listOf("North", "West")) {
				val (hostEnd, clientEnd) = MemLink.pair(scope)
				lobby.accept(hostEnd)
				val cl = ClientLobby(clientEnd, name)
				cl.onStart = { s ->
					clients[s.me] = s
					bots[s.me] = Bot(s).also { b -> s.listener = SessionListener(b::onUpdate) }
				}
			}
		}

		fun start(scope: CoroutineScope) {
			host = lobby.start(config, scope, pacing = false)
			val b = Bot(host)
			bots[Seat.SOUTH] = b
			host.listener = SessionListener(b::onUpdate)
			host.start()
		}
	}

	private fun runTable(block: suspend (Table) -> Unit) = runBlocking {
		val scope = CoroutineScope(coroutineContext)
		val t = Table(scope)
		repeat(20) { yield() } // let hellos and lobby updates arrive
		assertEquals(listOf("Host", "West", "North", null), t.lobby.names)
		block(t)
		t.host.close()
		for (c in t.clients.values) c.close()
		coroutineContext.cancelChildren()
	}

	private fun Game.roundsPlayed() = players.sumOf { it.wins }

	@Test
	fun threePeopleAndAComputerPlayRounds() = runTable { t ->
		t.start(CoroutineScope(kotlin.coroutines.coroutineContext))
		val game = t.host.game
		assertEquals(setOf(Seat.SOUTH, Seat.NORTH, Seat.WEST), game.players.filter { it.isHuman }.map { it.seat }.toSet())
		try {
			withTimeout(60_000) { while (game.roundsPlayed() < 2) yield() }
		} catch (e: Exception) {
			println("STALL curr=${game.currPlayer} prompt=${game.pendingPrompt}/${game.promptSeat} deciding=${game.deciding} ${game.decision}")
			for ((s, b) in t.bots) println("  $s updates=${b.updates} decision=${b.lastView?.decision} legal=${b.lastView?.legal}")
			throw e
		}
		repeat(50) { yield() }
		for (seat in listOf(Seat.NORTH, Seat.WEST)) assertTrue(t.bots[seat]!!.updates > 100)
	}

	@Test
	fun aPlayerWhoLeavesIsReplacedByAComputer() = runTable { t ->
		t.start(CoroutineScope(kotlin.coroutines.coroutineContext))
		val game = t.host.game
		var left: SessionStatus? = null
		t.host.onStatus = { left = it }
		withTimeout(60_000) { while ((t.bots[Seat.WEST]?.updates ?: 0) < 50) yield() }
		t.clients[Seat.WEST]!!.close()
		withTimeout(120_000) { while (game.roundsPlayed() < 2) yield() }
		assertEquals(SessionStatus.PlayerLeft(Seat.WEST, "West"), left)
		assertTrue(!game.player(Seat.WEST).isHuman)
	}

	@Test
	fun viewsNeverRevealAnotherPlayersHiddenCards() = runBlocking {
		val g = Game.newGame(TestConfig(humanSeats = setOf(Seat.SOUTH, Seat.NORTH)), Random(5), pacing = false)
		var checked = 0
		var job: Job? = null
		g.listener = com.smorgasbork.hotdeath.engine.GameListener { e ->
			for (viewer in Seat.entries) {
				val v = g.viewFor(viewer)
				for (p in g.players) {
					val pv = v.player(p.seat)
					val seen = g.player(viewer).peeked
					val visible = p.hand.cards.filter { p.seat == viewer || p.hand.isVisible(it) || it in seen }
					assertEquals(visible.map { it.deckIndex }.toSet(), pv.held.filter { it >= 0 }.toSet())
					assertEquals(p.hand.size, pv.held.size)
				}
				if (viewer != g.promptSeat) assertEquals(null, v.prompt)
				val ev = g.eventFor(e, viewer)
				if (e is GameEvent.Message && e.message.to != null && e.message.to != viewer) assertEquals(null, ev)
			}
			checked++
			if (g.waitingToStartRound) {
				if (g.players.sumOf { it.wins } >= 3) job?.cancel() else g.requestNextRound()
			}
		}
		for (s in listOf(Seat.SOUTH, Seat.NORTH)) g.handOverToComputer(s) // computers decide
		withTimeout(120_000) {
			job = launch { g.run() }
			job!!.join()
		}
		assertTrue(checked > 100)
	}

	@Test
	fun lobbyRefusesAFifthPlayerAndOldClients() = runBlocking {
		val scope = CoroutineScope(coroutineContext)
		val lobby = HostLobby("Host")
		val ends = (1..4).map { i ->
			val (h, c) = MemLink.pair(scope)
			lobby.accept(h)
			ClientLobby(c, "P$i")
			c
		}
		val (h, old) = MemLink.pair(scope)
		lobby.accept(h)
		old.send(Wire.encode(WireMessage.Hello(Wire.PROTOCOL + 1, "Old")))
		repeat(30) { yield() }
		assertEquals(4, lobby.playerCount)
		assertTrue(!ends[3].open)
		assertTrue(!old.open)
		lobby.close()
	}

	@Test
	fun clientsCannotActForOtherSeats() {
		val g = Game.newGame(TestConfig(humanSeats = setOf(Seat.SOUTH, Seat.NORTH)), Random(3), pacing = false)
		// NORTH asks to draw and choose a color for SOUTH: nothing happens.
		g.apply(Seat.NORTH, PlayerAction.Draw)
		g.apply(Seat.NORTH, PlayerAction.Color(CardColor.RED))
		assertEquals(null, g.pendingPrompt)
	}

	@Test
	fun namesAreCleaned() {
		assertEquals("Bob", Wire.cleanName("  B\u0000o\nb  "))
		assertEquals("Player", Wire.cleanName("   "))
		assertEquals(Wire.MAX_NAME, Wire.cleanName("x".repeat(100)).length)
	}
}
