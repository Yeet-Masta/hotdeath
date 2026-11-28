package com.smorgasbork.hotdeath

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View

/**
 * Short vibrations for game moments; nothing when [enabled] says no.
 *
 * Uses the vibrator directly rather than [View.performHapticFeedback]: touch
 * feedback follows the phone's "touch vibration" setting, which many phones
 * ship turned off (and apps targeting Android 13+ can no longer override), so
 * those buzzes were often silent. These count as game vibrations instead.
 */
class Haptics(view: View, private val enabled: () -> Boolean) {

	private val vibrator: Vibrator? = vibratorOf(view.context)

	private val attributes: AudioAttributes = AudioAttributes.Builder()
		.setUsage(AudioAttributes.USAGE_GAME)
		.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
		.build()

	/** Plays [predefined] where the phone has it, else a [ms]-long pulse at [amplitude] (1..255). */
	private fun buzz(predefined: Int?, ms: Long, amplitude: Int) {
		val v = vibrator ?: return
		if (!enabled() || !v.hasVibrator()) return
		if (Build.VERSION.SDK_INT < 26) {
			@Suppress("DEPRECATION") v.vibrate(ms, attributes)
			return
		}
		val effect = if (Build.VERSION.SDK_INT >= 29 && predefined != null) VibrationEffect.createPredefined(predefined)
			else VibrationEffect.createOneShot(ms, if (v.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE)
		if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
		else @Suppress("DEPRECATION") v.vibrate(effect, attributes)
	}

	/** It is your turn. */
	fun yourTurn() = buzz(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_CLICK else null, 30, 160)

	/** That card cannot be played. */
	fun reject() = buzz(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_DOUBLE_CLICK else null, 60, 200)

	/** You are attacked, take a penalty, or ran out of time. */
	fun hit() = buzz(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_HEAVY_CLICK else null, 90, 255)

	/** A short nudge: time nearly up, or a chance to jump in. */
	fun warn() = buzz(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_TICK else null, 15, 120)

	private companion object {
		fun vibratorOf(ctx: Context): Vibrator? =
			if (Build.VERSION.SDK_INT >= 31) {
				(ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
			} else {
				@Suppress("DEPRECATION")
				ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
			}
	}
}
