package com.smorgasbork.hotdeath

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.DrawableRes
import com.smorgasbork.hotdeath.engine.CardColor
import com.smorgasbork.hotdeath.engine.CardType
import com.smorgasbork.hotdeath.engine.Face
import com.smorgasbork.hotdeath.engine.GameMessage
import com.smorgasbork.hotdeath.engine.Reason
import com.smorgasbork.hotdeath.engine.Seat
import com.smorgasbork.hotdeath.engine.Special

/**
 * Android resources and display text for the engine's types.
 *
 * Resources are found by name, so new cards need no code here:
 * `cardname_<special>`, `cardhelp_<special>`, `msg_<kind>` and art
 * `card_<type key>` (with `_ff` variants for family-friendly mode).
 */
@SuppressLint("DiscouragedApi")
object CardText {

	private fun id(ctx: Context, type: String, name: String): Int =
		ctx.resources.getIdentifier(name, type, ctx.packageName)

	private fun string(ctx: Context, name: String, ff: Boolean): Int =
		(if (ff) id(ctx, "string", name + "_ff") else 0).takeIf { it != 0 } ?: id(ctx, "string", name)

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

	/** The plain card's name ("Red 4", "Wild Draw Four"). */
	fun plainName(ctx: Context, t: CardType): String {
		val face = when (t.face) {
			Face.DRAW_TWO -> ctx.getString(R.string.cardval_d)
			Face.SKIP -> ctx.getString(R.string.cardval_s)
			Face.REVERSE -> ctx.getString(R.string.cardval_r)
			Face.WILD -> return ctx.getString(R.string.cardval_wild)
			Face.WILD_DRAW_FOUR -> return ctx.getString(R.string.cardval_wild_drawfour)
			else -> t.face.number.toString()
		}
		return "${colorName(ctx, t.color)} $face"
	}

	/** The special's own name ("Death"), or the plain name. */
	fun shortName(ctx: Context, t: CardType, familyFriendly: Boolean): String {
		val s = t.special ?: return plainName(ctx, t)
		val res = string(ctx, "cardname_" + s.key, familyFriendly)
		return if (res != 0) ctx.getString(res) else s.name
	}

	/** Full name: the special's name, with its color where there are several ("Blue Cure"). */
	fun cardName(ctx: Context, t: CardType, familyFriendly: Boolean): String {
		val s = t.special ?: return plainName(ctx, t)
		val name = shortName(ctx, t, familyFriendly)
		return if (s.replaces.distinct().size > 1 && t.color != CardColor.WILD) "${colorName(ctx, t.color)} $name" else name
	}

	/** Artwork for [t], or 0 if there is none yet (then the base card is drawn with the name over it). */
	@DrawableRes
	fun artwork(ctx: Context, t: CardType, familyFriendly: Boolean): Int {
		// Art made for the card itself wins over old art that fits.
		val names = listOfNotNull("card_" + t.key, LEGACY_ART[t.key])
		for (n in names) {
			if (familyFriendly) id(ctx, "drawable", n + "_ff").takeIf { it != 0 }?.let { return it }
			id(ctx, "drawable", n).takeIf { it != 0 }?.let { return it }
		}
		return 0
	}

	/** Artwork for [t], falling back to its base card. */
	@DrawableRes
	fun drawable(ctx: Context, t: CardType, familyFriendly: Boolean): Int =
		artwork(ctx, t, familyFriendly).takeIf { it != 0 } ?: artwork(ctx, t.base, familyFriendly).takeIf { it != 0 }
			?: R.drawable.card_back

	fun help(ctx: Context, t: CardType, familyFriendly: Boolean): String {
		val key = t.special?.key ?: t.face.key
		val res = string(ctx, "cardhelp_$key", familyFriendly)
		return if (res != 0) ctx.getString(res) else ""
	}

	fun reason(ctx: Context, r: Reason): String {
		val res = id(ctx, "string", "prompt_reason_" + r.name.lowercase())
		return if (res != 0) ctx.getString(res) else ""
	}

	fun message(ctx: Context, m: GameMessage, ff: Boolean, nameOf: (Seat) -> String = { seatName(ctx, it) }): String {
		val res = string(ctx, "msg_" + m.kind.name.lowercase(), ff)
		if (res == 0) return ""
		return ctx.getString(
			res,
			m.seat?.let(nameOf) ?: "",
			m.other?.let(nameOf) ?: "",
			m.n,
			m.card?.let { cardName(ctx, it, ff) } ?: "",
		)
	}

	/** Old Hot Death art that still fits a Constitution card. */
	private val LEGACY_ART: Map<String, String> = buildMap {
		put(CardType.special(Special.HOT_DEATH).key, "card_wild_hd")
		put(CardType.special(Special.HARVESTER).key, "card_wild_hos")
		put(CardType.special(Special.MYSTERY_DRAW).key, "card_wild_mystery")
		put(CardType.special(Special.HOLY_DEFENDER, CardColor.RED).key, "card_red_0_hd")
		put(CardType.special(Special.GLASNOST).key, "card_red_2_glasnost")
		put(CardType.special(Special.MAGIC_5).key, "card_red_5_magic")
		put(CardType.special(Special.LUCK).key, "card_green_4_irish")
		put(CardType.special(Special.FUCK_YOU).key, "card_blue_0_fuckyou")
		put(CardType.special(Special.BLUE_SHIELD).key, "card_blue_2_shield")
		put(CardType.special(Special.SIXTY_NINE, CardColor.YELLOW).key, "card_yellow_69")
		put(CardType.special(Special.AIDS).key, "card_green_3_aids")
		put(CardType.special(Special.PING).key, "card_blue_1_ping")
		for (c in CardColor.SUITS) {
			val n = c.name.lowercase()
			put(CardType.special(Special.SPREADER, c).key, "card_${n}_d_spreader")
			put(CardType.special(Special.DOUBLE_SKIP, c).key, "card_${n}_s_double")
			put(CardType.special(Special.REVERSE_SKIP, c).key, "card_${n}_r_skip")
		}
	}
}
