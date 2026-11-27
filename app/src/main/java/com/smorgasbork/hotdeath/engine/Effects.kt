package com.smorgasbork.hotdeath.engine

/*
 * Card effects. See docs/RULES.md for how each rule of the Constitution is
 * read. Every effect leaves [Game.next] set to whoever plays next.
 */

// ── piles ─────────────────────────────────────────────────────────────────────

/** Puts a played card on top of the recycle pile if one is open, else the discard pile. */
internal suspend fun Game.putOnPile(c: Card, from: Player?) {
	(recyclePiles.lastOrNull()?.pile ?: discardPile).add(c)
	emit(GameEvent.CardDiscarded(c, from?.seat, animMs()))
	waitABit(2)
}

/** The card shown on top of the play area. */
val Game.visibleTop: Card? get() = recyclePiles.lastOrNull()?.pile?.top ?: discardPile.top

internal fun Game.noteEmptied(p: Player) {
	if (p.hand.isEmpty && !p.calledUno) p.emptiedWithoutUno = true
}

private suspend fun Game.chooseColorFor(p: Player) {
	currColor = p.controller.chooseColor(this, p)
	logger("${p.seat} chose $currColor")
	announceIndicator(direction, currColor)
}

private fun Game.others(p: Player): List<Seat> = players.filter { it !== p && it.active }.map { it.seat }

/** [forced] (Clone) or [p]'s choice among the other active players. */
private suspend fun Game.pick(p: Player, reason: Reason, forced: Player?): Player? {
	if (forced != null) return forced
	val options = others(p)
	if (options.isEmpty()) return null
	return player(p.controller.chooseVictim(this, p, reason, options) ?: options.first())
}

private fun opensAttack(t: CardType, elimination: Boolean): Boolean = when (t.special) {
	null -> t.face == Face.DRAW_TWO || t.face == Face.WILD_DRAW_FOUR
	Special.BACKSTAB, Special.HARVESTER, Special.HOT_DEATH, Special.DELAYED_BLAST,
	Special.TASTE_THE_RAINBOW, Special.MYSTERY_DRAW -> true
	Special.QUITTER -> !elimination
	else -> false
}

/** "Ending on a special card will not activate", except attacks, and Gold Coin is still flipped. */
private fun activatesAsLastCard(t: CardType): Boolean =
	t.special == null || t.special.category == Category.ATTACK && t.special != Special.MAD || t.special == Special.GOLD_COIN

// ── playing a card ───────────────────────────────────────────────────────────

/** [p] plays [c] on their turn (or as a jump-in). */
internal suspend fun Game.playCard(p: Player, c: Card, jumping: Boolean = false) {
	logger("${p.seat} plays ${c.type}")
	p.hand.remove(c)
	noteEmptied(p)
	lastSkipped.clear()
	val prevTop = topType
	val prevColor = currColor
	val last = p.hand.isEmpty
	val activates = apocalypse || config.eliminationMode || !last || activatesAsLastCard(c.type)
	val toField = !apocalypse && activates && c.special?.category == Category.FIELD
	if (!toField) putOnPile(c, p)
	topType = c.type
	if (c.color != CardColor.WILD) currColor = c.color
	if (p.hand.size == 1) afterPlayUno(p)

	if (apocalypse) {
		baseEffect(p, c.type)
		return
	}

	// Attacks: an identical card jumping in replaces this one (the stack restarts at it).
	if (activates && opensAttack(c.type, config.eliminationMode)) {
		jumpIn(c.type, p)?.let { (j, jc) ->
			if (c.type.face.isWild) chooseColorFor(p)
			moveTo(j)
			playCard(j, jc, jumping = true)
			return
		}
	}

	if (activates) {
		effect(p, c, c.type, prevTop, prevColor, forced = null)
	} else {
		if (c.type.face.isWild) chooseColorFor(p)
		next = step(p)
	}
	announceIndicator(direction, currColor)

	// The Plague carrier jumping in puts the Plague on top of their card.
	if (jumping && plagueCarrier === p) endPlague(p)

	if (!opensAttack(c.type, config.eliminationMode) && !roundComplete && p.seat !in lastSkipped) {
		jumpIn(c.type, p)?.let { (j, jc) ->
			moveTo(j)
			playCard(j, jc, jumping = true)
		}
	}
}

/** The Apocalypse: every card is its base card, original Uno rules. */
private suspend fun Game.baseEffect(p: Player, t: CardType) {
	val b = t.base
	when (b.face) {
		Face.SKIP -> skip(p, 1)
		Face.REVERSE -> {
			reverseDirection()
			next = if (duel) p else step(p)
		}
		Face.DRAW_TWO -> {
			val v = step(p)
			message(Msg.TAKES_ATTACK, v.seat, n = 2, card = b)
			forceDraw(v, 2, Game.DrawKind.ATTACK)
			next = step(v)
		}
		Face.WILD -> {
			chooseColorFor(p)
			next = step(p)
		}
		Face.WILD_DRAW_FOUR -> {
			chooseColorFor(p)
			val v = step(p)
			message(Msg.TAKES_ATTACK, v.seat, n = 4, card = b)
			forceDraw(v, 4, Game.DrawKind.ATTACK)
			next = step(v)
		}
		else -> next = step(p)
	}
	announceIndicator(direction, currColor)
}

private fun Game.drawAttack(p: Player, t: CardType, amount: Int, family: StackFamily?, victim: Player, unblockable: Boolean = false) =
	Attack(AttackKind.DRAW, t, null, amount * teamFactor(p, t), family, p, victim, directed = false, unblockable = unblockable)

/**
 * The effect of [t] played by [p]. [c] is the physical card, or null when
 * Clone repeats an effect; then [forced] is the player it is aimed at.
 */
private suspend fun Game.effect(p: Player, c: Card?, t: CardType, prevTop: CardType?, prevColor: CardColor?, forced: Player?) {
	val cloning = c == null
	suspend fun color() {
		if (cloning && prevColor != null && prevColor != CardColor.WILD) currColor = prevColor else chooseColorFor(p)
	}
	next = step(p)
	when (t.special) {
		null -> when (t.face) {
			Face.SKIP -> skip(p, 1)
			Face.REVERSE -> reverseCard(p, t, reverseCard = !cloning)
			Face.DRAW_TWO -> launch(drawAttack(p, t, 2, StackFamily.TWO, forced ?: step(p)))
			Face.WILD -> color()
			Face.WILD_DRAW_FOUR -> {
				color()
				launch(drawAttack(p, t, 4, StackFamily.FOUR, forced ?: step(p)))
			}
			else -> {}
		}
		Special.SIXTY_NINE, Special.AIDS, Special.DODGE, Special.FTPIP, Special.MAGIC_5, Special.PENN_STATE -> {}
		Special.HOLY_DEFENDER -> removeHorseman(p)
		Special.FUCK_YOU -> reverseCard(p, t, reverseCard = false)
		Special.BACKSTAB -> backstab(p, t, reverseCard = !cloning)
		Special.BLUE_SHIELD, Special.LUCK, Special.CONQUEST, Special.YIN, Special.YANG, Special.PLAGUE ->
			if (c != null) toField(p, c, p)
		Special.PROPHET -> if (c != null) {
			toField(p, c, p)
			prophetLook(p)
		}
		Special.FAMINE, Special.WAR -> if (c != null) toNeutral(c, p)
		Special.DEATH -> if (c != null) {
			val v = pick(p, Reason.DEATH, forced)
			if (v == null) discardPile.addBottom(c) else {
				toField(v, c, p)
				deathFlip(v)
			}
		}
		Special.VIRUS -> if (c != null) {
			val v = pick(p, Reason.VIRUS, forced)
			if (v == null) discardPile.addBottom(c) else {
				launch(Attack(AttackKind.VIRUS, t, c, 0, null, p, v, directed = true, unblockable = false))
			}
		}
		Special.CLONE -> clone(p, prevTop, prevColor)
		Special.COMMUNISM -> communism(p)
		Special.CURE -> cure(p)
		Special.DELAYED_BLAST -> {
			color()
			var v = forced ?: step(p)
			if (!duel && forced == null) {
				lastSkipped += v.seat
				message(Msg.SKIPPED, v.seat)
				v = step(v)
			}
			launch(drawAttack(p, t, 4, StackFamily.FOUR, v))
		}
		Special.DOUBLE_SKIP -> skip(p, if (duel) 1 else 2)
		Special.GIFT -> gift(p, forced)
		Special.GLASNOST -> pick(p, Reason.GLASNOST, forced)?.let {
			launch(Attack(AttackKind.GLASNOST, t, null, 0, null, p, it, directed = true, unblockable = false))
		}
		Special.GOLD_COIN -> goldCoin(p, forced)
		Special.HARVESTER -> {
			color()
			launch(drawAttack(p, t, 4, StackFamily.FOUR, forced ?: step(p), unblockable = true))
		}
		Special.HOT_DEATH -> {
			color()
			launch(drawAttack(p, t, 8, StackFamily.FOUR, forced ?: step(p)))
		}
		Special.I_DONT_LIKE_THESE -> iDontLikeThese(p)
		Special.MAD -> pick(p, Reason.MAD, forced)?.let { v ->
			removePlayer(p, p, Special.MAD)
			removePlayer(v, p, Special.MAD)
		}
		Special.MARTYR -> martyr(p, forced)
		Special.MYSTERY_DRAW -> {
			color()
			val n = if (cloning) lastMysteryAmount else prevTop?.number ?: 0
			if (n > 0) {
				if (!cloning) lastMysteryAmount = n
				launch(drawAttack(p, t, n, null, forced ?: step(p)))
			}
		}
		Special.NECROMANCER -> necromancer(p)
		Special.PING -> pick(p, Reason.PING, forced)?.let {
			launch(Attack(AttackKind.PING, t, null, 1, null, p, it, directed = true, unblockable = false))
		}
		Special.QUITTER -> if (config.eliminationMode) {
			removePlayer(p, p, Special.QUITTER)
		} else {
			launch(Attack(AttackKind.QUIT, t, null, 0, null, p, forced ?: step(p), directed = false, unblockable = false))
		}
		Special.RECYCLER -> {
			if (cloning) reverseDirection() else reverseCard(p, t, reverseCard = true)
			recyclePiles += RecyclePile(p)
			message(Msg.RECYCLING, p.seat)
			if (attack == null && next === p && !duel) next = step(p)
		}
		Special.REVERSE_SKIP -> {
			if (!reverseCard(p, t, reverseCard = !cloning, skipAfter = true)) {
				skip(p, 1)
			}
		}
		Special.SPREADER -> spreader(p)
		Special.SWAP -> {
			swapHands(p, forced ?: before(p))
			reverseCard(p, t, reverseCard = !cloning)
		}
		Special.TASTE_THE_RAINBOW -> {
			color()
			launch(Attack(AttackKind.RAINBOW, t, null, 0, null, p, forced ?: step(p), directed = false, unblockable = false))
		}
		Special.CHOSEN_ONE -> chosenOne(p)
		Special.FLOOD -> flood(p)
	}
	changed()
}

private fun Game.skip(p: Player, k: Int) {
	var s = p
	repeat(k) {
		s = step(s)
		lastSkipped += s.seat
		message(Msg.SKIPPED, s.seat)
	}
	next = step(s)
}

/**
 * Reverses play. A reverse card also empties an open recycle pile onto the
 * new next player. Returns true if that happened (and [Game.next] is set).
 */
private suspend fun Game.reverseCard(p: Player, t: CardType, reverseCard: Boolean, skipAfter: Boolean = false): Boolean {
	reverseDirection()
	message(Msg.DIRECTION)
	if (reverseCard && dumpRecycle(p, t, extra = 0, family = null)) return true
	if (!skipAfter) next = if (duel) p else step(p)
	return false
}

private suspend fun Game.backstab(p: Player, t: CardType, reverseCard: Boolean) {
	reverseDirection()
	if (reverseCard && dumpRecycle(p, t, extra = 2 * teamFactor(p, t), family = StackFamily.TWO)) return
	launch(drawAttack(p, t, 2, StackFamily.TWO, step(p)))
}

/** Ends the innermost recycle pile: the new next player draws it (plus [extra]). */
private suspend fun Game.dumpRecycle(p: Player, t: CardType, extra: Int, family: StackFamily?): Boolean {
	val rp = recyclePiles.removeLastOrNull() ?: return false
	val cards = rp.pile.takeAll().toMutableList()
	// The reverse card itself stays in play, on the pile below.
	cards.lastOrNull()?.takeIf { it.type === t }?.let {
		cards.removeAt(cards.lastIndex)
		(recyclePiles.lastOrNull()?.pile ?: discardPile).add(it)
	}
	val victim = step(p)
	val a = Attack(AttackKind.DRAW, t, null, extra, family, rp.owner, victim, directed = false, unblockable = true)
	a.pile += cards
	launch(a)
	return true
}

private suspend fun Game.clone(p: Player, prevTop: CardType?, prevColor: CardColor?) {
	val prev = prevTop
	val s = prev?.special
	if (prev == null || s == Special.CLONE || s == Special.COMMUNISM || s?.category == Category.FIELD) return
	message(Msg.CLONE, card = prev)
	if (prev.face.isWild && prevColor != null) currColor = prevColor
	effect(p, null, prev, null, prevColor, forced = step(p))
}

// ── field cards ──────────────────────────────────────────────────────────────

/** Puts [c] in front of [owner]. */
internal suspend fun Game.toField(owner: Player, c: Card, from: Player?) {
	owner.hand.remove(c)
	owner.field += c
	emit(GameEvent.CardToField(c, from?.seat, owner.seat, animMs()))
	message(Msg.FIELD_ACTIVE, owner.seat, card = c.type)
	waitABit(2)
	when (c.special) {
		Special.PLAGUE -> if (plagueCarrier == null) plagueCarrier = owner
		Special.YIN, Special.YANG -> teamUp(owner)
		else -> {}
	}
	checkApocalypse()
	changed()
}

private suspend fun Game.toNeutral(c: Card, from: Player?) {
	neutral += c
	emit(GameEvent.CardToField(c, from?.seat, null, animMs()))
	message(Msg.FIELD_ACTIVE, null, card = c.type)
	waitABit(2)
	if (c.special == Special.WAR) war()
	checkApocalypse()
	changed()
}

/** Yin, Yang and Plague come into play the moment they are drawn. Returns true if [c] did. */
internal suspend fun Game.activateIfDrawn(p: Player, c: Card): Boolean {
	val s = c.special ?: return false
	if (apocalypse || !s.activatesWhenDrawn) return false
	if (s == Special.PLAGUE && plagueCarrier != null) return false
	toField(p, c, p)
	return true
}

/** Yin and Yang with different players: the one with more cards joins the other. */
private suspend fun Game.teamUp(p: Player) {
	val other = if (p.hasField(Special.YIN)) Special.YANG else Special.YIN
	val q = players.firstOrNull { it !== p && it.active && it.hasField(other) } ?: return
	if (p.teammate != null || q.teammate != null) return
	val (keep, drop) = if (q.hand.size > p.hand.size) p to q else q to p
	for (c in drop.hand.takeAll()) discardPile.addBottom(c)
	keep.teammate = drop
	drop.teammate = keep
	drop.status = Status.TEAMED
	if (plagueCarrier === drop) endPlague(drop)
	message(Msg.TEAM_UP, keep.seat, drop.seat)
	changed()
}

private suspend fun Game.checkApocalypse() {
	if (apocalypse) return
	val inPlay = (players.flatMap { it.field } + neutral).mapNotNull { it.special }.toSet()
	if (Special.entries.filter { it.isHorseman }.all { it in inPlay }) {
		apocalypse = true
		topType = topType?.base
		attack?.resolved = true
		message(Msg.APOCALYPSE)
		changed()
	}
}

/** Death's clock: the number on the top card of the draw pile, otherwise 4. */
private suspend fun Game.deathFlip(v: Player, clock: Int? = null) {
	if (clock != null) {
		v.deathClock = clock
	} else {
		val c = drawFromPile()
		val n = c?.type?.number
		when {
			c == null -> v.deathClock = 4
			c.special?.isHorseman == true -> {
				v.deathClock = 4
				toNeutral(c, null)
			}
			n != null -> {
				v.deathClock = n
				v.clockCard = c
				v.field += c
				emit(GameEvent.CardToField(c, null, v.seat, animMs()))
			}
			else -> {
				v.deathClock = 4
				discardPile.addBottom(c)
			}
		}
	}
	message(Msg.DEATH_CLOCK, v.seat, n = v.deathClock)
	changed()
}

/** War: everyone turns up a card; its color is banned for them. */
private suspend fun Game.war() {
	for (p in activePlayers) {
		val c = drawFromPile() ?: break
		p.warCard = c
		p.field += c
		emit(GameEvent.CardToField(c, null, p.seat, animMs()))
		message(Msg.WAR_COLOR, p.seat, card = c.type)
		waitABit(4)
	}
}

/** Holy Defender: its player may take one Horseman out of play. */
internal suspend fun Game.removeHorseman(p: Player) {
	if (apocalypse) return
	val horsemen = players.flatMap { it.field } .filter { it.special?.isHorseman == true } + neutral.filter { it.special?.isHorseman == true }
	if (horsemen.isEmpty()) return
	val c = p.controller.chooseCard(this, p, Reason.REMOVE_HORSEMAN, horsemen, optional = true) ?: return
	val holder = players.firstOrNull { c in it.field }
	if (holder != null) holder.field.remove(c) else neutral.remove(c)
	discardPile.addBottom(c)
	when (c.special) {
		Special.DEATH -> holder?.let {
			it.deathClock = -1
			it.clockCard?.let { k -> it.field.remove(k); discardPile.addBottom(k) }
			it.clockCard = null
		}
		Special.WAR -> for (q in players) {
			q.warCard?.let { k -> q.field.remove(k); discardPile.addBottom(k) }
			q.warCard = null
		}
		Special.CONQUEST -> holder?.let {
			for (k in it.armies) { it.field.remove(k); discardPile.addBottom(k) }
			it.armies.clear()
			it.conquered.clear()
		}
		else -> {}
	}
	message(Msg.FIELD_REMOVED, holder?.seat, card = c.type)
	changed()
}

private suspend fun Game.cure(p: Player) {
	val virus = p.fieldCard(Special.VIRUS) ?: return
	p.field.remove(virus)
	discardPile.addBottom(virus)
	message(Msg.CURED, p.seat)
}

private fun Game.flood(p: Player) {
	val washed = setOf(Special.LUCK, Special.BLUE_SHIELD, Special.VIRUS, Special.PROPHET, Special.PLAGUE)
	for (q in players) {
		val gone = q.field.filter { it.special in washed }
		q.field.removeAll(gone.toSet())
		gone.forEach { discardPile.addBottom(it) }
	}
	if (plagueCarrier != null) {
		plagueCarrier = null
		skipPlagueDraw = p
	}
	prophet = null
	prophetTarget = null
	neutral.filter { it.special == Special.FAMINE }.forEach {
		neutral.remove(it)
		discardPile.addBottom(it)
	}
	message(Msg.FLOOD)
}

// ── turn start, Plague ───────────────────────────────────────────────────────

/** Plague, Death and the Prophet at the start of [p]'s turn. Returns true if the turn is used up. */
internal suspend fun Game.turnStartEffects(p: Player): Boolean {
	if (plagueCarrier === p) {
		endPlague(p)
		next = step(p)
		return true
	}
	if (p.deathClock >= 0 && p.hasField(Special.DEATH)) {
		val left = p.deathClock + p.luckCount - p.virusCount
		if (left <= 0) {
			removePlayer(p, null, Special.DEATH)
			next = step(p)
			return true
		}
		p.deathClock--
		message(Msg.DEATH_CLOCK, p.seat, n = left - 1)
	}
	if (prophet === p) prophetLook(p)
	return false
}

/** The Plague goes on top of the pile. */
internal suspend fun Game.endPlague(carrier: Player) {
	val plague = carrier.fieldCard(Special.PLAGUE)
	plagueCarrier = null
	message(Msg.PLAGUE_OVER, carrier.seat)
	if (plague != null) {
		carrier.field.remove(plague)
		putOnPile(plague, carrier)
		topType = plague.type
		currColor = plague.color
		announceIndicator(direction, currColor)
	}
}

/** While Plague is out, everyone who takes a turn draws after it. */
internal suspend fun Game.plagueAfterTurn(p: Player) {
	if (apocalypse || plagueCarrier == null || plagueCarrier === p || skipPlagueDraw === p) return
	if (p.luckCount > 0) return
	val n = (if (p.virusCount > 0) 2 else 1) + (if (drewBecauseStuck) 1 else 0)
	message(Msg.PLAGUE_DRAW, p.seat, n = n)
	forceDraw(p, n, Game.DrawKind.PLAIN)
}

// ── players leaving ──────────────────────────────────────────────────────────

/** [p] leaves the round: removed in normal mode, safely out in elimination mode. */
internal suspend fun Game.removePlayer(p: Player, by: Player?, cause: Special?) {
	if (!p.active) return
	message(Msg.REMOVED, p.seat, by?.seat, card = cause?.let { CardType.special(it) })
	if (plagueCarrier === p) endPlague(p)
	for (c in p.hand.takeAll()) discardPile.addBottom(c)
	for (c in p.field.toList()) {
		// Death and Conquest stay in play.
		if (c.special == Special.DEATH || c.special == Special.CONQUEST) neutral += c else discardPile.addBottom(c)
	}
	p.field.clear()
	p.armies.clear()
	p.deathClock = -1
	p.clockCard = null
	p.warCard = null
	p.calledUno = false
	if (prophet === p) {
		prophet = null
		prophetTarget = null
	}
	if (prophetTarget === p) prophetTarget = null
	setStatus(p, if (config.eliminationMode) Status.FINISHED else Status.REMOVED)
	checkRoundEnd()
}

/** Glasnost: the hand as it is now goes face up. Holding Quitter gets you caught. */
internal suspend fun Game.exposeHand(p: Player) {
	for (c in p.hand.revealCurrent()) emit(GameEvent.CardFlipped(c, animMs()))
	message(Msg.FACE_UP, p.seat)
	waitABit(2)
	if (p.hand.has(Special.QUITTER)) {
		message(Msg.CAUGHT_QUITTER, p.seat)
		removePlayer(p, null, Special.QUITTER)
	}
}

// ── technical cards ──────────────────────────────────────────────────────────

private suspend fun Game.spreader(p: Player) {
	var lastPenn: Player? = null
	var q = step(p)
	val victims = ArrayList<Player>()
	while (q !== p && q !in victims) {
		victims += q
		q = step(q)
	}
	for (v in victims) {
		val penn = v.hand.first(Special.PENN_STATE)
		if (penn != null) {
			v.hand.remove(penn)
			putOnPile(penn, v)
			topType = penn.type
			currColor = penn.color
			message(Msg.PENN_STATE, v.seat)
			forceDraw(p, 2, Game.DrawKind.PLAIN)
			lastPenn = v
		} else {
			forceDraw(v, 2, Game.DrawKind.PLAIN)
		}
	}
	// The Spreader's player goes again, unless Penn State turned it around.
	next = lastPenn?.let { step(it) } ?: p
}

private suspend fun Game.swapHands(a: Player, b: Player) {
	if (a === b) return
	val ca = a.hand.takeAll()
	val cb = b.hand.takeAll()
	for (c in cb) { a.hand.add(c); emit(GameEvent.CardPassed(c, b.seat, a.seat, animMs())) }
	for (c in ca) { b.hand.add(c); emit(GameEvent.CardPassed(c, a.seat, b.seat, animMs())) }
	a.calledUno = false
	b.calledUno = false
	message(Msg.SWAP, a.seat, b.seat)
	waitABit(2)
}

private suspend fun Game.gift(p: Player, forced: Player?) {
	if (p.hand.size < 2) return
	val card = p.controller.chooseCard(this, p, Reason.GIFT_CARD, p.hand.cards.toList()) ?: return
	val to = pick(p, Reason.GIFT, forced) ?: return
	p.hand.remove(card)
	to.hand.add(card)
	to.calledUno = false
	p.peeked += card
	emit(GameEvent.CardPassed(card, p.seat, to.seat, animMs()))
	message(Msg.GIFT, p.seat, to.seat)
	waitABit(2)
}

private suspend fun Game.goldCoin(p: Player, forced: Player?) {
	if (random.nextBoolean()) {
		message(Msg.GOLD_COIN_UP, p.seat)
		repeat(activePlayerCount) {
			val to = pick(p, Reason.GOLD_COIN, forced) ?: return
			val c = drawFromPile() ?: return
			to.hand.add(c)
			to.calledUno = false
			emit(GameEvent.CardDrawn(c, to.seat, animMs()))
			waitABit(4)
		}
	} else {
		message(Msg.GOLD_COIN_DOWN, p.seat)
		// Went out on it: back in.
		if (p.hand.isEmpty) drawOne(p)
	}
}

private suspend fun Game.iDontLikeThese(p: Player) {
	val n = p.hand.size
	for (c in p.hand.takeAll()) discardPile.addBottom(c)
	message(Msg.I_DONT_LIKE_THESE, p.seat)
	repeat(n) { drawOne(p) }
}

private suspend fun Game.martyr(p: Player, forced: Player?) {
	val to = pick(p, Reason.MARTYR, forced)
	if (to != null) {
		for (c in p.hand.takeAll()) {
			to.hand.add(c)
			emit(GameEvent.CardPassed(c, p.seat, to.seat, animMs()))
		}
		to.calledUno = false
	}
	removePlayer(p, p, Special.MARTYR)
}

private suspend fun Game.chosenOne(p: Player) {
	val drawn = ArrayList<Card>()
	repeat(5) {
		val c = drawFromPile() ?: return@repeat
		p.hand.add(c)
		emit(GameEvent.CardDrawn(c, p.seat, animMs()))
		drawn += c
	}
	if (drawn.isEmpty()) return
	message(Msg.CHOSEN_ONE, p.seat)
	val keep = p.controller.chooseCard(this, p, Reason.CHOSEN_ONE, drawn) ?: drawn.first()
	// The rest go back on top, the first one drawn on top again.
	for (c in drawn.reversed()) {
		if (c === keep) continue
		p.hand.remove(c)
		drawPile.add(c)
	}
	p.calledUno = false
	activateIfDrawn(p, keep)
	changed()
}

private suspend fun Game.necromancer(p: Player) {
	val dead = players.filter {
		it.status == Status.REMOVED || (config.eliminationMode && it.status == Status.FINISHED)
	}.map { it.seat }
	if (dead.isEmpty()) return
	val target = player(p.controller.chooseVictim(this, p, Reason.NECROMANCER, dead) ?: dead.first())
	// Anyone may stop it with a Fuck You.
	val blockers = players.filter { it !== p && it.active }.associateWith { q -> q.hand.cards.filter { it.special == Special.FUCK_YOU } }
	val block = reactionWindow(CardType.special(Special.FUCK_YOU), blockers)
	if (block != null) {
		val (b, fy) = block
		b.hand.remove(fy)
		noteEmptied(b)
		putOnPile(fy, b)
		topType = fy.type
		currColor = fy.color
		message(Msg.REVIVE_BLOCKED, b.seat, target.seat)
		return
	}
	target.status = Status.ACTIVE
	target.teammate = null
	target.field.clear()
	message(Msg.REVIVED, target.seat, p.seat)
	repeat(Game.HAND_SIZE) { drawOne(target) }
	changed()
}

private suspend fun Game.communism(p: Player) {
	val order = ArrayList<Player>()
	var q = step(p)
	while (q !in order) {
		order += q
		q = step(q)
	}
	val pool = ArrayList<Card>()
	for (pl in order) pool += pl.hand.takeAll()
	// A recycle pile is shared out too, and recycling ends.
	for (rp in recyclePiles) pool += rp.pile.takeAll()
	recyclePiles.clear()
	pool.shuffle(random)
	message(Msg.COMMUNISM)
	pool.forEachIndexed { i, c ->
		val to = order[i % order.size]
		to.hand.add(c)
		emit(GameEvent.CardDrawn(c, to.seat, animMs()))
	}
	for (pl in order) {
		pl.calledUno = false
		pl.peeked.clear()
	}
	waitABit()
	// Field cards you are dealt come into play for you.
	for (pl in order) {
		for (c in pl.hand.cards.filter { it.special?.category == Category.FIELD }) {
			when (c.special) {
				Special.FAMINE, Special.WAR -> {
					pl.hand.remove(c)
					toNeutral(c, pl)
				}
				Special.DEATH -> {
					toField(pl, c, pl)
					deathFlip(pl, clock = 7)
				}
				Special.PROPHET -> {
					toField(pl, c, pl)
					prophetLook(pl)
				}
				Special.PLAGUE -> if (plagueCarrier == null) toField(pl, c, pl)
				else -> toField(pl, c, pl)
			}
		}
	}
}

// ── Prophet ──────────────────────────────────────────────────────────────────

/** The Prophet looks at a player's hand. Horsemen seen there come into play. */
internal suspend fun Game.prophetLook(p: Player) {
	prophet = p
	val options = others(p)
	if (options.isEmpty()) return
	val t = player(p.controller.chooseVictim(this, p, Reason.PROPHET, options) ?: options.first())
	prophetTarget = t
	p.peeked += t.hand.cards
	message(Msg.PROPHET_SEES, p.seat, t.seat)
	for (c in t.hand.cards.filter { it.special?.isHorseman == true }) {
		when (c.special) {
			Special.FAMINE, Special.WAR -> {
				t.hand.remove(c)
				toNeutral(c, t)
			}
			Special.DEATH -> {
				toField(t, c, t)
				deathFlip(t)
			}
			else -> toField(t, c, t)
		}
	}
	changed()
}

internal suspend fun Game.askProphet(p: Player, about: CardType) {
	val pr = prophet ?: return
	val t = prophetTarget ?: return
	p.askedProphetThisTurn = true
	message(Msg.PROPHET_QUESTION, p.seat, t.seat, card = about)
	waitABit()
	val yes = t.hand.cards.any { it in pr.peeked && (if (about.special != null) it.special == about.special else it.type === about) }
	message(if (yes) Msg.PROPHET_YES else Msg.PROPHET_NO, pr.seat)
	waitABit(2)
	if (!yes) return
	when (about.special) {
		Special.QUITTER -> {
			message(Msg.CAUGHT_QUITTER, t.seat)
			removePlayer(t, p, Special.QUITTER)
		}
		Special.MAD -> {
			removePlayer(t, p, Special.MAD)
			removePlayer(pr, p, Special.MAD)
		}
		else -> {}
	}
}

// ── Quitter accusations ──────────────────────────────────────────────────────

internal suspend fun Game.accuse(p: Player, t: Player) {
	message(Msg.ACCUSES, p.seat, t.seat)
	for (c in t.hand.revealCurrent()) emit(GameEvent.CardFlipped(c, animMs()))
	waitABit()
	val quitter = t.hand.first(Special.QUITTER)
	if (quitter != null) {
		message(Msg.ACCUSATION_RIGHT, t.seat)
		if (config.eliminationMode) {
			// Elimination mode: the accuser goes out instead and the Quitter is thrown away.
			t.hand.remove(quitter)
			discardPile.addBottom(quitter)
			setStatus(p, Status.FINISHED)
			message(Msg.PLAYER_OUT, p.seat)
		} else {
			removePlayer(t, p, Special.QUITTER)
		}
	} else {
		message(Msg.ACCUSATION_WRONG, p.seat)
		if (config.eliminationMode) forceDraw(p, 8, Game.DrawKind.PENALTY) else removePlayer(p, null, null)
	}
}

// ── Conquest ─────────────────────────────────────────────────────────────────

internal suspend fun Game.invade(p: Player, t: Player) {
	message(Msg.INVASION, p.seat, t.seat)
	next = step(p)
	val defOptions = t.hand.cards.filter { it.type.isNumber }
	if (defOptions.isEmpty()) {
		message(Msg.INVASION_WON, p.seat, t.seat)
		forceDraw(t, 3, Game.DrawKind.PENALTY)
		return
	}
	val mine = p.hand.cards.filter { it.type.isNumber }
	val a = p.controller.chooseCard(this, p, Reason.ARMY, mine) ?: mine.first()
	val d = t.controller.chooseCard(this, t, Reason.ARMY, defOptions) ?: defOptions.first()
	p.hand.remove(a)
	t.hand.remove(d)
	emit(GameEvent.CardToField(a, p.seat, p.seat, animMs()))
	emit(GameEvent.CardToField(d, t.seat, p.seat, animMs()))
	waitABit()
	if (a.special == Special.QUITTER || d.special == Special.QUITTER) {
		discardPile.addBottom(a)
		discardPile.addBottom(d)
		removePlayer(p, null, Special.QUITTER)
		removePlayer(t, null, Special.QUITTER)
		return
	}
	val na = a.type.number ?: 0
	val nd = d.type.number ?: 0
	when {
		na > nd -> {
			p.armies += a
			p.armies += d
			p.field += a
			p.field += d
			p.conquered += t.seat
			message(Msg.INVASION_WON, p.seat, t.seat)
			forceDraw(t, 2, Game.DrawKind.PENALTY)
			if (isConqueror(p)) message(Msg.CONQUEROR, p.seat)
		}
		na < nd -> {
			discardPile.addBottom(a)
			discardPile.addBottom(d)
			message(Msg.INVASION_WON, t.seat, p.seat)
			forceDraw(p, 2, Game.DrawKind.PENALTY)
		}
		else -> {
			discardPile.addBottom(a)
			discardPile.addBottom(d)
			message(Msg.INVASION_TIE)
			drawOne(p)
			drawOne(t)
		}
	}
	changed()
}

// ── the first card ───────────────────────────────────────────────────────────

/** Turns up the first card and applies the Constitution's special start conditions. */
internal suspend fun Game.startCard() {
	val d = dealer!!
	val c = drawFromPile() ?: return
	val s = c.special
	next = step(d)
	if (s?.isHorseman == true) {
		// Horsemen go to neutral play; the top card counts as a Red 4.
		toNeutral(c, null)
		topType = CardType.RED_4
		currColor = CardColor.RED
		announceIndicator(direction, currColor)
		moveTo(next!!)
		return
	}
	discardPile.add(c)
	emit(GameEvent.CardDiscarded(c, null, animMs()))
	waitABit(2)
	topType = c.type
	currColor = c.color
	if (c.color == CardColor.WILD) chooseColorFor(d)
	announceIndicator(direction, currColor)

	fun drawAmount(): Int? = when {
		s == null && c.face == Face.DRAW_TWO -> 2
		s == null && c.face == Face.WILD_DRAW_FOUR -> 4
		s == Special.BACKSTAB -> 2
		s == Special.HARVESTER || s == Special.DELAYED_BLAST -> 4
		s == Special.HOT_DEATH -> 8
		else -> null
	}

	when {
		s?.category == Category.FIELD -> {
			discardPile.remove(c)
			toField(d, c, null)
			if (s == Special.PROPHET) prophetLook(d)
		}
		s == Special.PING || s == Special.GLASNOST -> {
			val options = others(d)
			val v = player(d.controller.chooseVictim(this, d, if (s == Special.PING) Reason.PING else Reason.GLASNOST, options) ?: options.first())
			val kind = if (s == Special.PING) AttackKind.PING else AttackKind.GLASNOST
			launch(Attack(kind, c.type, null, if (s == Special.PING) 1 else 0, null, d, v, directed = true, unblockable = false))
		}
		s == Special.QUITTER -> removePlayer(d, null, Special.QUITTER)
		s == Special.SPREADER -> for (p in activePlayers) forceDraw(p, 2, Game.DrawKind.PLAIN)
		s == Special.TASTE_THE_RAINBOW -> {
			message(Msg.TAKES_ATTACK, d.seat, card = c.type)
			launch(Attack(AttackKind.RAINBOW, c.type, null, 0, null, d, d, directed = false, unblockable = true))
		}
		drawAmount() != null -> {
			if (s == Special.BACKSTAB) reverseDirection()
			val n = drawAmount()!!
			message(Msg.TAKES_ATTACK, d.seat, n = n, card = c.type)
			forceDraw(d, n, Game.DrawKind.ATTACK)
		}
		c.face == Face.REVERSE -> {
			reverseDirection()
			if (s == Special.RECYCLER) recyclePiles += RecyclePile(d)
		}
		s == Special.REVERSE_SKIP -> {
			reverseDirection()
			skip(d, 1)
		}
		s == Special.DOUBLE_SKIP -> skip(d, 2)
		s == null && c.face == Face.SKIP -> skip(d, 1)
		else -> {}
	}
	val first = when {
		c.face == Face.REVERSE || s == Special.BACKSTAB || drawAmount() != null || s == Special.SPREADER ||
			s == Special.QUITTER || s?.category == Category.FIELD || s == Special.TASTE_THE_RAINBOW -> step(d)
		else -> next ?: step(d)
	}
	next = null
	moveTo(first)
}
