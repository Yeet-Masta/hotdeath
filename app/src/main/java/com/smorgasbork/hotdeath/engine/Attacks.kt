package com.smorgasbork.hotdeath.engine

enum class AttackKind {
	/** Draw [Attack.amount] cards (plus any recycle pile). */
	DRAW,
	/** Quitter: removed from the round. */
	QUIT,
	/** Hand laid out face up. */
	GLASNOST,
	/** One card, never changed by anything. */
	PING,
	/** Infected. */
	VIRUS,
	/** Taste the Rainbow: draw until all four colors or a wild. */
	RAINBOW,
}

/** Stacking families: Draw Twos on Draw Twos, Draw Fours (and Eights) on Draw Fours. */
enum class StackFamily { TWO, FOUR }

/**
 * An attack on its way to a victim. Answers (stacking, defenses) change it
 * until someone takes it or it is blocked.
 */
class Attack(
	val kind: AttackKind,
	/** The card that started it. */
	val card: CardType,
	/** The physical card that ends up in front of the victim (Virus). */
	val physical: Card?,
	var amount: Int,
	val family: StackFamily?,
	/** Who started it (Fuck You sends it back here). */
	val source: Player,
	var victim: Player,
	val directed: Boolean,
	val unblockable: Boolean,
) {
	/** Who sent it to the current victim. */
	var sender: Player = source
	/** The last player who added to the stack, and how much (AIDS, Clone). */
	var lastAdder: Player = source
	var lastContribution: Int = amount
	var hotDeaths: Int = if (card.special == Special.HOT_DEATH) 1 else 0
	var hasHarvester: Boolean = card.special == Special.HARVESTER
	var harvesterLast: Boolean = card.special == Special.HARVESTER
	/** A recycle pile riding along: whoever takes the attack takes these too. */
	val pile = ArrayList<Card>()
	/** Play continues after this player. */
	var continueFrom: Player = if (directed) source else victim
	var resolved = false
}

private val ANSWER_DEFENSES = setOf(
	Special.HOLY_DEFENDER, Special.FUCK_YOU, Special.FTPIP, Special.DODGE, Special.AIDS, Special.MAGIC_5,
)

/** How much [t] adds to a stack in [family], or null if it does not stack there. */
internal fun Game.stackValue(t: CardType, a: Attack): Int? {
	val fam = a.family ?: return null
	if (a.kind != AttackKind.DRAW) return null
	return when (fam) {
		StackFamily.TWO -> when {
			t.special == Special.CLONE -> a.lastContribution
			t.special == Special.BACKSTAB -> 2
			t.special == null && t.face == Face.DRAW_TWO -> 2
			else -> null
		}
		StackFamily.FOUR -> when (t.special) {
			Special.CLONE -> a.lastContribution
			Special.HOT_DEATH -> 8
			Special.HARVESTER, Special.DELAYED_BLAST -> 4
			null -> if (t.face == Face.WILD_DRAW_FOUR) 4 else null
			else -> null
		}
	}
}

/** Cards [p] may answer [a] with. */
fun Game.answers(p: Player, a: Attack): List<Card> {
	if (apocalypse || a.victim !== p) return emptyList()
	return p.hand.cards.filter { c ->
		val s = c.special
		if (s in ANSWER_DEFENSES) canDefend(s!!, a)
		else stackValue(c.type, a) != null && !banned(p, c)
	}
}

private fun canDefend(s: Special, a: Attack): Boolean {
	val blockable = !a.unblockable && !a.hasHarvester
	return when (s) {
		Special.HOLY_DEFENDER -> blockable && a.kind != AttackKind.PING
		Special.FUCK_YOU -> blockable || (a.pile.isNotEmpty() && !a.hasHarvester)
		Special.FTPIP -> blockable
		Special.DODGE -> blockable || (a.pile.isNotEmpty() && !a.hasHarvester)
		Special.AIDS -> !a.unblockable && a.pile.isEmpty() &&
			(a.kind == AttackKind.DRAW || a.kind == AttackKind.QUIT || a.kind == AttackKind.GLASNOST)
		Special.MAGIC_5 -> a.kind == AttackKind.DRAW && a.hotDeaths > 0
		else -> false
	}
}

/** A team of two (Yin and Yang on different players) doubles its attacks. */
internal fun Game.teamFactor(p: Player, t: CardType): Int =
	if (p.teammate != null && t.special != Special.HARVESTER && t.special != Special.PING) 2 else 1

/** Starts [a] and plays it out. When it returns, [Game.next] is set. */
internal suspend fun Game.launch(a: Attack) {
	attack = a
	changed()
	try {
		resolve(a)
	} finally {
		// A blocked Virus is thrown away.
		a.physical?.let { v -> if (players.none { v in it.field }) discardPile.addBottom(v) }
		attack = null
		changed()
	}
	next = step(a.continueFrom)
}

private suspend fun Game.resolve(a: Attack) {
	while (!a.resolved && !roundComplete) {
		val v = a.victim
		if (!v.active) return
		moveTo(v)

		// Yin and Yang on one player: immune to most attacks.
		if (!apocalypse && v.hasYinAndYang && a.kind != AttackKind.QUIT && a.kind != AttackKind.PING) {
			val opts = answers(v, a).filter { stackValue(it.type, a) != null }
			val choice = if (opts.isEmpty()) TurnAction.Pass else ask(v, a)
			if (choice is TurnAction.Play && choice.card in opts) {
				answer(v, choice.card, a)
				continue
			}
			if (a.kind == AttackKind.DRAW && a.harvesterLast) {
				take(v, a)
			} else {
				message(Msg.IMMUNE, v.seat)
				if (!a.directed) a.continueFrom = v
			}
			return
		}

		val opts = answers(v, a)
		val choice = if (opts.isEmpty()) {
			waitABit(2)
			TurnAction.Pass
		} else {
			message(Msg.YOUR_ANSWER, v.seat, card = a.card, n = a.amount, to = v.seat)
			ask(v, a)
		}
		if (choice is TurnAction.Play && choice.card in opts) {
			answer(v, choice.card, a)
		} else {
			take(v, a)
			return
		}
	}
}

/** Asks [v] for an answer, handling free actions along the way. */
private suspend fun Game.ask(v: Player, a: Attack): TurnAction {
	while (true) {
		val action = decide(v, Decision.ANSWER) { v.controller.answer(this, v, a) } ?: return TurnAction.Pass
		if (action.isFree) {
			handleFree(v, action, onTurn = false)
			if (!v.active || roundComplete) return TurnAction.Pass
			continue
		}
		return action
	}
}

/** [v] answers [a] with [c]. */
private suspend fun Game.answer(v: Player, c: Card, a: Attack) {
	v.hand.remove(c)
	noteEmptied(v)
	putOnPile(c, v)
	topType = c.type
	if (c.color != CardColor.WILD) currColor = c.color
	if (v.hand.size == 1) afterPlayUno(v)
	val stack = stackValue(c.type, a)
	if (stack != null && c.special !in ANSWER_DEFENSES) {
		if (c.type.face.isWild) {
			currColor = v.controller.chooseColor(this, v)
			announceIndicator(direction, currColor)
		}
		addToStack(v, c.type, stack, a)
		// Someone holding the same card may jump in: the stack restarts at their card.
		jumpIn(c.type, v)?.let { (j, jc) ->
			j.hand.remove(jc)
			noteEmptied(j)
			putOnPile(jc, j)
			topType = jc.type
			a.amount = 0
			a.hotDeaths = 0
			a.hasHarvester = false
			addToStack(j, jc.type, stackValue(jc.type, a) ?: 2, a)
		}
		return
	}

	a.continueFrom = v
	when (c.special) {
		Special.HOLY_DEFENDER -> {
			message(Msg.BLOCKED, v.seat, card = c.type)
			a.resolved = true
			removeHorseman(v)
		}
		Special.FUCK_YOU -> {
			reverseDirection()
			val to = if (a.source !== v && a.source.active) a.source else a.sender
			a.sender = v
			a.victim = to
			a.continueFrom = to
			message(Msg.SENT_BACK, to.seat)
			shieldBreaksOnRedirect(a)
		}
		Special.FTPIP -> {
			val options = players.filter { it !== v && it.active }.map { it.seat }
			val to = player(v.controller.chooseVictim(this, v, Reason.FTPIP, options) ?: options.first())
			a.sender = v
			a.victim = to
			// Play continues after the FTPIP player unless the new victim defends.
			a.continueFrom = v
			message(Msg.REDIRECTED, to.seat, v.seat)
			shieldBreaksOnRedirect(a)
		}
		Special.DODGE -> {
			val to = step(v)
			a.sender = v
			a.victim = to
			a.continueFrom = if (a.directed) a.source else to
			message(Msg.DODGED, v.seat, to.seat)
		}
		Special.AIDS -> {
			val partner = (if (a.lastAdder !== v) a.lastAdder else a.sender).takeIf { it.active } ?: a.source
			message(Msg.SPLIT, v.seat, partner.seat, n = a.amount)
			a.resolved = true
			when (a.kind) {
				AttackKind.DRAW -> {
					val total = a.amount
					forceDraw(partner, (total + 1) / 2, Game.DrawKind.ATTACK)
					forceDraw(v, total / 2, Game.DrawKind.ATTACK)
				}
				AttackKind.QUIT -> {
					removePlayer(v, a.source, Special.QUITTER)
					if (partner !== v) removePlayer(partner, v, Special.AIDS)
				}
				AttackKind.GLASNOST -> {
					exposeHand(v)
					if (partner !== v) exposeHand(partner)
				}
				else -> {}
			}
			if (a.directed) a.continueFrom = a.source
		}
		Special.MAGIC_5 -> {
			val removed = 8 * a.hotDeaths
			a.amount = maxOf(0, a.amount - removed)
			a.hotDeaths = 0
			message(Msg.MAGIC_5, v.seat, n = removed)
			if (a.amount == 0 && a.pile.isEmpty()) a.resolved = true
		}
		else -> {}
	}
}

private fun Game.addToStack(p: Player, t: CardType, value: Int, a: Attack) {
	val contribution = value * teamFactor(p, t)
	a.amount += contribution
	a.lastAdder = p
	a.sender = p
	a.lastContribution = contribution
	if (t.special == Special.HOT_DEATH) a.hotDeaths++
	a.harvesterLast = t.special == Special.HARVESTER
	if (a.harvesterLast) a.hasHarvester = true
	if (t.special == Special.BACKSTAB) reverseDirection()
	var to = step(p)
	if (t.special == Special.DELAYED_BLAST && !duel) {
		lastSkipped += to.seat
		message(Msg.SKIPPED, to.seat)
		to = step(to)
	}
	a.victim = to
	a.continueFrom = to
	message(Msg.STACKED, p.seat, n = a.amount)
}

/** Fuck You / FTPIP aimed at a Blue Shield always break it; only a Harvester still gets through. */
private suspend fun Game.shieldBreaksOnRedirect(a: Attack) {
	val v = a.victim
	if (apocalypse || a.kind != AttackKind.DRAW) return
	val shield = v.fieldCard(Special.BLUE_SHIELD) ?: return
	breakShield(v, shield)
	if (!a.hasHarvester) {
		a.resolved = true
		if (!a.directed) a.continueFrom = v
	}
}

private suspend fun Game.breakShield(v: Player, shield: Card) {
	v.field.remove(shield)
	discardPile.addBottom(shield)
	message(Msg.SHIELD_BREAKS, v.seat)
	changed()
}

/** [v] takes [a]. */
private suspend fun Game.take(v: Player, a: Attack) {
	a.resolved = true
	if (!a.directed) a.continueFrom = v
	when (a.kind) {
		AttackKind.DRAW, AttackKind.RAINBOW -> {
			val shield = if (apocalypse || a.pile.isNotEmpty()) null else v.fieldCard(Special.BLUE_SHIELD)
			if (shield != null) {
				when {
					a.hasHarvester -> breakShield(v, shield)
					a.kind == AttackKind.RAINBOW || a.amount >= 4 -> {
						breakShield(v, shield)
						return
					}
					else -> {
						message(Msg.SHIELD_ABSORBS, v.seat)
						return
					}
				}
			}
			if (a.kind == AttackKind.RAINBOW) {
				tasteTheRainbow(v)
				return
			}
			var n = a.amount
			if (v.teammate != null && !a.hasHarvester) n = (n + 1) / 2
			message(Msg.TAKES_ATTACK, v.seat, n = n + a.pile.size, card = a.card)
			if (a.pile.isNotEmpty()) {
				message(Msg.RECYCLE_DUMP, v.seat, n = a.pile.size)
				for (c in a.pile) {
					v.hand.add(c)
					emit(GameEvent.CardDrawn(c, v.seat, animMs()))
					waitABit(15)
				}
				v.calledUno = false
				a.pile.clear()
			}
			if (n > 0) forceDraw(v, n, Game.DrawKind.ATTACK)
		}
		AttackKind.PING -> {
			message(Msg.PING, a.sender.seat, v.seat)
			forceDraw(v, 1, Game.DrawKind.PLAIN)
		}
		AttackKind.QUIT -> removePlayer(v, a.sender, Special.QUITTER)
		AttackKind.GLASNOST -> exposeHand(v)
		AttackKind.VIRUS -> {
			val virus = a.physical
			if (virus != null) {
				v.field += virus
				emit(GameEvent.CardToField(virus, a.source.seat, v.seat, animMs()))
			}
			message(Msg.VIRUS, v.seat)
			changed()
		}
	}
}

/** Draw until all four colors or any wild have come up. */
private suspend fun Game.tasteTheRainbow(v: Player) {
	val seen = HashSet<CardColor>()
	var n = 0
	while (true) {
		val c = drawOne(v) ?: break
		n++
		if (c.color == CardColor.WILD) break
		seen += c.color
		if (seen.size == 4) break
		waitABit(15)
	}
	message(Msg.DRAWING, v.seat, n = n)
}
