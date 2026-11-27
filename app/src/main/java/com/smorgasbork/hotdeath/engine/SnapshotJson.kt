package com.smorgasbork.hotdeath.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Saves the game at a turn boundary as JSON, and restores it. Cards are
 * stored by deck index (the deck is the same in every game). Saves from the
 * old rules have no version 2 marker and are refused.
 */
object SnapshotJson {
	private const val VERSION = 2

	fun capture(g: Game): String = JSONObject().apply {
		put("v", VERSION)
		put("draw", ids(g.drawPile.asList))
		put("discard", ids(g.discardPile.asList))
		put("recycle", JSONArray().also { a ->
			g.recyclePiles.forEach { a.put(JSONObject().put("owner", it.owner.seat.name).put("cards", ids(it.pile.asList))) }
		})
		put("neutral", ids(g.neutral))
		put("top", g.topType?.key ?: JSONObject.NULL)
		put("color", g.currColor?.id ?: 0)
		put("dir", g.direction.id)
		put("curr", seat(g.currPlayer))
		put("dealer", seat(g.dealer))
		put("apocalypse", g.apocalypse)
		put("plague", seat(g.plagueCarrier))
		put("prophet", seat(g.prophet))
		put("prophetTarget", seat(g.prophetTarget))
		put("mystery", g.lastMysteryAmount)
		put("complete", g.roundComplete)
		put("winner", g.roundWinner?.name ?: JSONObject.NULL)
		put("loser", g.roundLoser?.name ?: JSONObject.NULL)
		put("players", JSONArray().also { a ->
			for (p in g.players) a.put(JSONObject().apply {
				put("status", p.status.name)
				put("hand", ids(p.hand.cards))
				put("revealed", ids(p.hand.revealedCards))
				put("faceUp", p.hand.faceUp)
				put("field", ids(p.field))
				put("clock", p.deathClock)
				put("clockCard", p.clockCard?.deckIndex ?: -1)
				put("armies", ids(p.armies))
				put("war", p.warCard?.deckIndex ?: -1)
				put("conquered", JSONArray(p.conquered.map { it.name }))
				put("teammate", seat(p.teammate))
				put("uno", p.calledUno)
				put("noUno", p.emptiedWithoutUno)
				put("peeked", ids(p.peeked))
				put("wins", p.wins)
				put("losses", p.losses)
			})
		})
	}.toString()

	/** Fills a fresh game from [json]; throws [IllegalArgumentException] if it is unusable. */
	fun restore(g: Game, json: String) {
		val o = try {
			JSONObject(json)
		} catch (e: Exception) {
			throw IllegalArgumentException("bad save", e)
		}
		require(o.optInt("v") == VERSION) { "save from another version" }
		try {
			fun card(i: Int): Card? = g.deck[i]
			fun cards(a: JSONArray): List<Card> = List(a.length()) { a.getInt(it) }.map { requireNotNull(card(it)) { "bad card $it" } }
			fun player(k: String): Player? = if (o.isNull(k)) null else g.player(Seat.valueOf(o.getString(k)))

			g.drawPile.addAll(cards(o.getJSONArray("draw")))
			g.discardPile.addAll(cards(o.getJSONArray("discard")))
			val rec = o.getJSONArray("recycle")
			for (i in 0 until rec.length()) {
				val r = rec.getJSONObject(i)
				val rp = RecyclePile(g.player(Seat.valueOf(r.getString("owner"))))
				rp.pile.addAll(cards(r.getJSONArray("cards")))
				g.recyclePiles += rp
			}
			g.neutral += cards(o.getJSONArray("neutral"))
			g.topType = if (o.isNull("top")) null else CardType.fromKey(o.getString("top"))
			g.currColor = CardColor.fromId(o.getInt("color"))
			g.direction = Direction.fromId(o.getInt("dir"))
			g.currPlayer = player("curr")
			g.dealer = player("dealer")
			g.apocalypse = o.getBoolean("apocalypse")
			g.plagueCarrier = player("plague")
			g.prophet = player("prophet")
			g.prophetTarget = player("prophetTarget")
			g.lastMysteryAmount = o.getInt("mystery")
			g.restoreRoundState(
				o.getBoolean("complete"),
				if (o.isNull("winner")) null else Seat.valueOf(o.getString("winner")),
				if (o.isNull("loser")) null else Seat.valueOf(o.getString("loser")),
			)

			val ps = o.getJSONArray("players")
			require(ps.length() == 4) { "expected 4 players" }
			for ((i, p) in g.players.withIndex()) {
				val j = ps.getJSONObject(i)
				val h = Hand(j.getBoolean("faceUp")).also { it.keepSorted = p.controller is HumanController }
				h.restore(cards(j.getJSONArray("hand")), cards(j.getJSONArray("revealed")))
				p.replaceHand(h)
				p.status = Status.valueOf(j.getString("status"))
				p.field += cards(j.getJSONArray("field"))
				p.deathClock = j.getInt("clock")
				p.clockCard = card(j.getInt("clockCard"))
				p.armies += cards(j.getJSONArray("armies"))
				p.warCard = card(j.getInt("war"))
				val conq = j.getJSONArray("conquered")
				for (k in 0 until conq.length()) p.conquered += Seat.valueOf(conq.getString(k))
				p.teammate = if (j.isNull("teammate")) null else g.player(Seat.valueOf(j.getString("teammate")))
				p.calledUno = j.getBoolean("uno")
				p.emptiedWithoutUno = j.getBoolean("noUno")
				p.peeked += cards(j.getJSONArray("peeked"))
				p.wins = j.getInt("wins")
				p.losses = j.getInt("losses")
			}
			requireNotNull(g.currPlayer) { "no current player" }
			requireNotNull(g.dealer) { "no dealer" }
		} catch (e: IllegalArgumentException) {
			throw e
		} catch (e: Exception) {
			throw IllegalArgumentException("bad save", e)
		}
	}

	private fun ids(cs: Collection<Card>) = JSONArray().also { a -> cs.forEach { a.put(it.deckIndex) } }
	private fun seat(p: Player?): Any = p?.seat?.name ?: JSONObject.NULL
}
