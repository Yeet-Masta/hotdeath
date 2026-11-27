package com.smorgasbork.hotdeath.net

import com.smorgasbork.hotdeath.engine.AttackKind
import com.smorgasbork.hotdeath.engine.CardColor
import com.smorgasbork.hotdeath.engine.CardType
import com.smorgasbork.hotdeath.engine.Decision
import com.smorgasbork.hotdeath.engine.Direction
import com.smorgasbork.hotdeath.engine.GameMessage
import com.smorgasbork.hotdeath.engine.Msg
import com.smorgasbork.hotdeath.engine.Prompt
import com.smorgasbork.hotdeath.engine.Reason
import com.smorgasbork.hotdeath.engine.Seat
import com.smorgasbork.hotdeath.engine.Status
import org.json.JSONArray
import org.json.JSONObject

/** Messages between a host and its clients. */
sealed interface WireMessage {
	/** Client → host, first message. */
	data class Hello(val version: Int, val name: String) : WireMessage
	/** Host → clients: who is in the lobby, by seat (null = computer / empty). */
	data class Lobby(val names: List<String?>) : WireMessage
	/** Host → client: the game started and you play [seat]. */
	data class Start(val seat: Seat) : WireMessage
	/** Host → client: the table changed, optionally with an animation. */
	data class Update(val view: TableView, val event: ViewEvent?) : WireMessage
	/** Client → host. */
	data class Action(val action: PlayerAction) : WireMessage
	/** Either way: the connection is ending. */
	data class Bye(val reason: String) : WireMessage
}

/**
 * JSON encoding of [WireMessage]. Decoding throws on malformed input; callers
 * treat that as a broken connection. Strings from the other side are only
 * ever used as data (names), never interpreted.
 */
object Wire {
	const val PROTOCOL = 2
	/** Longest player name accepted from the network. */
	const val MAX_NAME = 24

	fun encode(m: WireMessage): String = JSONObject().apply {
		when (m) {
			is WireMessage.Hello -> put("t", "hello").put("v", m.version).put("name", m.name)
			is WireMessage.Lobby -> put("t", "lobby").put("names", names(m.names))
			is WireMessage.Start -> put("t", "start").put("seat", m.seat.name)
			is WireMessage.Update -> {
				put("t", "u").put("v", view(m.view))
				m.event?.let { put("e", event(it)) }
			}
			is WireMessage.Action -> put("t", "a").put("a", action(m.action))
			is WireMessage.Bye -> put("t", "bye").put("reason", m.reason)
		}
	}.toString()

	fun decode(text: String): WireMessage {
		val o = JSONObject(text)
		return when (val t = o.getString("t")) {
			"hello" -> WireMessage.Hello(o.getInt("v"), cleanName(o.getString("name")))
			"lobby" -> WireMessage.Lobby(names(o.getJSONArray("names")))
			"start" -> WireMessage.Start(seat(o.getString("seat")))
			"u" -> WireMessage.Update(view(o.getJSONObject("v")), o.optJSONObject("e")?.let(::event))
			"a" -> WireMessage.Action(action(o.getJSONObject("a")))
			"bye" -> WireMessage.Bye(o.optString("reason").take(200))
			else -> throw IllegalArgumentException("unknown message $t")
		}
	}

	/** Trims a name and drops control characters. */
	fun cleanName(s: String): String =
		s.filter { !it.isISOControl() }.trim().take(MAX_NAME).ifEmpty { "Player" }

	// ── helpers ───────────────────────────────────────────────────────────────

	private fun seat(s: String) = Seat.valueOf(s)
	private fun optSeat(o: JSONObject, k: String): Seat? = if (o.isNull(k) || !o.has(k)) null else seat(o.getString(k))
	private fun optColor(o: JSONObject, k: String): CardColor? = if (o.isNull(k) || !o.has(k)) null else CardColor.valueOf(o.getString(k))
	private fun JSONObject.putEnum(k: String, e: Enum<*>?) = put(k, e?.name ?: JSONObject.NULL)

	private fun optType(o: JSONObject, k: String): CardType? =
		if (o.isNull(k) || !o.has(k)) null else type(o.getString(k))
	private fun type(s: String): CardType = requireNotNull(CardType.fromKey(s)) { "unknown card" }
	private fun seats(xs: List<Seat>) = JSONArray(xs.map { it.name })
	private fun seats(a: JSONArray) = List(a.length()) { seat(a.getString(it)) }

	private fun ints(xs: List<Int>) = JSONArray().also { a -> xs.forEach { a.put(it) } }
	private fun ints(a: JSONArray) = List(a.length()) { a.getInt(it) }

	private fun names(xs: List<String?>) = JSONArray().also { a -> xs.forEach { a.put(it ?: JSONObject.NULL) } }
	private fun names(a: JSONArray): List<String?> {
		require(a.length() == 4) { "expected 4 seats" }
		return List(4) { if (a.isNull(it)) null else cleanName(a.getString(it)) }
	}

	// ── view ──────────────────────────────────────────────────────────────────

	private fun view(v: TableView) = JSONObject().apply {
		put("me", v.me.name)
		put("names", names(v.names))
		put("players", JSONArray().also { a ->
			v.players.forEach { p ->
				a.put(JSONObject()
					.put("seat", p.seat.name).put("status", p.status.name).put("human", p.isHuman)
					.put("wins", p.wins).put("losses", p.losses)
					.put("field", ints(p.field)).put("held", ints(p.held))
					.put("uno", p.calledUno).put("clock", p.deathClock).putEnum("mate", p.teammate))
			}
		})
		put("draw", v.drawPileSize)
		put("discard", ints(v.discard))
		put("recycle", ints(v.recycle))
		put("recyclePiles", v.recyclePiles)
		put("neutral", ints(v.neutral))
		putEnum("curr", v.currPlayer)
		putEnum("color", v.currColor)
		put("top", v.top?.key ?: JSONObject.NULL)
		put("apoc", v.apocalypse)
		put("elim", v.elimination)
		put("roundComplete", v.roundComplete)
		put("waiting", v.waitingToStartRound)
		putEnum("winner", v.roundWinner)
		putEnum("loser", v.roundLoser)
		put("ff", v.fastForward)
		put("showNext", v.showNextRound)
		put("showFf", v.showFastForward)
		put("decision", v.decision.name)
		put("legal", ints(v.legal))
		put("canDraw", v.canDraw)
		put("canPass", v.canPass)
		put("jump", v.jumpIn)
		v.prompt?.let { put("prompt", prompt(it)) }
		v.attack?.let { a ->
			put("attack", JSONObject()
				.put("kind", a.kind.name).put("card", a.card.key).put("n", a.amount).put("pile", a.pile)
				.put("victim", a.victim.name).put("source", a.source.name))
		}
		put("accuse", v.canAccuse)
		put("invade", seats(v.invadeTargets))
		put("ask", v.canAskProphet)
		putEnum("prophet", v.prophet)
		putEnum("prophetTarget", v.prophetTarget)
		put("recolor", v.canChangeColor)
		put("indDir", v.indicatorDirection.name)
		putEnum("indColor", v.indicatorColor)
		put("delay", v.delayMs)
	}

	private fun view(o: JSONObject): TableView {
		val pa = o.getJSONArray("players")
		require(pa.length() == 4) { "expected 4 players" }
		return TableView(
			me = seat(o.getString("me")),
			names = names(o.getJSONArray("names")),
			players = List(4) { i ->
				val p = pa.getJSONObject(i)
				PlayerView(
					seat = seat(p.getString("seat")),
					status = Status.valueOf(p.getString("status")),
					isHuman = p.getBoolean("human"),
					wins = p.getInt("wins"),
					losses = p.getInt("losses"),
					field = ints(p.getJSONArray("field")),
					held = ints(p.getJSONArray("held")),
					calledUno = p.getBoolean("uno"),
					deathClock = p.getInt("clock"),
					teammate = optSeat(p, "mate"),
				)
			},
			drawPileSize = o.getInt("draw"),
			discard = ints(o.getJSONArray("discard")),
			recycle = ints(o.getJSONArray("recycle")),
			recyclePiles = o.getInt("recyclePiles"),
			neutral = ints(o.getJSONArray("neutral")),
			currPlayer = optSeat(o, "curr"),
			currColor = optColor(o, "color"),
			top = optType(o, "top"),
			apocalypse = o.getBoolean("apoc"),
			elimination = o.getBoolean("elim"),
			roundComplete = o.getBoolean("roundComplete"),
			waitingToStartRound = o.getBoolean("waiting"),
			roundWinner = optSeat(o, "winner"),
			roundLoser = optSeat(o, "loser"),
			fastForward = o.getBoolean("ff"),
			showNextRound = o.getBoolean("showNext"),
			showFastForward = o.getBoolean("showFf"),
			decision = Decision.valueOf(o.getString("decision")),
			legal = ints(o.getJSONArray("legal")),
			canDraw = o.getBoolean("canDraw"),
			canPass = o.getBoolean("canPass"),
			jumpIn = o.getBoolean("jump"),
			prompt = o.optJSONObject("prompt")?.let(::prompt),
			attack = o.optJSONObject("attack")?.let { a ->
				AttackView(AttackKind.valueOf(a.getString("kind")), type(a.getString("card")), a.getInt("n"), a.getInt("pile"),
					seat(a.getString("victim")), seat(a.getString("source")))
			},
			canAccuse = o.getBoolean("accuse"),
			invadeTargets = seats(o.getJSONArray("invade")),
			canAskProphet = o.getBoolean("ask"),
			prophet = optSeat(o, "prophet"),
			prophetTarget = optSeat(o, "prophetTarget"),
			canChangeColor = o.getBoolean("recolor"),
			indicatorDirection = Direction.valueOf(o.getString("indDir")),
			indicatorColor = optColor(o, "indColor"),
			delayMs = o.getLong("delay"),
		)
	}

	private fun prompt(p: Prompt) = JSONObject().apply {
		when (p) {
			Prompt.Color -> put("k", "color")
			is Prompt.Victim -> put("k", "victim").put("r", p.reason.name).put("o", seats(p.options)).put("opt", p.optional)
			is Prompt.ChooseCard -> put("k", "card").put("r", p.reason.name).put("o", ints(p.options)).put("opt", p.optional)
		}
	}

	private fun prompt(o: JSONObject): Prompt = when (val k = o.getString("k")) {
		"color" -> Prompt.Color
		"victim" -> Prompt.Victim(Reason.valueOf(o.getString("r")), seats(o.getJSONArray("o")), o.getBoolean("opt"))
		"card" -> Prompt.ChooseCard(Reason.valueOf(o.getString("r")), ints(o.getJSONArray("o")), o.getBoolean("opt"))
		else -> throw IllegalArgumentException("unknown prompt $k")
	}

	// ── events ────────────────────────────────────────────────────────────────

	private fun event(e: ViewEvent) = JSONObject().apply {
		when (e) {
			ViewEvent.RoundReset -> put("k", "reset")
			is ViewEvent.Dealt -> put("k", "dealt").put("c", e.card).put("dealer", e.dealer.name).put("to", e.to.name).put("ms", e.durationMs)
			is ViewEvent.Drawn -> put("k", "drawn").put("c", e.card).put("to", e.to.name).put("ms", e.durationMs)
			is ViewEvent.Flipped -> put("k", "flip").put("c", e.card).put("ms", e.durationMs)
			is ViewEvent.Discarded -> put("k", "discard").put("c", e.card).putEnum("from", e.from).put("ms", e.durationMs)
			is ViewEvent.ToField -> put("k", "field").put("c", e.card).putEnum("from", e.from).putEnum("to", e.to).put("ms", e.durationMs)
			is ViewEvent.Passed -> put("k", "pass").put("c", e.card).put("from", e.from.name).put("to", e.to.name).put("ms", e.durationMs)
			is ViewEvent.PointerMoved -> put("k", "pointer").put("seat", e.seat.name).put("dir", e.direction.name).put("ms", e.durationMs)
			is ViewEvent.IndicatorChanged -> put("k", "ind").put("dir", e.direction.name).putEnum("color", e.color).put("ms", e.durationMs)
			is ViewEvent.ColorChooser -> put("k", "chooser").put("show", e.show).put("ms", e.durationMs)
			is ViewEvent.Message -> put("k", "msg").put("m", message(e.message))
		}
	}

	private fun event(o: JSONObject): ViewEvent {
		fun ms() = o.getLong("ms")
		return when (val k = o.getString("k")) {
			"reset" -> ViewEvent.RoundReset
			"dealt" -> ViewEvent.Dealt(o.getInt("c"), seat(o.getString("dealer")), seat(o.getString("to")), ms())
			"drawn" -> ViewEvent.Drawn(o.getInt("c"), seat(o.getString("to")), ms())
			"flip" -> ViewEvent.Flipped(o.getInt("c"), ms())
			"discard" -> ViewEvent.Discarded(o.getInt("c"), optSeat(o, "from"), ms())
			"field" -> ViewEvent.ToField(o.getInt("c"), optSeat(o, "from"), optSeat(o, "to"), ms())
			"pass" -> ViewEvent.Passed(o.getInt("c"), seat(o.getString("from")), seat(o.getString("to")), ms())
			"pointer" -> ViewEvent.PointerMoved(seat(o.getString("seat")), Direction.valueOf(o.getString("dir")), ms())
			"ind" -> ViewEvent.IndicatorChanged(Direction.valueOf(o.getString("dir")), optColor(o, "color"), ms())
			"chooser" -> ViewEvent.ColorChooser(o.getBoolean("show"), ms())
			"msg" -> ViewEvent.Message(message(o.getJSONObject("m")))
			else -> throw IllegalArgumentException("unknown event $k")
		}
	}

	private fun message(m: GameMessage) = JSONObject()
		.put("k", m.kind.name).putEnum("s", m.seat).putEnum("s2", m.other).put("n", m.n)
		.put("c", m.card?.key ?: JSONObject.NULL).putEnum("to", m.to)

	private fun message(o: JSONObject) = GameMessage(
		Msg.valueOf(o.getString("k")), optSeat(o, "s"), optSeat(o, "s2"), o.getInt("n"), optType(o, "c"), optSeat(o, "to"),
	)

	// ── actions ───────────────────────────────────────────────────────────────

	private fun action(a: PlayerAction) = JSONObject().apply {
		when (a) {
			is PlayerAction.Play -> put("k", "play").put("c", a.card)
			PlayerAction.Draw -> put("k", "draw")
			PlayerAction.Pass -> put("k", "pass")
			PlayerAction.DiscardTap -> put("k", "discard")
			PlayerAction.NextRound -> put("k", "next")
			PlayerAction.FastForward -> put("k", "ff")
			is PlayerAction.Color -> put("k", "color").put("c", a.color.name)
			is PlayerAction.Victim -> put("k", "victim").putEnum("s", a.seat)
			is PlayerAction.ChooseCard -> put("k", "card").put("c", a.card ?: JSONObject.NULL)
			PlayerAction.Uno -> put("k", "uno")
			is PlayerAction.Accuse -> put("k", "accuse").put("s", a.seat.name)
			is PlayerAction.AskProphet -> put("k", "ask").put("c", a.about.key)
			is PlayerAction.ChangeColor -> put("k", "recolor").put("c", a.color.name)
			is PlayerAction.Invade -> put("k", "invade").put("s", a.seat.name)
		}
	}

	private fun action(o: JSONObject): PlayerAction = when (val k = o.getString("k")) {
		"play" -> PlayerAction.Play(o.getInt("c"))
		"draw" -> PlayerAction.Draw
		"pass" -> PlayerAction.Pass
		"discard" -> PlayerAction.DiscardTap
		"next" -> PlayerAction.NextRound
		"ff" -> PlayerAction.FastForward
		"color" -> PlayerAction.Color(CardColor.valueOf(o.getString("c")))
		"victim" -> PlayerAction.Victim(optSeat(o, "s"))
		"card" -> PlayerAction.ChooseCard(if (o.isNull("c")) null else o.getInt("c"))
		"uno" -> PlayerAction.Uno
		"accuse" -> PlayerAction.Accuse(seat(o.getString("s")))
		"ask" -> PlayerAction.AskProphet(type(o.getString("c")))
		"recolor" -> PlayerAction.ChangeColor(CardColor.valueOf(o.getString("c")))
		"invade" -> PlayerAction.Invade(seat(o.getString("s")))
		else -> throw IllegalArgumentException("unknown action $k")
	}
}
