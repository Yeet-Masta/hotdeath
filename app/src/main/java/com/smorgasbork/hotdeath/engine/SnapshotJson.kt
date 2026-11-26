package com.smorgasbork.hotdeath.engine

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Converts [GameSnapshot]s to and from the JSON stored in preferences.
 *
 * The layout matches the original save format, so games saved by older
 * versions still load. Version 2 adds `prevCard`, `nextPlayerPreset` and,
 * per hand, `faceUp` and `revealed`; all are optional when reading.
 */
object SnapshotJson {

	private const val VERSION = 2

	fun toJson(s: GameSnapshot): String {
		val o = JSONObject()
		o.put("version", VERSION)
		o.put("deckType", s.deckType.key)

		val state = JSONObject()
		state.put("dealer", s.dealer)
		state.put("currPlayer", s.currPlayer)
		state.put("currColor", s.currColor)
		state.put("direction", s.direction)
		state.put("cardsPlayed", s.cardsPlayed)
		state.put("roundComplete", s.roundComplete)
		state.put("currCard", s.currCard)
		state.put("prevCard", s.prevCard)
		state.put("nextPlayerPreset", s.nextPlayerPreset)
		o.put("state", state)

		o.put("drawPile", JSONObject().put("cards", ints(s.drawPile)))
		o.put("discardPile", JSONObject().put("cards", ints(s.discardPile)))

		val pen = s.penalty
		o.put("penalty", JSONObject()
			.put("type", pen.type)
			.put("numcards", pen.numCards)
			.put("hasHotDeath", pen.hasHotDeath)
			.put("generatingPlayer", pen.generatingPlayer)
			.put("victim", pen.victim)
			.put("secondaryVictim", pen.secondaryVictim)
			.put("origCard", pen.origCard))

		val players = JSONArray()
		for (p in s.players) {
			val hand = JSONObject()
				.put("cards", ints(p.tableCards + p.heldCards))
				.put("numCardsOnTable", p.tableCards.size)
				.put("revealed", ints(p.revealed))
			p.faceUp?.let { hand.put("faceUp", it) }
			players.put(JSONObject()
				.put("active", p.active)
				.put("totalScore", p.totalScore)
				.put("lastScore", p.lastScore)
				.put("hasTriedDrawing", p.hasTriedDrawing)
				.put("lastDrawn", p.lastDrawn)
				.put("virusPenalty", p.virusPenalty)
				.put("lastVirusPenalty", p.lastVirusPenalty)
				.put("hand", hand))
		}
		o.put("players", players)
		return o.toString()
	}

	/** @throws JSONException if the text is not a usable save. */
	fun fromJson(text: String): GameSnapshot {
		val o = JSONObject(text)
		val state = o.getJSONObject("state")
		val pen = o.getJSONObject("penalty")
		val pa = o.getJSONArray("players")

		val players = (0 until pa.length()).map { i ->
			val p = pa.getJSONObject(i)
			val hand = p.getJSONObject("hand")
			val cards = intList(hand.getJSONArray("cards"))
			val onTable = hand.getInt("numCardsOnTable").coerceIn(0, cards.size)
			PlayerSnapshot(
				active = p.getBoolean("active"),
				totalScore = p.getInt("totalScore"),
				lastScore = p.getInt("lastScore"),
				hasTriedDrawing = p.getBoolean("hasTriedDrawing"),
				lastDrawn = p.getInt("lastDrawn"),
				virusPenalty = p.getInt("virusPenalty"),
				lastVirusPenalty = p.getInt("lastVirusPenalty"),
				tableCards = cards.take(onTable),
				heldCards = cards.drop(onTable),
				revealed = hand.optJSONArray("revealed")?.let(::intList) ?: emptyList(),
				faceUp = if (hand.has("faceUp")) hand.getBoolean("faceUp") else null,
			)
		}

		return GameSnapshot(
			deckType = DeckType.fromKey(o.getString("deckType")),
			drawPile = intList(o.getJSONObject("drawPile").getJSONArray("cards")),
			discardPile = intList(o.getJSONObject("discardPile").getJSONArray("cards")),
			currColor = state.getInt("currColor"),
			direction = state.getInt("direction"),
			cardsPlayed = state.getInt("cardsPlayed"),
			roundComplete = state.getBoolean("roundComplete"),
			currCard = state.getInt("currCard"),
			prevCard = state.optInt("prevCard", -1),
			currPlayer = state.getInt("currPlayer"),
			dealer = state.getInt("dealer"),
			nextPlayerPreset = state.optInt("nextPlayerPreset", 0),
			players = players,
			penalty = PenaltySnapshot(
				type = pen.getInt("type"),
				numCards = pen.getInt("numcards"),
				hasHotDeath = pen.getBoolean("hasHotDeath"),
				generatingPlayer = pen.getInt("generatingPlayer"),
				victim = pen.getInt("victim"),
				secondaryVictim = pen.getInt("secondaryVictim"),
				origCard = pen.getInt("origCard"),
			),
		)
	}

	private fun ints(xs: List<Int>): JSONArray = JSONArray().also { a -> xs.forEach { a.put(it) } }

	private fun intList(a: JSONArray): List<Int> = (0 until a.length()).map { a.getInt(it) }
}
