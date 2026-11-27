package com.smorgasbork.hotdeath.net

import com.smorgasbork.hotdeath.engine.CardColor
import com.smorgasbork.hotdeath.engine.CardType
import com.smorgasbork.hotdeath.engine.DeckType
import com.smorgasbork.hotdeath.engine.Direction
import com.smorgasbork.hotdeath.engine.GameMessage
import com.smorgasbork.hotdeath.engine.NoGoodReason
import com.smorgasbork.hotdeath.engine.PenaltyType
import com.smorgasbork.hotdeath.engine.Prompt
import com.smorgasbork.hotdeath.engine.Seat
import org.json.JSONArray
import org.json.JSONObject

/** Messages between a host and its clients. */
sealed interface WireMessage {
	/** Client → host, first message. */
	data class Hello(val version: Int, val name: String, val rejoin: String? = null) : WireMessage
	/** Host → clients: who is in the lobby, by seat (null = computer / empty). */
	data class Lobby(val names: List<String?>) : WireMessage
	/** Host → client: the game started and you play [seat]; [token] lets you rejoin it. */
	data class Start(val seat: Seat, val token: String? = null) : WireMessage
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
	const val PROTOCOL = 1
	/** Longest player name accepted from the network. */
	const val MAX_NAME = 24

	fun encode(m: WireMessage): String = JSONObject().apply {
		when (m) {
			is WireMessage.Hello -> put("t", "hello").put("v", m.version).put("name", m.name).apply { m.rejoin?.let { put("rejoin", it) } }
			is WireMessage.Lobby -> put("t", "lobby").put("names", names(m.names))
			is WireMessage.Start -> put("t", "start").put("seat", m.seat.name).apply { m.token?.let { put("token", it) } }
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
			"hello" -> WireMessage.Hello(o.getInt("v"), cleanName(o.getString("name")), optToken(o, "rejoin"))
			"lobby" -> WireMessage.Lobby(names(o.getJSONArray("names")))
			"start" -> WireMessage.Start(seat(o.getString("seat")), optToken(o, "token"))
			// An animation this version does not know is dropped; the table itself still updates.
			"u" -> WireMessage.Update(view(o.getJSONObject("v")), o.optJSONObject("e")?.let { runCatching { event(it) }.getOrNull() })
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
	private fun optToken(o: JSONObject, k: String): String? = if (o.isNull(k) || !o.has(k)) null else o.getString(k).take(64)
	private fun optSeat(o: JSONObject, k: String): Seat? = if (o.isNull(k) || !o.has(k)) null else seat(o.getString(k))
	private fun optColor(o: JSONObject, k: String): CardColor? = if (o.isNull(k) || !o.has(k)) null else CardColor.valueOf(o.getString(k))
	private fun JSONObject.putEnum(k: String, e: Enum<*>?) = put(k, e?.name ?: JSONObject.NULL)

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
		put("deck", v.deckType.key)
		put("names", names(v.names))
		put("players", JSONArray().also { a ->
			v.players.forEach { p ->
				a.put(JSONObject()
					.put("seat", p.seat.name).put("active", p.active).put("human", p.isHuman)
					.put("total", p.totalScore).put("last", p.lastScore).put("virus", p.lastVirusPenalty)
					.put("table", ints(p.table)).put("held", ints(p.held)))
			}
		})
		put("draw", v.drawPileSize)
		put("discard", ints(v.discard))
		putEnum("curr", v.currPlayer)
		putEnum("color", v.currColor)
		put("lastPlayed", v.lastPlayed)
		put("roundComplete", v.roundComplete)
		put("waiting", v.waitingToStartRound)
		put("over", v.gameOver)
		putEnum("winner", v.gameWinner)
		put("ff", v.fastForward)
		put("showNext", v.showNextRound)
		put("showFf", v.showFastForward)
		put("menu", v.showMenu)
		put("canDraw", v.canDraw)
		put("canPass", v.canPass)
		put("legal", ints(v.legal))
		putEnum("clock", v.clockSeat)
		put("clockMs", v.clockLeftMs)
		v.prompt?.let { put("prompt", prompt(it)) }
		v.penalty?.let { p ->
			put("penalty", JSONObject()
				.put("type", p.type.name).put("n", p.numCards).put("orig", p.origCard)
				.putEnum("victim", p.victim).putEnum("gen", p.generator))
		}
		put("indDir", v.indicatorDirection.name)
		putEnum("indColor", v.indicatorColor)
		put("delay", v.delayMs)
	}

	private fun view(o: JSONObject): TableView {
		val pa = o.getJSONArray("players")
		require(pa.length() == 4) { "expected 4 players" }
		return TableView(
			me = seat(o.getString("me")),
			deckType = DeckType.fromKey(o.getString("deck")),
			names = names(o.getJSONArray("names")),
			players = List(4) { i ->
				val p = pa.getJSONObject(i)
				PlayerView(
					seat = seat(p.getString("seat")),
					active = p.getBoolean("active"),
					isHuman = p.getBoolean("human"),
					totalScore = p.getInt("total"),
					lastScore = p.getInt("last"),
					lastVirusPenalty = p.getInt("virus"),
					table = ints(p.getJSONArray("table")),
					held = ints(p.getJSONArray("held")),
				)
			},
			drawPileSize = o.getInt("draw"),
			discard = ints(o.getJSONArray("discard")),
			currPlayer = optSeat(o, "curr"),
			currColor = optColor(o, "color"),
			lastPlayed = o.getInt("lastPlayed"),
			roundComplete = o.getBoolean("roundComplete"),
			waitingToStartRound = o.getBoolean("waiting"),
			gameOver = o.getBoolean("over"),
			gameWinner = optSeat(o, "winner"),
			fastForward = o.getBoolean("ff"),
			showNextRound = o.getBoolean("showNext"),
			showFastForward = o.getBoolean("showFf"),
			showMenu = o.getBoolean("menu"),
			canDraw = o.getBoolean("canDraw"),
			canPass = o.getBoolean("canPass"),
			legal = o.optJSONArray("legal")?.let { ints(it) } ?: emptyList(),
			clockSeat = optSeat(o, "clock"),
			clockLeftMs = o.optLong("clockMs"),
			prompt = o.optJSONObject("prompt")?.let(::prompt),
			penalty = o.optJSONObject("penalty")?.let { p ->
				PenaltyView(PenaltyType.valueOf(p.getString("type")), p.getInt("n"), p.getInt("orig"),
					optSeat(p, "victim"), optSeat(p, "gen"))
			},
			indicatorDirection = Direction.valueOf(o.getString("indDir")),
			indicatorColor = optColor(o, "indColor"),
			delayMs = o.getLong("delay"),
		)
	}

	private fun prompt(p: Prompt) = JSONObject().apply {
		when (p) {
			Prompt.NumCardsToDeal -> put("k", "deal")
			Prompt.Color -> put("k", "color")
			is Prompt.Victim -> put("k", "victim").put("o", JSONArray(p.options.map { it.name }))
		}
	}

	private fun prompt(o: JSONObject): Prompt = when (val k = o.getString("k")) {
		"deal" -> Prompt.NumCardsToDeal
		"color" -> Prompt.Color
		"victim" -> o.getJSONArray("o").let { a -> Prompt.Victim(List(a.length()) { seat(a.getString(it)) }) }
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
			is ViewEvent.CheatSwap -> put("k", "cheat").put("new", e.newCard).put("old", e.oldCard).put("ms", e.durationMs)
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
			"cheat" -> ViewEvent.CheatSwap(o.getInt("new"), o.getInt("old"), ms())
			"pointer" -> ViewEvent.PointerMoved(seat(o.getString("seat")), Direction.valueOf(o.getString("dir")), ms())
			"ind" -> ViewEvent.IndicatorChanged(Direction.valueOf(o.getString("dir")), optColor(o, "color"), ms())
			"chooser" -> ViewEvent.ColorChooser(o.getBoolean("show"), ms())
			"msg" -> ViewEvent.Message(message(o.getJSONObject("m")))
			else -> throw IllegalArgumentException("unknown event $k")
		}
	}

	private fun message(m: GameMessage) = JSONObject().apply {
		when (m) {
			is GameMessage.Dealing -> put("k", "Dealing").put("s", m.dealer.name).put("n", m.count)
			GameMessage.TapDrawPile -> put("k", "TapDrawPile")
			is GameMessage.YourPlay -> put("k", "YourPlay").put("s", m.seat.name)
			is GameMessage.CardNoGood -> put("k", "CardNoGood").put("s", m.seat.name).apply { m.why?.let { put("why", why(it)) } }
			is GameMessage.TimedOut -> put("k", "TimedOut").put("s", m.seat.name)
			is GameMessage.PlayerDrawsCard -> put("k", "PlayerDrawsCard").put("s", m.seat.name)
			is GameMessage.TakesPenalty -> put("k", "TakesPenalty").put("s", m.seat.name).put("n", m.count).putEnum("c", m.reason)
			is GameMessage.FaceUp -> put("k", "FaceUp").put("s", m.seat.name)
			is GameMessage.EjectedBy -> put("k", "EjectedBy").put("s", m.seat.name).putEnum("s2", m.by)
			is GameMessage.Ejected -> put("k", "Ejected").put("s", m.seat.name)
			is GameMessage.RoundWinner -> put("k", "RoundWinner").put("s", m.seat.name)
			is GameMessage.ShufflingDiscard -> put("k", "ShufflingDiscard").put("n", m.count)
			GameMessage.DiscardEmpty -> put("k", "DiscardEmpty")
			GameMessage.LuckOfIrish -> put("k", "LuckOfIrish")
			is GameMessage.Drawing -> put("k", "Drawing").put("s", m.seat.name).put("n", m.count)
			is GameMessage.AllBastardCards -> put("k", "AllBastardCards").put("s", m.seat.name)
			is GameMessage.HasBlueShield -> put("k", "HasBlueShield").put("s", m.seat.name)
			is GameMessage.HolyDefender -> put("k", "HolyDefender").put("s", m.victim.name)
			GameMessage.Magic5 -> put("k", "Magic5")
			is GameMessage.SendingPenalty -> put("k", "SendingPenalty").put("s", m.victim.name)
			is GameMessage.Swap -> put("k", "Swap").put("s", m.a.name).put("s2", m.b.name)
			is GameMessage.Clone -> put("k", "Clone").put("c", m.card.name)
			is GameMessage.Ping -> put("k", "Ping").put("s", m.from.name).put("s2", m.to.name)
		}
	}

	private fun message(o: JSONObject): GameMessage {
		fun s() = seat(o.getString("s"))
		fun s2() = seat(o.getString("s2"))
		fun n() = o.getInt("n")
		return when (val k = o.getString("k")) {
			"Dealing" -> GameMessage.Dealing(s(), n())
			"TapDrawPile" -> GameMessage.TapDrawPile
			"YourPlay" -> GameMessage.YourPlay(s())
			"CardNoGood" -> GameMessage.CardNoGood(s(), o.optJSONObject("why")?.let(::why))
			"TimedOut" -> GameMessage.TimedOut(s())
			"PlayerDrawsCard" -> GameMessage.PlayerDrawsCard(s())
			"TakesPenalty" -> GameMessage.TakesPenalty(s(), n(), if (o.isNull("c") || !o.has("c")) null else CardType.valueOf(o.getString("c")))
			"FaceUp" -> GameMessage.FaceUp(s())
			"EjectedBy" -> GameMessage.EjectedBy(s(), optSeat(o, "s2"))
			"Ejected" -> GameMessage.Ejected(s())
			"RoundWinner" -> GameMessage.RoundWinner(s())
			"ShufflingDiscard" -> GameMessage.ShufflingDiscard(n())
			"DiscardEmpty" -> GameMessage.DiscardEmpty
			"LuckOfIrish" -> GameMessage.LuckOfIrish
			"Drawing" -> GameMessage.Drawing(s(), n())
			"AllBastardCards" -> GameMessage.AllBastardCards(s())
			"HasBlueShield" -> GameMessage.HasBlueShield(s())
			"HolyDefender" -> GameMessage.HolyDefender(s())
			"Magic5" -> GameMessage.Magic5
			"SendingPenalty" -> GameMessage.SendingPenalty(s())
			"Swap" -> GameMessage.Swap(s(), s2())
			"Clone" -> GameMessage.Clone(CardType.valueOf(o.getString("c")))
			"Ping" -> GameMessage.Ping(s(), s2())
			else -> throw IllegalArgumentException("unknown message $k")
		}
	}

	private fun why(r: NoGoodReason) = JSONObject().apply {
		when (r) {
			NoGoodReason.NotYourTurn -> put("k", "turn")
			is NoGoodReason.UnderAttack -> put("k", "attack").put("n", r.count)
			NoGoodReason.Aids -> put("k", "aids")
			NoGoodReason.Shitter -> put("k", "shitter")
			is NoGoodReason.HoldsColor -> put("k", "holds").putEnum("color", r.color)
			is NoGoodReason.NoMatch -> put("k", "match").putEnum("color", r.color).putEnum("top", r.top)
		}
	}

	/** Null for a reason this version does not know (the plain message is shown). */
	private fun why(o: JSONObject): NoGoodReason? = when (o.optString("k")) {
		"turn" -> NoGoodReason.NotYourTurn
		"attack" -> NoGoodReason.UnderAttack(o.getInt("n"))
		"aids" -> NoGoodReason.Aids
		"shitter" -> NoGoodReason.Shitter
		"holds" -> NoGoodReason.HoldsColor(optColor(o, "color"))
		"match" -> NoGoodReason.NoMatch(optColor(o, "color"), if (o.isNull("top") || !o.has("top")) null else CardType.valueOf(o.getString("top")))
		else -> null
	}

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
			is PlayerAction.Victim -> put("k", "victim").put("s", a.seat.name)
			is PlayerAction.Deal -> put("k", "deal").put("n", a.count)
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
		"victim" -> PlayerAction.Victim(seat(o.getString("s")))
		"deal" -> PlayerAction.Deal(o.getInt("n"))
		else -> throw IllegalArgumentException("unknown action $k")
	}
}
