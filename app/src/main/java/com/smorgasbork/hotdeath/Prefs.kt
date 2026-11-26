package com.smorgasbork.hotdeath

import android.os.Bundle
import androidx.annotation.XmlRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager

/** Settings screen. The values are read through [PrefsConfig]. */
class Prefs : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_prefs)

		PreferenceManager.setDefaultValues(this, R.xml.main_preferences, false)

		if (savedInstanceState == null) {
			supportFragmentManager
					.beginTransaction()
					.replace(R.id.settings, PrefsFragment())
					.commit()
		}
	}

	/** Shows one preferences XML with every icon tinted white for the dark theme. */
	abstract class TintedPrefsFragment(@param:XmlRes private val xml: Int) : PreferenceFragmentCompat() {
		override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
			setPreferencesFromResource(xml, rootKey)
			tintAllIcons(preferenceScreen)
		}

		private fun tintAllIcons(group: PreferenceGroup) {
			val color = ContextCompat.getColor(requireContext(), android.R.color.white)
			for (i in 0 until group.preferenceCount) {
				val preference = group.getPreference(i)
				preference.icon?.let { DrawableCompat.setTint(it, color) }
				if (preference is PreferenceGroup) tintAllIcons(preference)
			}
		}
	}

	class PrefsFragment : TintedPrefsFragment(R.xml.main_preferences)

	class PlayersPrefsFragment : TintedPrefsFragment(R.xml.opponent_preferences)
}
