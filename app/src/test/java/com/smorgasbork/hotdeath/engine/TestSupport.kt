package com.smorgasbork.hotdeath.engine

import kotlinx.coroutines.runBlocking
import kotlin.random.Random

/** Fixed settings for tests: every seat is a computer unless listed in [humanSeats]. */
class TestConfig(
	override val humanSeats: Set<Seat> = emptySet(),
	override val faceUp: Boolean = false,
	override val pauseLength: Int = 0,
	override val eliminationMode: Boolean = false,
	override val autoUno: Boolean = true,
	override val jumpInMs: Long = 0,
	private val skill: Int = 1,
) : GameConfig {
	override fun skill(seat: Seat): Int = skill
}

/**
 * A controller that answers from a script: [plays] are tried in order on
 * turns and answers, otherwise it passes or draws. Victims and colors are
 * fixed, cards chosen are the first offered, and it never jumps in unless
 * [jumpWith] says so.
 */
class Scripted(
	val plays: ArrayDeque<CardType> = ArrayDeque(),
	var victim: Seat? = null,
	var color: CardColor = CardColor.RED,
	var jumpWith: CardType? = null,
	var takeAttacks: Boolean = true,
) : PlayerController {
	override suspend fun takeTurn(game: Game, me: Player): TurnAction {
		val t = plays.removeFirstOrNull() ?: return TurnAction.Draw
		return me.hand.cards.firstOrNull { it.type === t }?.let { TurnAction.Play(it) } ?: TurnAction.Draw
	}

	override suspend fun answer(game: Game, me: Player, attack: Attack): TurnAction {
		val t = plays.firstOrNull()
		val c = game.answers(me, attack).firstOrNull { it.type === t }
		if (c != null) {
			plays.removeFirst()
			return TurnAction.Play(c)
		}
		return TurnAction.Pass
	}

	override suspend fun chooseColor(game: Game, me: Player): CardColor = color
	override suspend fun chooseVictim(game: Game, me: Player, reason: Reason, options: List<Seat>, optional: Boolean): Seat? =
		victim?.takeIf { it in options } ?: options.first()

	override suspend fun chooseCard(game: Game, me: Player, reason: Reason, options: List<Card>, optional: Boolean): Card? =
		if (optional) null else options.first()

	override fun jumpInDelay(game: Game, me: Player, card: Card, windowMs: Long): Long? =
		if (jumpWith != null && card.type === jumpWith) 0 else null
}

fun t(s: Special, color: CardColor = s.replaces.first()) = CardType.special(s, color)
fun plain(c: CardColor, f: Face) = CardType.plain(c, f)

/**
 * A game set up mid-round with scripted players: SOUTH to play on [top]
 * with [color] current. Hands not given get five plain cards that cannot
 * be played on much (yellow numbers).
 */
class Table(
	top: CardType,
	color: CardColor = top.color,
	hands: Map<Seat, List<CardType>>,
	config: TestConfig = TestConfig(),
	seed: Int = 1,
) {
	val scripts = Seat.entries.associateWith { Scripted() }
	val game = Game.newGame(config, Random(seed), pacing = false) { scripts[it]!! }
	private val used = HashSet<Card>()

	fun card(type: CardType): Card {
		val c = game.deck.cards.first { it.type === type && it !in used }
		used += c
		return c
	}

	init {
		for (p in game.players) p.resetRound(false)
		val topCard = card(top)
		game.discardPile.add(topCard)
		game.topType = top
		game.currColor = color
		for (p in game.players) {
			val types = hands[p.seat] ?: List(5) { plain(CardColor.YELLOW, Face.NUMBERS[(it + 3) % 10]) }
			for (ty in types) p.hand.add(card(ty))
		}
		// Draw pile: everything else, plain cards on top so draws are predictable.
		val rest = game.deck.cards.filter { it !in used }
		game.drawPile.addAll(rest.filter { it.special != null } + rest.filter { it.special == null })
		game.currPlayer = game.player(Seat.SOUTH)
		game.dealer = game.player(Seat.EAST)
		game.restoreRoundState(false, null, null)
	}

	fun p(s: Seat) = game.player(s)
	val south get() = p(Seat.SOUTH)
	val west get() = p(Seat.WEST)
	val north get() = p(Seat.NORTH)
	val east get() = p(Seat.EAST)

	fun script(s: Seat) = scripts[s]!!

	/** SOUTH plays the first card of [type] from their hand. */
	fun play(type: CardType, by: Seat = Seat.SOUTH) = runBlocking {
		val pl = p(by)
		game.currPlayer = pl
		game.playCard(pl, pl.hand.cards.first { it.type === type })
	}
}

/** Every card of the deck is in exactly one place. */
fun Game.assertCardsConserved() {
	val all = drawPile.asList + discardPile.asList + recyclePiles.flatMap { it.pile.asList } + neutral +
		players.flatMap { it.hand.cards + it.field } + (attack?.pile ?: emptyList()) +
		// a Virus on its way to its victim
		listOfNotNull(attack?.physical).filter { v -> players.none { v in it.field } }
	if (all.isEmpty()) return // nothing dealt yet
	val dupes = all.groupBy { it }.filterValues { it.size > 1 }.keys
	check(dupes.isEmpty()) { "in two places: $dupes" }
	check(all.size == deck.size) { "expected ${deck.size} cards in play, found ${all.size}" }
}
