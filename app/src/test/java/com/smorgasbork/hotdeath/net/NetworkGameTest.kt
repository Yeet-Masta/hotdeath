package com.smorgasbork.hotdeath.net

import com.smorgasbork.hotdeath.engine.CardColor
import com.smorgasbork.hotdeath.engine.CardDeck
import com.smorgasbork.hotdeath.engine.CardType
import com.smorgasbork.hotdeath.engine.NoGoodReason
import com.smorgasbork.hotdeath.engine.DeckType
import com.smorgasbork.hotdeath.engine.Game
import com.smorgasbork.hotdeath.engine.GameEvent
import com.smorgasbork.hotdeath.engine.GameMessage
import com.smorgasbork.hotdeath.engine.Prompt
import com.smorgasbork.hotdeath.engine.Seat
import com.smorgasbork.hotdeath.engine.TestConfig
import kotlinx.coroutines.CoroutineScope
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
 * Plays from what a [TableView] shows: tries its cards one by one, then
 * draws or passes; answers every prompt; presses Next round.
 */
private class Bot(private val session: GameSession, private val deck: CardDeck) {
	private val tried = HashSet<Int>()
	private var turnKey: Any? = null
	var updates = 0
	var lastView: TableView? = null

	fun onUpdate(v: TableView, e: ViewEvent?) {
		updates++
		lastView = v
		// Every update must survive the wire unchanged.
		val back = Wire.decode(Wire.encode(WireMessage.Update(v, e))) as WireMessage.Update
		check(back.view == v && back.event == e) { "wire round trip changed an update" }

		if (e is ViewEvent.Message && e.message is GameMessage.CardNoGood) return next(v)
		when (val p = v.prompt) {
			Prompt.NumCardsToDeal -> return session.act(PlayerAction.Deal(7))
			Prompt.Color -> return session.act(PlayerAction.Color(CardColor.BLUE))
			is Prompt.Victim -> return session.act(PlayerAction.Victim(p.options.first()))
			null -> {}
		}
		if (v.showNextRound) return session.act(PlayerAction.NextRound)
		if (v.showMenu) {
			val key = Triple(v.currPlayer, v.player(v.me).size, v.discard.size)
			if (key != turnKey) {
				turnKey = key
				tried.clear()
				next(v)
			}
		}
	}

	private fun next(v: TableView) {
		if (!v.myTurn) return
		val mine = v.player(v.me).let { it.table + it.held }
		check(mine.all { it >= 0 }) { "a player cannot see their own card" }
		val c = mine.firstOrNull { it !in tried && deck[it] != null }
		when {
			c != null -> {
				tried += c
				session.act(PlayerAction.Play(c))
			}
			v.canDraw -> session.act(PlayerAction.Draw)
			else -> session.act(PlayerAction.Pass)
		}
	}
}

class NetworkGameTest {

	private class Table(scope: CoroutineScope, deckType: DeckType, seed: Int) {
		val lobby = HostLobby("Host")
		val clients = HashMap<Seat, ClientSession>()
		val bots = HashMap<Seat, Bot>()
		val links = HashMap<Seat, MemLink>()
		lateinit var host: HostSession
		val deck = CardDeck(deckType)
		val config = TestConfig(deckType)

		init {
			for (name in listOf("North", "West")) {
				val (hostEnd, clientEnd) = MemLink.pair(scope)
				lobby.accept(hostEnd)
				val cl = ClientLobby(clientEnd, name)
				cl.onStart = { s ->
					clients[s.me] = s
					links[s.me] = clientEnd
					bots[s.me] = Bot(s, deck).also { b -> s.listener = SessionListener(b::onUpdate) }
				}
			}
		}

		fun start(scope: CoroutineScope, seed: Int) {
			host = lobby.start(config, scope, pacing = false)
			val b = Bot(host, deck)
			bots[Seat.SOUTH] = b
			host.listener = SessionListener(b::onUpdate)
			host.start()
		}
	}

	private fun runTable(deckType: DeckType, seed: Int, block: suspend (Table) -> Unit) = runBlocking {
		val scope = CoroutineScope(coroutineContext)
		val t = Table(scope, deckType, seed)
		repeat(20) { yield() } // let hellos and lobby updates arrive
		assertEquals(listOf("Host", "West", "North", null), t.lobby.names)
		block(t)
	}

	@Test
	fun threePeopleAndAComputerPlayAWholeGame() = runTable(DeckType.STANDARD, 1) { t ->
		t.start(CoroutineScope(kotlin.coroutines.coroutineContext), 1)
		val game = t.host.game
		assertEquals(setOf(Seat.SOUTH, Seat.NORTH, Seat.WEST), game.players.filter { it.isHuman }.map { it.seat }.toSet())
		try {
			withTimeout(60_000) { while (!game.gameOver) yield() }
		} catch (e: Exception) {
			val h = game.humanController(game.currPlayer!!.seat)
			println("STALL curr=${game.currPlayer} prompt=${game.pendingPrompt}/${game.promptSeat} awaiting=${h?.awaitingTurn} waiting=${game.waitingToStartRound} showNext=${game.showNextRoundButton} menu=${game.showMenu} pen=${game.penalty.type}")
			for ((s, b) in t.bots) println("  $s updates=${b.updates} menu=${b.lastView?.showMenu} curr=${b.lastView?.currPlayer} prompt=${b.lastView?.prompt}")
			throw e
		}
		repeat(50) { yield() }
		for (seat in listOf(Seat.NORTH, Seat.WEST)) {
			val v = t.bots[seat]!!.lastView!!
			assertTrue(v.gameOver)
			assertEquals(game.gameWinner, v.gameWinner)
			assertTrue(t.bots[seat]!!.updates > 100)
		}
	}

	@Test
	fun aPlayerWhoLeavesIsReplacedByAComputer() = runTable(DeckType.HYBRID, 2) { t ->
		t.start(CoroutineScope(kotlin.coroutines.coroutineContext), 2)
		val game = t.host.game
		var left: SessionStatus? = null
		t.host.onStatus = { left = it }
		withTimeout(60_000) { while ((t.bots[Seat.WEST]?.updates ?: 0) < 50) yield() }
		t.clients[Seat.WEST]!!.close()
		withTimeout(120_000) { while (!game.gameOver) yield() }
		assertEquals(SessionStatus.PlayerLeft(Seat.WEST, "West"), left)
		assertTrue(!game.player(Seat.WEST).isHuman)
	}

	@Test
	fun aPlayerWhoDropsCanRejoinTheirSeat() = runTable(DeckType.HYBRID, 3) { t ->
		t.start(CoroutineScope(kotlin.coroutines.coroutineContext), 3)
		val game = t.host.game
		val statuses = ArrayList<SessionStatus>()
		t.host.onStatus = { statuses += it }
		withTimeout(60_000) { while ((t.bots[Seat.WEST]?.updates ?: 0) < 30) yield() }
		val token = t.clients[Seat.WEST]!!.rejoinToken!!
		t.clients[Seat.WEST]!!.close()
		withTimeout(10_000) { while (game.player(Seat.WEST).isHuman) yield() }

		val scope = CoroutineScope(kotlin.coroutines.coroutineContext)
		// A stranger without the token is turned away.
		val (h1, c1) = MemLink.pair(scope)
		t.host.accept(h1)
		var refused: String? = null
		ClientLobby(c1, "Stranger", "not-a-token").onEnded = { refused = it }
		withTimeout(10_000) { while (refused == null) yield() }
		assertEquals("game started", refused)

		// WEST comes back with the token and gets the seat back.
		val (h2, c2) = MemLink.pair(scope)
		t.host.accept(h2)
		var back: ClientSession? = null
		ClientLobby(c2, "West", token).onStart = { s -> back = s }
		withTimeout(10_000) { while (back == null) yield() }
		assertEquals(Seat.WEST, back!!.me)
		assertTrue(game.player(Seat.WEST).isHuman)
		assertTrue(SessionStatus.PlayerBack(Seat.WEST, "West") in statuses)
		val b = Bot(back!!, t.deck)
		back!!.listener = SessionListener(b::onUpdate)
		withTimeout(120_000) { while (!game.gameOver) yield() }
		assertTrue(b.updates > 10)
	}

	@Test
	fun reasonsAndTokensSurviveTheWire() {
		val msgs = listOf(
			GameMessage.CardNoGood(Seat.WEST, NoGoodReason.NotYourTurn),
			GameMessage.CardNoGood(Seat.WEST, NoGoodReason.UnderAttack(4)),
			GameMessage.CardNoGood(Seat.WEST, NoGoodReason.Aids),
			GameMessage.CardNoGood(Seat.WEST, NoGoodReason.Shitter),
			GameMessage.CardNoGood(Seat.WEST, NoGoodReason.HoldsColor(CardColor.RED)),
			GameMessage.CardNoGood(Seat.WEST, NoGoodReason.NoMatch(CardColor.GREEN, CardType.RED_7)),
			GameMessage.CardNoGood(Seat.WEST, NoGoodReason.NoMatch(null, null)),
			GameMessage.CardNoGood(Seat.WEST),
			GameMessage.TimedOut(Seat.EAST),
		)
		for (m in msgs) {
			val g = Game.newGame(TestConfig(DeckType.STANDARD), Random(1), pacing = false)
			val u = WireMessage.Update(g.viewFor(Seat.SOUTH), ViewEvent.Message(m))
			assertEquals(u, Wire.decode(Wire.encode(u)))
		}
		for (m in listOf(WireMessage.Hello(Wire.PROTOCOL, "A", "tok"), WireMessage.Hello(Wire.PROTOCOL, "A"),
				WireMessage.Start(Seat.EAST, "tok"), WireMessage.Start(Seat.EAST))) {
			assertEquals(m, Wire.decode(Wire.encode(m)))
		}
		// An update whose event this version does not know still updates the table.
		val g = Game.newGame(TestConfig(DeckType.STANDARD), Random(1), pacing = false)
		val text = Wire.encode(WireMessage.Update(g.viewFor(Seat.SOUTH), null)).replaceFirst("{", "{\"e\":{\"k\":\"future\"},")
		assertEquals(null, (Wire.decode(text) as WireMessage.Update).event)
	}

	@Test
	fun viewsNeverRevealAnotherPlayersHiddenCards() = runBlocking {
		val g = Game.newGame(TestConfig(DeckType.EXTENDED, humanSeats = setOf(Seat.SOUTH, Seat.NORTH)), Random(5), pacing = false)
		var checked = 0
		g.listener = com.smorgasbork.hotdeath.engine.GameListener { e ->
			for (viewer in Seat.entries) {
				val v = g.viewFor(viewer)
				for (p in g.players) {
					val pv = v.player(p.seat)
					val visible = if (p.seat == viewer) p.hand.heldCards else p.hand.heldCards.filter { p.hand.isVisible(it) }
					assertEquals(visible.map { it.deckIndex }.toSet(), pv.held.filter { it >= 0 }.toSet())
					assertEquals(p.hand.heldCards.size, pv.held.size)
				}
				if (viewer != g.promptSeat) assertEquals(null, v.prompt)
				val ev = g.eventFor(e, viewer)
				if (e is GameEvent.CheatSwap && e.seat != viewer) assertEquals(null, ev)
			}
			checked++
			if (g.waitingToStartRound) g.requestNextRound()
			// Answer for the two people so the game moves on.
			for (s in listOf(Seat.SOUTH, Seat.NORTH)) {
				when (g.pendingPrompt) {
					Prompt.NumCardsToDeal -> g.submitNumCardsToDeal(s, 7)
					Prompt.Color -> g.submitColor(s, CardColor.RED)
					is Prompt.Victim -> g.submitVictim(s, (g.pendingPrompt as Prompt.Victim).options.first())
					null -> {}
				}
			}
		}
		for (s in listOf(Seat.SOUTH, Seat.NORTH)) g.handOverToComputer(s) // computers decide turns
		withTimeout(120_000) { g.run() }
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
		val g = Game.newGame(TestConfig(DeckType.STANDARD, humanSeats = setOf(Seat.SOUTH, Seat.NORTH)), Random(3), pacing = false)
		// NORTH asks to play SOUTH's cards and draw on SOUTH's turn: nothing happens.
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
