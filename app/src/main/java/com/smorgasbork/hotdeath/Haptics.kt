package com.smorgasbork.hotdeath

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** Short vibrations for game moments, through [view]; nothing when [enabled] says no. */
class Haptics(private val view: View, private val enabled: () -> Boolean) {

	private fun buzz(constant: Int) {
		if (!enabled()) return
		@Suppress("DEPRECATION")
		view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
	}

	/** It is your turn. */
	fun yourTurn() = buzz(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)

	/** That card cannot be played. */
	fun reject() = buzz(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

	/** You are attacked, take a penalty, or ran out of time. */
	fun hit() = buzz(HapticFeedbackConstants.LONG_PRESS)

	/** Your time is nearly up. */
	fun warn() = buzz(if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_PRESS else HapticFeedbackConstants.KEYBOARD_TAP)
}
