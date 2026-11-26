package com.smorgasbork.hotdeath

import android.content.Context
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatDialog
import kotlin.math.abs

/** A dialog that closes when tapped anywhere (but not when scrolled). */
open class TapDismissableDialog(context: Context) : AppCompatDialog(context) {
	private var downX = 0f
	private var downY = 0f
	private val threshold = 7 * context.resources.displayMetrics.density

	override fun onTouchEvent(event: MotionEvent): Boolean {
		when (event.action) {
			MotionEvent.ACTION_DOWN -> {
				downX = event.x
				downY = event.y
			}
			MotionEvent.ACTION_UP ->
				if (abs(event.x - downX) <= threshold && abs(event.y - downY) <= threshold) dismiss()
		}
		return true
	}
}
