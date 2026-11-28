package com.smorgasbork.hotdeath

import android.graphics.Color
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
import com.smorgasbork.hotdeath.engine.Category

/**
 * How to play: the table rules, then every special card with its picture and
 * what it does. Opened from the main menu's Help button.
 */
class RulesActivity : AppCompatActivity() {

	private val familyFriendly: Boolean by lazy { PrefsConfig(this).familyFriendly }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		title = getString(R.string.dlg_help_title)

		val content = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(16), dp(8), dp(16), dp(24))
		}
		for ((head, body) in sections()) {
			content.addView(heading(head))
			content.addView(body(body))
		}

		content.addView(heading(getString(R.string.rules_cards_title)))
		content.addView(body(getString(R.string.rules_cards_intro)))
		addCards(content)

		setContentView(ScrollView(this).apply {
			isFillViewport = true
			fitsSystemWindows = true
			addView(content)
		})
	}

	private fun sections(): List<Pair<String, String>> = listOf(
		R.string.rules_goal_title to R.string.rules_goal,
		R.string.rules_turn_title to R.string.rules_turn,
		R.string.rules_attacks_title to R.string.rules_attacks,
		R.string.rules_jump_title to R.string.rules_jump,
		R.string.rules_uno_title to R.string.rules_uno,
		R.string.rules_removed_title to R.string.rules_removed,
		R.string.rules_end_title to R.string.rules_end,
		R.string.rules_apocalypse_title to R.string.rules_apocalypse,
		R.string.rules_tips_title to R.string.rules_tips,
	).map { (h, b) -> getString(h) to getString(b) }

	/** One row per special card (not per color copy), grouped by what kind of card it is. */
	private fun addCards(into: LinearLayout) {
		val specials = CardDeck.STANDARD.types.filter { it.special != null }.distinctBy { it.special }
		val groups = listOf(
			Category.ATTACK to R.string.rules_group_attack,
			Category.DEFENSE to R.string.rules_group_defense,
			Category.FIELD to R.string.rules_group_field,
			Category.GLOBAL to R.string.rules_group_global,
			Category.TECHNICAL to R.string.rules_group_technical,
		)
		for ((cat, label) in groups) {
			val types = specials.filter { it.special!!.category == cat }
			if (types.isEmpty()) continue
			into.addView(subheading(getString(label)))
			for (t in types) {
				val help = CardText.help(this, t, familyFriendly)
				if (help.isEmpty()) continue
				val row = LinearLayout(this).apply {
					orientation = LinearLayout.HORIZONTAL
					gravity = Gravity.TOP
					setPadding(0, dp(6), 0, dp(6))
				}
				row.addView(ImageView(this).apply {
					setImageResource(CardText.drawable(this@RulesActivity, t, familyFriendly))
					adjustViewBounds = true
					contentDescription = CardText.cardName(this@RulesActivity, t, familyFriendly)
				}, LinearLayout.LayoutParams(dp(56), dp(84)))
				val text = LinearLayout(this).apply {
					orientation = LinearLayout.VERTICAL
					setPadding(dp(12), 0, 0, 0)
				}
				text.addView(TextView(this).apply {
					setText(CardText.shortName(this@RulesActivity, t, familyFriendly))
					setTextColor(ACCENT)
					setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
					setTypeface(typeface, android.graphics.Typeface.BOLD)
				})
				text.addView(body(help))
				row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
				into.addView(row)
			}
		}
	}

	private fun heading(text: String) = TextView(this).apply {
		this.text = text
		setTextColor(ACCENT)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
		setTypeface(typeface, android.graphics.Typeface.BOLD)
		setPadding(0, dp(20), 0, dp(4))
		isFocusable = true
	}

	private fun subheading(text: String) = TextView(this).apply {
		this.text = text
		setTextColor(Color.WHITE)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
		setTypeface(typeface, android.graphics.Typeface.BOLD)
		setPadding(0, dp(16), 0, dp(2))
	}

	private fun body(text: String) = TextView(this).apply {
		this.text = text
		setTextColor(0xFFDDDDDD.toInt())
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
		setLineSpacing(0f, 1.15f)
		setTextIsSelectable(false)
	}

	private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

	private companion object {
		const val ACCENT = 0xFFFFB74D.toInt()
	}
}
