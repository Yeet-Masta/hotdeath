package com.smorgasbork.hotdeath

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.smorgasbork.hotdeath.engine.CardColor
import com.smorgasbork.hotdeath.engine.CardType
import com.smorgasbork.hotdeath.engine.CardValue
import com.smorgasbork.hotdeath.engine.GameMessage
import com.smorgasbork.hotdeath.engine.NoGoodReason
import com.smorgasbork.hotdeath.engine.Seat

/** Android resources and display text for the engine's types. */
object CardText {

	fun seatName(ctx: Context, seat: Seat): String = ctx.getString(when (seat) {
		Seat.NORTH -> R.string.seat_north
		Seat.EAST -> R.string.seat_east
		Seat.SOUTH -> R.string.seat_south
		Seat.WEST -> R.string.seat_west
	})

	fun colorName(ctx: Context, c: CardColor?): String = when (c) {
		CardColor.RED -> ctx.getString(R.string.cardcolor_red)
		CardColor.GREEN -> ctx.getString(R.string.cardcolor_green)
		CardColor.BLUE -> ctx.getString(R.string.cardcolor_blue)
		CardColor.YELLOW -> ctx.getString(R.string.cardcolor_yellow)
		else -> ""
	}

	fun cardName(ctx: Context, t: CardType, familyFriendly: Boolean): String {
		specialName(t, familyFriendly)?.let { return ctx.getString(it) }
		val value = when (t.value) {
			CardValue.DRAW -> ctx.getString(R.string.cardval_d)
			CardValue.SKIP -> ctx.getString(R.string.cardval_s)
			CardValue.REVERSE -> ctx.getString(R.string.cardval_r)
			CardValue.DRAW_SPREAD -> ctx.getString(R.string.cardval_d_spread)
			CardValue.SKIP_DOUBLE -> ctx.getString(R.string.cardval_s_double)
			CardValue.REVERSE_SKIP -> ctx.getString(R.string.cardval_r_skip)
			CardValue.REVERSE_BACKSTAB -> ctx.getString(R.string.cardname_backstab)
			CardValue.CLONE -> ctx.getString(R.string.cardname_clone)
			CardValue.REVERSE_SWAP -> ctx.getString(R.string.cardname_swap)
			else -> t.value.number?.toString() ?: ""
		}
		return "${colorName(ctx, t.color)} $value"
	}

	/** Names for cards that have a unique name; null for ordinary cards. */
	@StringRes
	private fun specialName(t: CardType, ff: Boolean): Int? = when (t) {
		CardType.WILD -> R.string.cardval_wild
		CardType.WILD_DRAW_FOUR -> R.string.cardval_wild_drawfour
		CardType.WILD_HD -> R.string.cardname_wild_hd
		CardType.WILD_DB -> R.string.cardname_wild_db
		CardType.WILD_HOS -> R.string.cardname_wild_hos
		CardType.WILD_MYSTERY -> R.string.cardname_wild_mystery
		CardType.RED_0_HD -> R.string.cardname_red_0_hd
		CardType.RED_2_GLASNOST -> R.string.cardname_red_2_glasnost
		CardType.RED_5_MAGIC -> R.string.cardname_red_5_magic
		CardType.GREEN_0_QUITTER -> R.string.cardname_green_0_quitter
		CardType.GREEN_3_AIDS -> if (ff) R.string.cardname_green_3_aids_ff else R.string.cardname_green_3_aids
		CardType.GREEN_4_IRISH -> R.string.cardname_green_4_irish
		CardType.BLUE_0_FUCK_YOU -> if (ff) R.string.cardname_blue_0_fuck_you_ff else R.string.cardname_blue_0_fuck_you
		CardType.BLUE_2_SHIELD -> R.string.cardname_blue_2_shield
		CardType.YELLOW_0_SHITTER -> if (ff) R.string.cardname_yellow_0_shitter_ff else R.string.cardname_yellow_0_shitter
		CardType.YELLOW_1_MAD -> R.string.cardname_yellow_1_mad
		CardType.YELLOW_69 -> R.string.cardname_yellow_69
		CardType.RED_R_BACKSTAB, CardType.GREEN_R_BACKSTAB,
		CardType.BLUE_R_BACKSTAB, CardType.YELLOW_R_BACKSTAB -> R.string.cardname_backstab
		CardType.GREEN_2_CLONE, CardType.YELLOW_2_CLONE -> R.string.cardname_clone
		CardType.BLUE_1_PING -> R.string.cardname_ping
		CardType.GREEN_R_SWAP, CardType.YELLOW_R_SWAP -> R.string.cardname_swap
		else -> null
	}

	@DrawableRes
	fun drawable(t: CardType, ff: Boolean): Int = when (t) {
		CardType.RED_0 -> R.drawable.card_red_0
		CardType.RED_1 -> R.drawable.card_red_1
		CardType.RED_2 -> R.drawable.card_red_2
		CardType.RED_3 -> R.drawable.card_red_3
		CardType.RED_4 -> R.drawable.card_red_4
		CardType.RED_5 -> R.drawable.card_red_5
		CardType.RED_6 -> R.drawable.card_red_6
		CardType.RED_7 -> R.drawable.card_red_7
		CardType.RED_8 -> R.drawable.card_red_8
		CardType.RED_9 -> R.drawable.card_red_9
		CardType.RED_D -> R.drawable.card_red_d
		CardType.RED_S -> R.drawable.card_red_s
		CardType.RED_R -> R.drawable.card_red_r
		CardType.GREEN_0 -> R.drawable.card_green_0
		CardType.GREEN_1 -> R.drawable.card_green_1
		CardType.GREEN_2 -> R.drawable.card_green_2
		CardType.GREEN_3 -> R.drawable.card_green_3
		CardType.GREEN_4 -> R.drawable.card_green_4
		CardType.GREEN_5 -> R.drawable.card_green_5
		CardType.GREEN_6 -> R.drawable.card_green_6
		CardType.GREEN_7 -> R.drawable.card_green_7
		CardType.GREEN_8 -> R.drawable.card_green_8
		CardType.GREEN_9 -> R.drawable.card_green_9
		CardType.GREEN_D -> R.drawable.card_green_d
		CardType.GREEN_S -> R.drawable.card_green_s
		CardType.GREEN_R -> R.drawable.card_green_r
		CardType.BLUE_0 -> R.drawable.card_blue_0
		CardType.BLUE_1 -> R.drawable.card_blue_1
		CardType.BLUE_2 -> R.drawable.card_blue_2
		CardType.BLUE_3 -> R.drawable.card_blue_3
		CardType.BLUE_4 -> R.drawable.card_blue_4
		CardType.BLUE_5 -> R.drawable.card_blue_5
		CardType.BLUE_6 -> R.drawable.card_blue_6
		CardType.BLUE_7 -> R.drawable.card_blue_7
		CardType.BLUE_8 -> R.drawable.card_blue_8
		CardType.BLUE_9 -> R.drawable.card_blue_9
		CardType.BLUE_D -> R.drawable.card_blue_d
		CardType.BLUE_S -> R.drawable.card_blue_s
		CardType.BLUE_R -> R.drawable.card_blue_r
		CardType.YELLOW_0 -> R.drawable.card_yellow_0
		CardType.YELLOW_1 -> R.drawable.card_yellow_1
		CardType.YELLOW_2 -> R.drawable.card_yellow_2
		CardType.YELLOW_3 -> R.drawable.card_yellow_3
		CardType.YELLOW_4 -> R.drawable.card_yellow_4
		CardType.YELLOW_5 -> R.drawable.card_yellow_5
		CardType.YELLOW_6 -> R.drawable.card_yellow_6
		CardType.YELLOW_7 -> R.drawable.card_yellow_7
		CardType.YELLOW_8 -> R.drawable.card_yellow_8
		CardType.YELLOW_9 -> R.drawable.card_yellow_9
		CardType.YELLOW_D -> R.drawable.card_yellow_d
		CardType.YELLOW_S -> R.drawable.card_yellow_s
		CardType.YELLOW_R -> R.drawable.card_yellow_r
		CardType.WILD -> R.drawable.card_wild
		CardType.WILD_DRAW_FOUR -> R.drawable.card_wild_drawfour
		CardType.WILD_HOS -> R.drawable.card_wild_hos
		CardType.WILD_HD -> R.drawable.card_wild_hd
		CardType.WILD_MYSTERY -> R.drawable.card_wild_mystery
		CardType.WILD_DB -> R.drawable.card_wild_db
		CardType.RED_0_HD -> R.drawable.card_red_0_hd
		CardType.RED_2_GLASNOST -> R.drawable.card_red_2_glasnost
		CardType.RED_5_MAGIC -> R.drawable.card_red_5_magic
		CardType.RED_D_SPREADER -> R.drawable.card_red_d_spreader
		CardType.RED_S_DOUBLE -> R.drawable.card_red_s_double
		CardType.RED_R_SKIP -> R.drawable.card_red_r_skip
		CardType.GREEN_0_QUITTER -> R.drawable.card_green_0_quitter
		CardType.GREEN_3_AIDS -> if (ff) R.drawable.card_green_3_aids_ff else R.drawable.card_green_3_aids
		CardType.GREEN_4_IRISH -> R.drawable.card_green_4_irish
		CardType.GREEN_D_SPREADER -> R.drawable.card_green_d_spreader
		CardType.GREEN_S_DOUBLE -> R.drawable.card_green_s_double
		CardType.GREEN_R_SKIP -> R.drawable.card_green_r_skip
		CardType.BLUE_0_FUCK_YOU -> if (ff) R.drawable.card_blue_0_fuckyou_ff else R.drawable.card_blue_0_fuckyou
		CardType.BLUE_2_SHIELD -> R.drawable.card_blue_2_shield
		CardType.BLUE_D_SPREADER -> R.drawable.card_blue_d_spreader
		CardType.BLUE_S_DOUBLE -> R.drawable.card_blue_s_double
		CardType.BLUE_R_SKIP -> R.drawable.card_blue_r_skip
		CardType.YELLOW_0_SHITTER -> if (ff) R.drawable.card_yellow_0_shitter_ff else R.drawable.card_yellow_0_shitter
		CardType.YELLOW_1_MAD -> R.drawable.card_yellow_1_mad
		CardType.YELLOW_69 -> R.drawable.card_yellow_69
		CardType.YELLOW_D_SPREADER -> R.drawable.card_yellow_d_spreader
		CardType.YELLOW_S_DOUBLE -> R.drawable.card_yellow_s_double
		CardType.YELLOW_R_SKIP -> R.drawable.card_yellow_r_skip
		CardType.BLUE_1_PING -> R.drawable.card_blue_1_ping
		// No artwork yet for these v3 cards.
		CardType.RED_R_BACKSTAB, CardType.GREEN_R_BACKSTAB, CardType.BLUE_R_BACKSTAB,
		CardType.YELLOW_R_BACKSTAB, CardType.GREEN_2_CLONE, CardType.YELLOW_2_CLONE,
		CardType.GREEN_R_SWAP, CardType.YELLOW_R_SWAP -> R.drawable.card_back
	}

	@StringRes
	fun help(t: CardType, ff: Boolean): Int = when (t) {
		CardType.WILD -> R.string.cardhelp_wild
		CardType.WILD_DRAW_FOUR -> R.string.cardhelp_wild_drawfour
		CardType.WILD_HOS -> R.string.cardhelp_wild_hos
		CardType.WILD_HD -> R.string.cardhelp_wild_hd
		CardType.WILD_MYSTERY -> R.string.cardhelp_wild_mystery
		CardType.WILD_DB -> R.string.cardhelp_wild_db
		CardType.RED_0_HD -> if (ff) R.string.cardhelp_red_0_hd_ff else R.string.cardhelp_red_0_hd
		CardType.RED_2_GLASNOST -> R.string.cardhelp_red_2_glasnost
		CardType.RED_5_MAGIC -> R.string.cardhelp_red_5_magic
		CardType.GREEN_0_QUITTER -> if (ff) R.string.cardhelp_green_0_quitter_ff else R.string.cardhelp_green_0_quitter
		CardType.GREEN_3_AIDS -> if (ff) R.string.cardhelp_green_3_aids_ff else R.string.cardhelp_green_3_aids
		CardType.GREEN_4_IRISH -> R.string.cardhelp_green_4_irish
		CardType.BLUE_0_FUCK_YOU -> if (ff) R.string.cardhelp_blue_0_fuck_you_ff else R.string.cardhelp_blue_0_fuck_you
		CardType.BLUE_2_SHIELD -> R.string.cardhelp_blue_2_shield
		CardType.YELLOW_0_SHITTER -> if (ff) R.string.cardhelp_yellow_0_shitter_ff else R.string.cardhelp_yellow_0_shitter
		CardType.YELLOW_1_MAD -> R.string.cardhelp_yellow_1_mad
		CardType.YELLOW_69 -> if (ff) R.string.cardhelp_yellow_69_ff else R.string.cardhelp_yellow_69
		CardType.RED_R_BACKSTAB, CardType.GREEN_R_BACKSTAB,
		CardType.BLUE_R_BACKSTAB, CardType.YELLOW_R_BACKSTAB -> R.string.cardhelp_backstab
		CardType.GREEN_2_CLONE, CardType.YELLOW_2_CLONE -> R.string.cardhelp_clone
		CardType.BLUE_1_PING -> R.string.cardhelp_ping
		CardType.GREEN_R_SWAP, CardType.YELLOW_R_SWAP -> R.string.cardhelp_swap
		else -> when (t.value) {
			CardValue.DRAW_SPREAD -> R.string.cardhelp_d_spread
			CardValue.SKIP_DOUBLE -> R.string.cardhelp_s_double
			CardValue.REVERSE_SKIP -> R.string.cardhelp_r_skip
			CardValue.DRAW -> R.string.cardhelp_d
			CardValue.SKIP -> R.string.cardhelp_s
			CardValue.REVERSE -> R.string.cardhelp_r
			CardValue.ZERO -> R.string.cardhelp_0
			CardValue.ONE -> R.string.cardhelp_1
			CardValue.TWO -> R.string.cardhelp_2
			CardValue.THREE -> R.string.cardhelp_3
			CardValue.FOUR -> R.string.cardhelp_4
			CardValue.FIVE -> R.string.cardhelp_5
			CardValue.SIX -> R.string.cardhelp_6
			CardValue.SEVEN -> R.string.cardhelp_7
			CardValue.EIGHT -> R.string.cardhelp_8
			else -> R.string.cardhelp_9
		}
	}

	/** Why a tapped card cannot be played, or the plain message when the reason is unknown. */
	private fun noGood(ctx: Context, why: NoGoodReason?, ff: Boolean): String = when (why) {
		null -> ctx.getString(R.string.msg_card_no_good)
		NoGoodReason.NotYourTurn -> ctx.getString(R.string.why_not_your_turn)
		is NoGoodReason.UnderAttack -> ctx.getString(R.string.why_under_attack, why.count)
		NoGoodReason.Aids -> ctx.getString(R.string.why_aids)
		NoGoodReason.Shitter -> ctx.getString(R.string.why_shitter, cardName(ctx, CardType.YELLOW_0_SHITTER, ff),
			cardName(ctx, CardType.RED_0_HD, ff), cardName(ctx, CardType.RED_5_MAGIC, ff))
		is NoGoodReason.HoldsColor -> ctx.getString(R.string.why_holds_color, colorName(ctx, why.color).trim())
		is NoGoodReason.NoMatch -> when {
			why.top == null -> ctx.getString(R.string.msg_card_no_good)
			why.color == null || why.color == CardColor.WILD -> ctx.getString(R.string.why_no_match_any, cardName(ctx, why.top, ff))
			else -> ctx.getString(R.string.why_no_match, colorName(ctx, why.color).trim().lowercase(), cardName(ctx, why.top, ff))
		}
	}

	fun message(ctx: Context, m: GameMessage, ff: Boolean, nameOf: (Seat) -> String = { seatName(ctx, it) }): String {
		fun s(seat: Seat) = nameOf(seat)
		return when (m) {
			is GameMessage.Dealing -> ctx.getString(R.string.msg_dealing, s(m.dealer), m.count)
			GameMessage.TapDrawPile -> ctx.getString(R.string.msg_tap_draw_pile)
			is GameMessage.YourPlay -> ctx.getString(R.string.msg_your_play)
			is GameMessage.CardNoGood -> noGood(ctx, m.why, ff)
			is GameMessage.TimedOut -> ctx.getString(R.string.msg_timed_out, s(m.seat))
			is GameMessage.PlayerDrawsCard -> ctx.getString(R.string.msg_player_draws_card, s(m.seat))
			is GameMessage.TakesPenalty -> ctx.getString(R.string.msg_player_takes_penalty, s(m.seat), m.count,
				m.reason?.let { cardName(ctx, it, ff) } ?: "")
			is GameMessage.FaceUp -> ctx.getString(R.string.msg_player_faceup, s(m.seat))
			is GameMessage.EjectedBy -> ctx.getString(R.string.msg_player_ejected_by, s(m.seat), m.by?.let(::s) ?: "?")
			is GameMessage.Ejected -> ctx.getString(R.string.msg_player_ejected, s(m.seat))
			is GameMessage.RoundWinner -> ctx.getString(R.string.msg_declare_round_winner, s(m.seat))
			is GameMessage.ShufflingDiscard ->
				if (m.count > 1) ctx.getString(R.string.msg_shuffling_discard, m.count)
				else ctx.getString(R.string.msg_shuffling_discard_1)
			GameMessage.DiscardEmpty -> ctx.getString(R.string.msg_discard_empty)
			GameMessage.LuckOfIrish -> ctx.getString(R.string.msg_luck_of_irish)
			is GameMessage.Drawing -> ctx.getString(R.string.msg_player_drawing, s(m.seat), m.count)
			is GameMessage.AllBastardCards -> ctx.getString(R.string.msg_all_bastard_cards, s(m.seat))
			GameMessage.Stalemate -> ctx.getString(R.string.msg_stalemate)
			is GameMessage.HasBlueShield -> ctx.getString(R.string.msg_has_blue_shield, s(m.seat))
			is GameMessage.HolyDefender -> ctx.getString(R.string.msg_holy_defender, s(m.victim))
			GameMessage.Magic5 -> ctx.getString(R.string.msg_magic_5)
			is GameMessage.SendingPenalty -> ctx.getString(R.string.msg_sending_penalty, s(m.victim))
			is GameMessage.Swap -> ctx.getString(R.string.msg_swap, s(m.a), s(m.b))
			is GameMessage.Clone -> ctx.getString(R.string.msg_clone, cardName(ctx, m.card, ff))
			is GameMessage.Ping -> ctx.getString(R.string.msg_ping, s(m.from), s(m.to))
		}
	}
}
