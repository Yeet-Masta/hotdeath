package com.smorgasbork.hotdeath.engine

/**
 * The computer player.
 *
 * Every legal play is scored on what it does for this player:
 *  - shedding a card, and how many of the cards left can follow it;
 *  - keeping wilds and defense cards for when they are needed;
 *  - hurting whoever is closest to going out, and protecting itself when it
 *    is close (or, in elimination mode, getting out at all);
 *  - what each special card is worth in the current situation.
 *
 * Skill (0 easy .. 2 hard) sets how much noise is added to the scores, how
 * often it jumps in, and whether it uses what it has seen of other hands.
 */
class ComputerController : PlayerController {

	private fun skill(game: Game, me: Player) = game.config.skill(me.seat).coerceIn(0, 2)
	private fun elim(game: Game) = game.config.eliminationMode

	// ── reading the table ─────────────────────────────────────────────────────

	/** How close [p] is to going out: 1 with one card, falling off quickly. */
	private fun threat(p: Player): Float = when (p.hand.size) {
		0 -> 0f
		1 -> 1f
		2 -> 0.7f
		3 -> 0.4f
		4 -> 0.2f
		else -> 0.08f
	}

	/** People on the same Yin/Yang team are not attacked. */
	private fun friend(me: Player, p: Player) = p === me || p === me.teammate

	private fun opponents(game: Game, me: Player) = game.activePlayers.filter { !friend(me, it) }

	/** The opponent closest to going out (ties broken at random). */
	private fun leader(game: Game, me: Player, options: List<Seat>): Seat {
		val foes = options.filter { !friend(me, game.player(it)) }.ifEmpty { options }
		return foes.minWith(compareBy<Seat> { game.player(it).hand.size }.thenBy { game.random.nextInt(100) })
	}

	private fun isDefense(c: Card) = c.special in DEFENSES

	/** Which stack [c] can join when answering an attack, if any. */
	private fun stackFamily(c: Card): StackFamily? = when {
		c.special == Special.SPREADER || c.special == Special.MYSTERY_DRAW || c.special == Special.TASTE_THE_RAINBOW -> null
		c.special == Special.BACKSTAB || (c.special == null && c.face == Face.DRAW_TWO) -> StackFamily.TWO
		c.special == Special.HOT_DEATH || c.special == Special.HARVESTER || c.special == Special.DELAYED_BLAST ||
			(c.special == null && c.face == Face.WILD_DRAW_FOUR) -> StackFamily.FOUR
		else -> null
	}

	/** Cards of [me]'s hand (without [played]) that could follow a card of [color]/[face]. */
	private fun followers(me: Player, played: Card, color: CardColor, face: Face): Int =
		me.hand.cards.count { it !== played && (it.color == color || it.face == face || it.face.isWild || it.special in ANYWHERE) }

	/** The color this player would name: the one it holds the most of, by count and then by value. */
	private fun bestColor(game: Game, me: Player, without: Card? = null): CardColor {
		val ban = me.bannedColor
		val cards = me.hand.cards.filter { it !== without && it.color != CardColor.WILD }
		return CardColor.SUITS.filter { it != ban }.maxBy { col ->
			val mine = cards.filter { it.color == col }
			mine.size * 10 + mine.count { it.special != null } * 3 + game.random.nextInt(3)
		}
	}

	// ── turns ─────────────────────────────────────────────────────────────────

	override suspend fun takeTurn(game: Game, me: Player): TurnAction {
		game.waitABit()
		me.drawnThisTurn?.let { drawn ->
			// Apocalypse rules: play the drawn card if possible.
			return if (drawn in game.legalPlays(me)) TurnAction.Play(drawn) else TurnAction.Pass
		}
		if (game.canChangeColor(me)) {
			val best = bestColor(game, me)
			if (best != game.currColor && me.hand.countColor(game.currColor) < me.hand.countColor(best)) return TurnAction.ChangeColor(best)
		}
		invasion(game, me)?.let { return it }

		val legal = game.legalPlays(me)
		if (legal.isEmpty()) return TurnAction.Draw
		val sk = skill(game, me)
		val noise = when (sk) { 0 -> 14f; 1 -> 4f; else -> 0.5f }
		return TurnAction.Play(legal.maxBy { score(game, me, it) + game.random.nextFloat() * noise })
	}

	/** Invade only with an army likely to win, against someone who can be hurt by losing theirs. */
	private fun invasion(game: Game, me: Player): TurnAction? {
		val targets = game.invadeTargets(me)
		if (targets.isEmpty()) return null
		val army = me.hand.cards.filter { it.special == null || it.special == Special.SIXTY_NINE }.mapNotNull { it.type.number }.maxOrNull() ?: return null
		val chance = when {
			army >= 9 -> 0.8f
			army >= 7 -> 0.5f
			else -> 0f
		}
		if (game.random.nextFloat() >= chance) return null
		return TurnAction.Invade(leader(game, me, targets))
	}

	private fun score(game: Game, me: Player, c: Card): Float {
		val elim = elim(game)
		val handAfter = me.hand.size - 1
		val next = game.step(me)
		val nextThreat = if (friend(me, next)) 0f else threat(next)
		val maxThreat = opponents(game, me).maxOfOrNull { threat(it) } ?: 0f
		val urgent = maxThreat >= 0.7f
		val sp = c.special

		// Shedding is the point of the game; going out wins it.
		var s = 10f
		if (handAfter == 0) s += 200f

		// How well the rest of the hand follows this card.
		val color = if (c.color == CardColor.WILD) bestColor(game, me, c) else c.color
		val follow = followers(me, c, color, c.face)
		s += follow * 2.5f
		if (c.color != CardColor.WILD) s += me.hand.countColor(c.color) * 1.5f

		// Keep the last card playable: a wild or a defense is the best card to finish on.
		if (handAfter == 1) {
			val last = me.hand.cards.first { it !== c }
			if (last.face.isWild) s += 15f
			if (follow == 0) s -= 20f
		}

		// Wilds are flexible: spend them early only when the hand is small or someone is close.
		if (c.face.isWild && sp == null) s -= if (me.hand.size <= 3 || urgent) 0f else 12f
		if (c.face == Face.WILD_DRAW_FOUR && sp == null) s += 4f + 30f * nextThreat

		// Plain attacks and turn-stealers: best against a next player who is close to going out.
		if (sp == null) when (c.face) {
			Face.DRAW_TWO -> s += 3f + 25f * nextThreat
			Face.SKIP -> s += 2f + 25f * nextThreat
			Face.REVERSE -> s += 1f + 15f * (nextThreat - threat(game.before(me)).coerceAtMost(1f))
			else -> {}
		}

		// Stackable attacks are also the best answer to an attack: keep one back
		// unless it can hurt someone close to going out.
		val family = stackFamily(c)
		if (family != null && handAfter > 1) {
			val sameFamily = me.hand.cards.count { it !== c && stackFamily(it) == family }
			val reserve = if (family == StackFamily.FOUR) 14f else 7f
			if (sameFamily == 0) s -= reserve * (1f - nextThreat)
		}

		// Defenses are worth more in hand than on the pile.
		if (isDefense(c)) s -= 22f + (if (me.hand.cards.count { isDefense(it) } <= 1) 10f else 0f)

		if (sp != null) s += special(game, me, c, sp, nextThreat, maxThreat, elim)
		return s
	}

	private fun special(game: Game, me: Player, c: Card, sp: Special, nextThreat: Float, maxThreat: Float, elim: Boolean): Float {
		val hand = me.hand.size
		val avg = game.activePlayers.sumOf { it.hand.size }.toFloat() / game.activePlayerCount.coerceAtLeast(1)
		return when (sp) {
			// Leaving the round: winning in elimination mode, losing otherwise.
			Special.MAD -> if (elim) 80f else if (maxThreat >= 1f && hand > 6) 10f else -120f
			Special.MARTYR -> if (elim) 70f else -150f
			Special.QUITTER -> if (elim) 90f else 20f + 40f * nextThreat

			// Field cards help every turn they stay out.
			Special.LUCK, Special.BLUE_SHIELD, Special.YIN, Special.YANG -> 30f
			Special.PROPHET -> 22f
			Special.CONQUEST -> 18f
			Special.DEATH -> 10f + 40f * maxThreat
			Special.VIRUS -> 12f + 25f * maxThreat
			Special.FAMINE, Special.WAR -> if (hand <= 4) 12f else -6f
			Special.PLAGUE -> if (hand <= 3) 8f else -4f
			Special.CURE -> if (me.virusCount > 0) 45f else -6f

			Special.GLASNOST -> 8f + (if (game.players.any { it !== me && it.active && it.hand.has(Special.QUITTER) }) 30f else 0f)
			Special.I_DONT_LIKE_THESE -> if (hand >= 9) 12f else -25f
			Special.COMMUNISM -> (hand - avg) * 8f - 4f
			Special.SWAP -> {
				val before = game.before(me)
				if (friend(me, before)) -40f else (hand - 1 - before.hand.size) * 7f
			}
			Special.NECROMANCER -> if (game.players.any { it.status == Status.REMOVED }) (if (elim) 15f else -35f) else 0f

			// Spreader hits everyone and you go again.
			Special.SPREADER -> 14f + 20f * maxThreat
			Special.HOT_DEATH -> 8f + 45f * nextThreat
			Special.HARVESTER -> 6f + 40f * nextThreat
			Special.DELAYED_BLAST -> 6f + 35f * threat(game.step(game.step(me)))
			Special.BACKSTAB -> 6f + 30f * threat(game.before(me))
			Special.TASTE_THE_RAINBOW -> 6f + 30f * nextThreat
			Special.MYSTERY_DRAW -> {
				val n = game.topType?.number ?: 0
				if (n in 1..9 || n == 69) 4f + n * 2f * (0.5f + nextThreat) else -6f
			}
			Special.PING, Special.GOLD_COIN, Special.GIFT -> 6f + 10f * maxThreat
			Special.CHOSEN_ONE -> if (hand <= 4) 8f else 2f
			Special.DOUBLE_SKIP, Special.REVERSE_SKIP -> 3f + 25f * nextThreat
			Special.RECYCLER -> 2f
			Special.FLOOD -> {
				val mineLost = me.field.count { it.special in FLOODED }
				val theirsLost = game.players.filter { !friend(me, it) }.sumOf { p -> p.field.count { it.special in FLOODED } }
				(theirsLost - mineLost) * 12f
			}
			Special.CLONE -> 4f
			// Defenses: [score] already charges for spending them.
			else -> 0f
		}
	}

	// ── answering attacks ─────────────────────────────────────────────────────

	override suspend fun answer(game: Game, me: Player, attack: Attack): TurnAction {
		game.waitABit(2)
		val opts = game.answers(me, attack)
		if (opts.isEmpty()) return TurnAction.Pass
		val elim = elim(game)
		if (attack.kind == AttackKind.QUIT && elim) return TurnAction.Pass

		fun has(s: Special) = opts.firstOrNull { it.special == s }
		// Magic 5 against Hot Death costs nothing worth keeping.
		has(Special.MAGIC_5)?.let { return TurnAction.Play(it) }

		val cost: Float = when (attack.kind) {
			AttackKind.QUIT -> 1000f
			AttackKind.GLASNOST -> if (me.hand.has(Special.QUITTER)) 1000f else 1f
			AttackKind.PING -> 1.5f
			AttackKind.VIRUS -> 8f
			AttackKind.RAINBOW -> 5f
			AttackKind.DRAW -> game.drawCount(me, attack.amount, Game.DrawKind.ATTACK).toFloat() + attack.pile.size
		}
		// Each card drawn hurts more the closer we are to going out.
		val pain = cost * (1f + 2f * threat(me))

		// Stacking passes the attack on and sheds a card: always better than taking it.
		val stack = opts.filter { !isDefense(it) }
		if (stack.isNotEmpty()) {
			val pick = stack.maxBy { if (it.face.isWild) 0 else me.hand.countColor(it.color) }
			return TurnAction.Play(pick)
		}

		// A defense is worth spending when the attack hurts more than the card is worth keeping.
		val defenses = me.hand.cards.count { isDefense(it) }
		val keep = if (defenses >= 2) 1.5f else 3f
		if (pain >= keep || cost >= 1000f) {
			for (s in DEFENSE_ORDER) has(s)?.let { return TurnAction.Play(it) }
		}
		return TurnAction.Pass
	}

	override suspend fun chooseColor(game: Game, me: Player): CardColor {
		game.waitABit(2)
		return bestColor(game, me)
	}

	override suspend fun chooseVictim(game: Game, me: Player, reason: Reason, options: List<Seat>, optional: Boolean): Seat? {
		game.waitABit(2)
		if (options.isEmpty()) return null
		return when (reason) {
			// Bring back whoever helps most: in elimination mode anyone (one more player to outlast).
			Reason.NECROMANCER -> options.minBy { game.player(it).hand.size + game.random.nextInt(3) }
			// Watch the player most likely to go out.
			Reason.PROPHET -> leader(game, me, options)
			else -> leader(game, me, options)
		}
	}

	override suspend fun chooseCard(game: Game, me: Player, reason: Reason, options: List<Card>, optional: Boolean): Card? {
		game.waitABit(2)
		if (options.isEmpty()) return null
		val elim = elim(game)
		return when (reason) {
			// Slip someone the Quitter (then accuse them); otherwise the card we can use least.
			Reason.GIFT_CARD -> options.firstOrNull { it.special == Special.QUITTER }
				?: options.filter { !isDefense(it) && !it.face.isWild }.minByOrNull { me.hand.countColor(it.color) * 10 + (if (it.special != null) 5 else 0) }
				?: options.first()
			Reason.ARMY -> if (elim) {
				options.firstOrNull { it.special == Special.QUITTER } ?: options.maxBy { it.type.number ?: 0 }
			} else {
				options.filter { it.special != Special.QUITTER }.maxByOrNull { it.type.number ?: 0 } ?: options.first()
			}
			Reason.CHOSEN_ONE -> options.maxBy {
				(if (isDefense(it)) 30 else 0) + (if (it.face.isWild) 20 else 0) +
					(if (it.special != null) 10 else 0) + me.hand.countColor(it.color)
			}
			Reason.REMOVE_HORSEMAN -> options.firstOrNull { c -> c.special == Special.DEATH && c in me.field }
				?: options.firstOrNull { c -> c.special == Special.FAMINE }
				?: options.firstOrNull { c -> c.special == Special.CONQUEST && c !in me.field }
				?: options.firstOrNull { it.special == Special.WAR }
			else -> options[game.random.nextInt(options.size)]
		}
	}

	override fun jumpInDelay(game: Game, me: Player, card: Card, windowMs: Long): Long? {
		val s = card.special
		if (!elim(game) && (s == Special.MAD || s == Special.MARTYR)) return null
		// Do not throw away a defense just to jump in.
		if (card.special in DEFENSES && me.hand.size > 2) return null
		val chance = when (skill(game, me)) { 0 -> 0.35f; 1 -> 0.6f; else -> 0.85f }
		if (game.random.nextFloat() >= chance) return null
		if (windowMs <= 0) return 0
		// Better players react faster.
		val slowest = when (skill(game, me)) { 0 -> windowMs; 1 -> windowMs * 3 / 4; else -> windowMs / 2 }
		return 250 + game.random.nextLong(maxOf(1, slowest - 250))
	}

	/** Someone this player knows is holding the Quitter. */
	fun accusation(game: Game, me: Player): Seat? = game.players.firstOrNull { q ->
		q !== me && q.active && q.hand.cards.any { c ->
			c.special == Special.QUITTER && (q.hand.isVisible(c) || c in me.peeked || c in q.hand.revealedCards)
		}
	}?.seat

	/** Ask the Prophet about the Quitter now and then; good players ask more. */
	fun prophetQuestion(game: Game, me: Player): CardType? {
		val chance = when (skill(game, me)) { 0 -> 0.15f; 1 -> 0.3f; else -> 0.5f }
		return if (game.random.nextFloat() < chance) CardType.special(Special.QUITTER) else null
	}

	companion object {
		private val DEFENSES = setOf(
			Special.HOLY_DEFENDER, Special.FUCK_YOU, Special.FTPIP, Special.DODGE, Special.AIDS, Special.MAGIC_5,
			Special.PENN_STATE,
		)
		/** Cheapest defense first; Fuck You and FTPIP also send the attack somewhere useful. */
		private val DEFENSE_ORDER = listOf(
			Special.FTPIP, Special.FUCK_YOU, Special.DODGE, Special.AIDS, Special.HOLY_DEFENDER,
		)
		/** Cards that play on anything. */
		private val ANYWHERE = setOf(Special.FUCK_YOU, Special.MAGIC_5, Special.CLONE)
		/** Field cards The Flood discards. */
		private val FLOODED = setOf(
			Special.LUCK, Special.BLUE_SHIELD, Special.PROPHET, Special.VIRUS, Special.FAMINE, Special.PLAGUE,
		)
	}
}
