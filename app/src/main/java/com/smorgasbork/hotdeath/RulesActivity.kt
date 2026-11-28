package com.smorgasbork.hotdeath

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.smorgasbork.hotdeath.engine.CardDeck

/**
 * How to play: the rules, then every card in the deck chosen in Settings with
 * its picture and what it does. Opened from the main menu's Help button.
 */
class RulesActivity : AppCompatActivity() {

	private val config by lazy { PrefsConfig(this) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		title = getString(R.string.dlg_help_title)
		val ff = config.familyFriendly

		val content = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(16), dp(8), dp(16), dp(24))
		}
		content.addView(heading(getString(R.string.rules_play_title)))
		content.addView(body(getString(R.string.dlg_help_text)))

		content.addView(heading(getString(R.string.rules_cards_title)))
		content.addView(body(getString(R.string.rules_cards_intro)))
		for (t in CardDeck(config.deckType).cards.map { it.type }.distinct()) {
			val row = LinearLayout(this).apply {
				orientation = LinearLayout.HORIZONTAL
				gravity = Gravity.TOP
				setPadding(0, dp(6), 0, dp(6))
			}
			row.addView(ImageView(this).apply {
				setImageResource(CardText.drawable(t, ff))
				adjustViewBounds = true
				contentDescription = CardText.cardName(this@RulesActivity, t, ff)
			}, LinearLayout.LayoutParams(dp(56), dp(84)))
			val text = LinearLayout(this).apply {
				orientation = LinearLayout.VERTICAL
				setPadding(dp(12), 0, 0, 0)
			}
			text.addView(TextView(this).apply {
				setText(CardText.cardName(this@RulesActivity, t, ff))
				setTextColor(ACCENT)
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
				setTypeface(typeface, Typeface.BOLD)
			})
			text.addView(body(getString(CardText.help(t, ff))))
			row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			content.addView(row)
		}

		setContentView(ScrollView(this).apply {
			fitsSystemWindows = true
			addView(content)
		})
	}

	private fun heading(text: String) = TextView(this).apply {
		this.text = text
		setTextColor(ACCENT)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
		setTypeface(typeface, Typeface.BOLD)
		setPadding(0, dp(20), 0, dp(4))
	}

	private fun body(text: String) = TextView(this).apply {
		this.text = text
		setTextColor(0xFFDDDDDD.toInt())
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
		setLineSpacing(0f, 1.15f)
	}

	private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

	private companion object {
		val ACCENT = 0xFFFFB74D.toInt()
	}
}
