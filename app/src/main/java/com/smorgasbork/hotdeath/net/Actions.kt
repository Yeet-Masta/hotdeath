package com.smorgasbork.hotdeath.net

import com.smorgasbork.hotdeath.engine.CardColor
import com.smorgasbork.hotdeath.engine.Game
import com.smorgasbork.hotdeath.engine.Seat

/** Everything a player can do at the table. */
sealed interface PlayerAction {
	data class Play(val card: Int) : PlayerAction
	data object Draw : PlayerAction
	data object Pass : PlayerAction
	data object DiscardTap : PlayerAction
	data object NextRound : PlayerAction
	data object FastForward : PlayerAction
	data class Color(val color: CardColor) : PlayerAction
	data class Victim(val seat: Seat) : PlayerAction
	data class Deal(val count: Int) : PlayerAction
}

/**
 * Applies [a] for the person in [seat]. The engine checks that it is that
 * person's turn or question, so a remote client cannot act for anyone else.
 */
fun Game.apply(seat: Seat, a: PlayerAction) {
	when (a) {
		is PlayerAction.Play -> deck[a.card]?.let { humanPlay(seat, it) }
		PlayerAction.Draw -> drawPileTapped(seat)
		PlayerAction.Pass -> humanPass(seat)
		PlayerAction.DiscardTap -> discardPileTapped()
		PlayerAction.NextRound -> requestNextRound()
		PlayerAction.FastForward -> if (showFastForwardButton) startFastForward()
		is PlayerAction.Color -> submitColor(seat, a.color)
		is PlayerAction.Victim -> submitVictim(seat, a.seat)
		is PlayerAction.Deal -> submitNumCardsToDeal(seat, a.count)
	}
}
