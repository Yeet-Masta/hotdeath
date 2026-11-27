package com.smorgasbork.hotdeath.engine

/**
 * The computer player. Plays to win in normal mode and to get out in
 * elimination mode, with a little randomness that shrinks as skill grows.
 */
class ComputerController : PlayerController {

	private fun skill(game: Game, me: Player) = game.config.skill(me.seat)
	private fun elim(game: Game) = game.config.eliminationMode

	/** The opponent closest to going out. */
	private fun leader(game: Game, options: List<Seat>): Seat =
		options.minWith(compareBy<Seat> { game.player(it).hand.size }.thenBy { game.random.nextInt(100) })

	override suspend fun takeTurn(game: Game, me: Player): TurnAction {
		game.waitABit()
		me.drawnThisTurn?.let { drawn ->
			// Apocalypse rules: play the drawn card if possible.
			return if (drawn in game.legalPlays(me)) TurnAction.Play(drawn) else TurnAction.Pass
		}
		if (game.canChangeColor(me)) {
			val best = bestColor(game, me)
			if (best != game.currColor) return TurnAction.ChangeColor(best)
		}
		val targets = game.invadeTargets(me)
		val bigArmy = me.hand.cards.mapNotNull { it.type.number }.maxOrNull() ?: 0
		if (targets.isNotEmpty() && bigArmy >= 7 && game.random.nextFloat() < 0.6f) {
			return TurnAction.Invade(targets[game.random.nextInt(targets.size)])
		}
		val legal = game.legalPlays(me)
		if (legal.isEmpty()) return TurnAction.Draw
		if (skill(game, me) == 0 && game.random.nextFloat() < 0.4f) return TurnAction.Play(legal[game.random.nextInt(legal.size)])
		return TurnAction.Play(legal.maxBy { score(game, me, it) + game.random.nextFloat() })
	}

	private fun score(game: Game, me: Player, c: Card): Float {
		val elim = elim(game)
		val nextUp = game.step(me)
		val danger = nextUp.hand.size <= 2
		val colorCount = me.hand.countColor(c.color).toFloat()
		var s = colorCount
		val sp = c.special
		if (sp == null) {
			when (c.face) {
				Face.WILD, Face.WILD_DRAW_FOUR -> s -= if (me.hand.size <= 3) 0f else 8f
				Face.DRAW_TWO, Face.SKIP -> s += if (danger) 15f else 3f
				else -> {}
			}
			if (c.face == Face.WILD_DRAW_FOUR) s += if (danger) 15f else 4f
			return s
		}
		s += when (sp) {
			Special.MAD, Special.MARTYR -> if (elim) 60f else -100f
			Special.QUITTER -> if (elim) 100f else 30f
			Special.LUCK, Special.BLUE_SHIELD, Special.CONQUEST, Special.PROPHET, Special.YIN, Special.YANG -> 25f
			Special.DEATH, Special.VIRUS, Special.GLASNOST -> 15f
			Special.FAMINE, Special.WAR -> if (me.hand.size <= 4) 10f else -5f
			Special.CURE -> if (me.virusCount > 0) 40f else -5f
			Special.I_DONT_LIKE_THESE -> if (me.hand.size >= 9) 15f else -20f
			Special.COMMUNISM -> {
				val avg = game.activePlayers.sumOf { it.hand.size }.toFloat() / game.activePlayerCount
				if (me.hand.size > avg + 1) 25f else -40f
			}
			Special.NECROMANCER -> if (game.players.any { it.status == Status.REMOVED }) (if (elim) 20f else -30f) else 0f
			Special.HOLY_DEFENDER, Special.FUCK_YOU, Special.FTPIP, Special.DODGE, Special.AIDS,
			Special.MAGIC_5, Special.PENN_STATE -> -25f
			Special.SPREADER -> 15f
			Special.HOT_DEATH, Special.HARVESTER, Special.DELAYED_BLAST, Special.BACKSTAB,
			Special.TASTE_THE_RAINBOW, Special.MYSTERY_DRAW -> if (danger) 20f else 6f
			Special.CHOSEN_ONE, Special.GIFT, Special.GOLD_COIN, Special.PING -> 6f
			Special.DOUBLE_SKIP, Special.REVERSE_SKIP -> if (danger) 12f else 3f
			else -> 0f
		}
		return s
	}

	override suspend fun answer(game: Game, me: Player, attack: Attack): TurnAction {
		game.waitABit(2)
		val opts = game.answers(me, attack)
		if (opts.isEmpty()) return TurnAction.Pass
		fun has(s: Special) = opts.firstOrNull { it.special == s }
		val stack = opts.filter { it.special !in DEFENSES }
		val serious = when (attack.kind) {
			AttackKind.QUIT -> !elim(game)
			AttackKind.GLASNOST -> me.hand.has(Special.QUITTER)
			AttackKind.PING, AttackKind.VIRUS -> false
			AttackKind.DRAW -> attack.amount + attack.pile.size >= 4
			AttackKind.RAINBOW -> true
		}
		if (attack.kind == AttackKind.QUIT && elim(game)) return TurnAction.Pass
		has(Special.MAGIC_5)?.let { return TurnAction.Play(it) }
		if (stack.isNotEmpty() && (serious || game.random.nextFloat() < 0.7f)) return TurnAction.Play(stack.first())
		if (!serious && skill(game, me) > 0 && game.random.nextFloat() < 0.6f) return TurnAction.Pass
		for (s in listOf(Special.HOLY_DEFENDER, Special.FUCK_YOU, Special.FTPIP, Special.DODGE, Special.AIDS)) {
			has(s)?.let { return TurnAction.Play(it) }
		}
		return TurnAction.Pass
	}

	private fun bestColor(game: Game, me: Player): CardColor {
		val ban = me.bannedColor
		return CardColor.SUITS.filter { it != ban }.maxBy { me.hand.countColor(it) * 10 + game.random.nextInt(10) }
	}

	override suspend fun chooseColor(game: Game, me: Player): CardColor {
		game.waitABit(2)
		return bestColor(game, me)
	}

	override suspend fun chooseVictim(game: Game, me: Player, reason: Reason, options: List<Seat>, optional: Boolean): Seat? {
		game.waitABit(2)
		if (options.isEmpty()) return null
		return when (reason) {
			Reason.NECROMANCER, Reason.PROPHET -> options[game.random.nextInt(options.size)]
			else -> leader(game, options)
		}
	}

	override suspend fun chooseCard(game: Game, me: Player, reason: Reason, options: List<Card>, optional: Boolean): Card? {
		game.waitABit(2)
		if (options.isEmpty()) return null
		return when (reason) {
			// Slip someone the Quitter, then accuse them.
			Reason.GIFT_CARD -> options.firstOrNull { it.special == Special.QUITTER }
				?: options.filter { it.special == null }.minByOrNull { me.hand.countColor(it.color) }
				?: options.first()
			Reason.ARMY -> if (elim(game)) {
				options.firstOrNull { it.special == Special.QUITTER } ?: options.maxBy { it.type.number ?: 0 }
			} else {
				options.filter { it.special != Special.QUITTER }.maxByOrNull { it.type.number ?: 0 } ?: options.first()
			}
			Reason.CHOSEN_ONE -> options.maxBy {
				(if (it.special != null) 20 else 0) + (if (it.color == CardColor.WILD) 10 else me.hand.countColor(it.color))
			}
			Reason.REMOVE_HORSEMAN -> options.firstOrNull { c -> c.special == Special.DEATH && c in me.field }
				?: options.firstOrNull { c -> c.special == Special.CONQUEST && c !in me.field }
				?: options.firstOrNull { it.special == Special.WAR }
			else -> options[game.random.nextInt(options.size)]
		}
	}

	override fun jumpInDelay(game: Game, me: Player, card: Card, windowMs: Long): Long? {
		val s = card.special
		if (!elim(game) && (s == Special.MAD || s == Special.MARTYR)) return null
		val chance = when (skill(game, me)) { 0 -> 0.35f; 1 -> 0.55f; else -> 0.75f }
		if (game.random.nextFloat() >= chance) return null
		if (windowMs <= 0) return 0
		return 300 + game.random.nextLong(maxOf(1, windowMs - 300))
	}

	/** Someone this player knows is holding the Quitter. */
	fun accusation(game: Game, me: Player): Seat? = game.players.firstOrNull { q ->
		q !== me && q.active && q.hand.cards.any { c ->
			c.special == Special.QUITTER && (q.hand.isVisible(c) || c in me.peeked || c in q.hand.revealedCards)
		}
	}?.seat

	/** Now and then, ask the Prophet about the Quitter. */
	fun prophetQuestion(game: Game, me: Player): CardType? =
		if (game.random.nextFloat() < 0.3f) CardType.special(Special.QUITTER) else null

	companion object {
		private val DEFENSES = setOf(
			Special.HOLY_DEFENDER, Special.FUCK_YOU, Special.FTPIP, Special.DODGE, Special.AIDS, Special.MAGIC_5,
		)
	}
}
