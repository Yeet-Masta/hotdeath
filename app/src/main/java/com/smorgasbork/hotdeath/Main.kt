package com.smorgasbork.hotdeath

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog

class Main : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.main)

		findViewById<View>(R.id.btn_continue).setOnClickListener { startGame(GameActivity.STARTUP_MODE_CONTINUE) }
		findViewById<View>(R.id.btn_new_game).setOnClickListener { startGame(GameActivity.STARTUP_MODE_NEW) }
		findViewById<View>(R.id.btn_multiplayer).setOnClickListener { startActivity(Intent(this, MultiplayerActivity::class.java)) }
		findViewById<View>(R.id.btn_settings).setOnClickListener { startActivity(Intent(this, Prefs::class.java)) }
		findViewById<View>(R.id.btn_help).setOnClickListener {
			showTextDialog(R.layout.dlg_help, R.string.dlg_help_title, getString(R.string.dlg_help_text))
		}
		findViewById<View>(R.id.btn_about).setOnClickListener {
			showTextDialog(R.layout.dlg_about, R.string.dlg_about_title, getString(R.string.dlg_about_text, versionName()))
		}
		findViewById<View>(R.id.btn_exit).setOnClickListener { finish() }
	}

	override fun onResume() {
		super.onResume()
		val hasSave = PrefsConfig(this).savedGame != null
		findViewById<View>(R.id.btn_continue).visibility = if (hasSave) View.VISIBLE else View.GONE
	}

	private fun startGame(mode: Int) {
		startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.STARTUP_MODE, mode))
	}

	private fun showTextDialog(@LayoutRes layout: Int, @StringRes title: Int, body: String) {
		AppCompatDialog(this).apply {
			setContentView(layout)
			setTitle(title)
			findViewById<TextView>(R.id.text)?.apply {
				movementMethod = ScrollingMovementMethod.getInstance()
				text = body
			}
			show()
		}
	}

	private fun versionName(): String = try {
		val info = if (Build.VERSION.SDK_INT >= 33) {
			packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
		} else {
			@Suppress("DEPRECATION")
			packageManager.getPackageInfo(packageName, 0)
		}
		info.versionName ?: ""
	} catch (e: PackageManager.NameNotFoundException) {
		""
	}
}
