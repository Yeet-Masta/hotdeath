package com.smorgasbork.hotdeath.mp

import android.os.Handler
import android.os.Looper
import com.smorgasbork.hotdeath.net.Link

/**
 * Base for transport links: whatever thread the transport calls back on,
 * [onMessage] and [onClosed] run on the main thread, and nothing is
 * delivered after the link is closed from either side.
 */
abstract class MainThreadLink : Link {
	private val main = Handler(Looper.getMainLooper())

	override var onMessage: ((String) -> Unit)? = null
	override var onClosed: (() -> Unit)? = null

	@Volatile
	private var closed = false

	protected abstract fun doSend(text: String)
	protected abstract fun doClose()

	override fun send(text: String) {
		if (!closed) doSend(text)
	}

	override fun close() {
		if (closed) return
		closed = true
		doClose()
	}

	/** The transport received [text] for this link. */
	fun received(text: String) {
		main.post { if (!closed) onMessage?.invoke(text) }
	}

	/** The other side went away. */
	fun lost() {
		main.post {
			if (!closed) {
				closed = true
				onClosed?.invoke()
			}
		}
	}
}
