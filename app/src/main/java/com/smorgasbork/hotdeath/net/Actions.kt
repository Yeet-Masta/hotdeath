package com.smorgasbork.hotdeath.net

import com.smorgasbork.hotdeath.engine.CardColor
import com.smorgasbork.hotdeath.engine.CardType
import com.smorgasbork.hotdeath.engine.Game
import com.smorgasbork.hotdeath.engine.Seat
import com.smorgasbork.hotdeath.engine.TurnAction

/** Everything a player can do at the table. */
sealed interface PlayerAction {
	data class Play(val card: Int) : PlayerAction
	data object Draw : PlayerAction
	/** Take the attack, or end the turn after drawing. */
	data object Pass : PlayerAction
	data object DiscardTap : PlayerAction
	data object NextRound : PlayerAction
	data object FastForward : PlayerAction
	data class Color(val color: CardColor) : PlayerAction
	/** Null declines an optional choice. */
	data class Victim(val seat: Seat?) : PlayerAction
	/** Null declines an optional choice. */
	data class ChooseCard(val card: Int?) : PlayerAction
	data object Uno : PlayerAction
	data class Accuse(val seat: Seat) : PlayerAction
	data class AskProphet(val about: CardType) : PlayerAction
	data class ChangeColor(val color: CardColor) : PlayerAction
	data class Invade(val seat: Seat) : PlayerAction
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
		is PlayerAction.ChooseCard -> submitCard(seat, a.card?.let { deck[it] })
		PlayerAction.Uno -> humanAction(seat, TurnAction.CallUno)
		is PlayerAction.Accuse -> humanAction(seat, TurnAction.Accuse(a.seat))
		is PlayerAction.AskProphet -> humanAction(seat, TurnAction.AskProphet(a.about))
		is PlayerAction.ChangeColor -> humanAction(seat, TurnAction.ChangeColor(a.color))
		is PlayerAction.Invade -> humanAction(seat, TurnAction.Invade(a.seat))
	}
}
