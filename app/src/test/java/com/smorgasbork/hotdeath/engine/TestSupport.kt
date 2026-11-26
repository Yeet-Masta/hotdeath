package com.smorgasbork.hotdeath.engine

import kotlin.random.Random

/** Fixed settings for tests: every seat is a computer unless listed in [humanSeats]. */
class TestConfig(
	override val deckType: DeckType = DeckType.STANDARD,
	override val humanSeats: Set<Seat> = emptySet(),
	override val faceUp: Boolean = false,
	override val cheatLevel: Int = 0,
	override val pauseLength: Int = 0,
	private val skill: Int = 1,
) : GameConfig {
	override fun skill(seat: Seat): Int = skill
	override fun aggression(seat: Seat): Int = 0
}

/** Takes distinct cards of the requested types out of a deck. */
class CardPicker(val deck: CardDeck) {
	private val used = HashSet<Int>()

	fun take(t: CardType): Card {
		val c = deck.cards.firstOrNull { it.type == t && it.deckIndex !in used }
			?: error("no unused $t in ${deck.deckType}")
		used += c.deckIndex
		return c
	}

	fun claim(c: Card): Card {
		check(used.add(c.deckIndex)) { "$c already taken" }
		return c
	}

	fun rest(): List<Card> = deck.cards.filter { it.deckIndex !in used }
}

fun handOf(vararg types: CardType, deckType: DeckType = DeckType.EXTENDED): Hand {
	val p = CardPicker(CardDeck(deckType))
	return Hand(true).apply { types.forEach { add(p.take(it)) } }
}

val NO_PENALTY = PenaltySnapshot(PenaltyType.NONE.id, 0, false, 0, 0, 0, -1)

/**
 * A game restored mid-round: SOUTH (a human) to play on [top] with [color]
 * current, holding [south]. Each computer holds five cards.
 */
fun gameAt(
	deckType: DeckType,
	top: CardType,
	color: CardColor,
	south: List<CardType>,
	penalty: (CardPicker, Card) -> PenaltySnapshot = { _, _ -> NO_PENALTY },
): Game {
	val deck = CardDeck(deckType)
	val p = CardPicker(deck)
	val topCard = p.take(top)
	val southCards = south.map(p::take)
	val pen = penalty(p, topCard)
	val others = List(3) { p.rest().take(5).map(p::claim) }
	val hands = listOf(southCards) + others
	val players = hands.mapIndexed { i, cards ->
		PlayerSnapshot(true, 0, 0, false, -1, 0, 0, emptyList(), cards.map { it.deckIndex }, emptyList(), i == 0)
	}
	val s = GameSnapshot(
		deckType = deckType,
		drawPile = p.rest().map { it.deckIndex },
		discardPile = listOf(topCard.deckIndex),
		currColor = color.id,
		direction = Direction.CLOCKWISE.id,
		cardsPlayed = 1,
		roundComplete = false,
		currCard = topCard.deckIndex,
		prevCard = -1,
		currPlayer = Seat.SOUTH.number,
		dealer = Seat.EAST.number,
		nextPlayerPreset = 0,
		players = players,
		penalty = pen,
	)
	return Game.restore(s, TestConfig(deckType, humanSeats = setOf(Seat.SOUTH)), Random(1), pacing = false)
}

fun Game.check(t: CardType): PlayCheck = evaluate(southPlayer.hand, southPlayer.hand.cards.first { it.type == t })

/** Every card of the deck is in exactly one place. */
fun Game.assertCardsConserved() {
	val all = drawPile.asList + discardPile.asList + players.flatMap { it.hand.cards }
	if (all.isEmpty()) return // nothing dealt yet
	check(all.toSet().size == all.size) { "a card is in two places" }
	// When everyone else is ejected, the last player's hand is thrown away (as in the original).
	if (activePlayerCount == 1 || roundComplete) return
	check(all.size == deck.size) { "expected ${deck.size} cards in play, found ${all.size}" }
}
