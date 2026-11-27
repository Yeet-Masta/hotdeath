package com.smorgasbork.hotdeath

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.smorgasbork.hotdeath.engine.CardType

/**
 * Card faces as bitmaps. A special card without its own artwork is drawn as
 * its base card with the special's name on a band across the middle, so the
 * game is playable before the art exists.
 */
object CardArt {

	fun bitmap(ctx: Context, t: CardType, familyFriendly: Boolean): Bitmap {
		val art = CardText.artwork(ctx, t, familyFriendly)
		if (art != 0) return BitmapFactory.decodeResource(ctx.resources, art)
		val base = BitmapFactory.decodeResource(ctx.resources, CardText.drawable(ctx, t.base, familyFriendly))
		if (t.special == null) return base
		val b = base.copy(Bitmap.Config.ARGB_8888, true)
		label(Canvas(b), b.width.toFloat(), b.height.toFloat(), CardText.shortName(ctx, t, familyFriendly))
		return b
	}

	private fun label(c: Canvas, w: Float, h: Float, name: String) {
		val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.WHITE
			typeface = Typeface.DEFAULT_BOLD
			textAlign = Paint.Align.CENTER
			textSize = w / 6.5f
		}
		val lines = wrap(name, text, w * 0.9f)
		while (lines.any { text.measureText(it) > w * 0.92f } && text.textSize > w / 14) text.textSize *= 0.9f
		val lineH = text.textSize * 1.15f
		val bandH = lineH * lines.size + text.textSize * 0.5f
		val top = (h - bandH) / 2
		val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
		c.drawRoundRect(RectF(w * 0.04f, top, w * 0.96f, top + bandH), w * 0.05f, w * 0.05f, band)
		lines.forEachIndexed { i, line ->
			c.drawText(line, w / 2, top + text.textSize * 0.25f + lineH * (i + 1) - (lineH - text.textSize) / 2, text)
		}
	}

	/** Greedy word wrap to [width]. */
	private fun wrap(s: String, p: Paint, width: Float): MutableList<String> {
		val lines = ArrayList<String>()
		var line = ""
		for (word in s.split(' ')) {
			val next = if (line.isEmpty()) word else "$line $word"
			if (line.isNotEmpty() && p.measureText(next) > width) {
				lines += line
				line = word
			} else {
				line = next
			}
		}
		if (line.isNotEmpty()) lines += line
		return lines
	}
}
