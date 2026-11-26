package com.smorgasbork.hotdeath.mp

import android.os.Handler
import android.os.Looper
import com.smorgasbork.hotdeath.net.Link
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/*
 * Online multiplayer through the relay server (relay/ in this repo). The
 * relay only forwards text between a host and up to three players in a room;
 * the game itself runs on the host's phone.
 *
 * Relay protocol, one WebSocket text frame per message:
 *   host  → relay  "#host"                      relay → host  "#room CODE"
 *   player→ relay  "#join CODE"                 relay → player "#joined"
 *                                               relay → host  "#peer N"
 *   host  → relay  "N|payload"  (to player N)   relay → player "payload"
 *   player→ relay  "payload"                    relay → host  "N|payload"
 *   host  → relay  "#kick N"                    relay → host  "#left N"
 *   relay → anyone "#error message"             relay → player "#closed" (host gone)
 * Game payloads are JSON objects, so they never start with '#'.
 */

private val http: OkHttpClient by lazy {
	OkHttpClient.Builder()
		.pingInterval(20, TimeUnit.SECONDS)
		.connectTimeout(15, TimeUnit.SECONDS)
		.build()
}

/** One WebSocket to the relay; [handle] and [dropped] run on the main thread. */
private abstract class RelaySocket(url: String, private val hello: String) : WebSocketListener() {
	private val main = Handler(Looper.getMainLooper())
	val ws: WebSocket = http.newWebSocket(Request.Builder().url(url).build(), this)
	@Volatile
	var ended = false

	abstract fun handle(text: String)
	abstract fun dropped(reason: String)

	override fun onOpen(webSocket: WebSocket, response: Response) {
		webSocket.send(hello)
	}

	override fun onMessage(webSocket: WebSocket, text: String) {
		main.post { if (!ended) handle(text) }
	}

	override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
		webSocket.close(1000, null)
		end(reason.ifEmpty { "relay closed the connection" })
	}

	override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
		end(t.message ?: "connection failed")
	}

	private fun end(reason: String) {
		main.post {
			if (!ended) {
				ended = true
				dropped(reason)
			}
		}
	}

	fun shutdown() {
		ended = true
		ws.close(1000, "bye")
	}
}

/** Opens a room on the relay and hands each player that joins to [onLink]. */
class RelayHost(url: String) {
	var onRoom: ((code: String) -> Unit)? = null
	var onLink: ((Link) -> Unit)? = null
	var onError: ((String) -> Unit)? = null

	private val peers = HashMap<Int, PeerLink>()
	private var accepting = true

	private inner class PeerLink(val n: Int) : MainThreadLink() {
		override fun doSend(text: String) {
			socket.ws.send("$n|$text")
		}

		override fun doClose() {
			peers.remove(n)
			socket.ws.send("#kick $n")
		}
	}

	private val socket = object : RelaySocket(url, "#host") {
		override fun handle(text: String) {
			when {
				text.startsWith("#room ") -> onRoom?.invoke(text.removePrefix("#room ").trim())
				text.startsWith("#peer ") -> {
					val n = text.removePrefix("#peer ").trim().toIntOrNull() ?: return
					if (!accepting) {
						ws.send("#kick $n")
						return
					}
					val link = PeerLink(n)
					peers[n] = link
					onLink?.invoke(link)
				}
				text.startsWith("#left ") -> text.removePrefix("#left ").trim().toIntOrNull()?.let { peers.remove(it)?.lost() }
				text.startsWith("#error ") -> onError?.invoke(text.removePrefix("#error "))
				text.startsWith("#") -> {}
				else -> {
					val bar = text.indexOf('|')
					if (bar <= 0) return
					val n = text.substring(0, bar).toIntOrNull() ?: return
					peers[n]?.received(text.substring(bar + 1))
				}
			}
		}

		override fun dropped(reason: String) {
			peers.values.toList().forEach { it.lost() }
			peers.clear()
			onError?.invoke(reason)
		}
	}

	/** Stops letting new players in (the relay room stays open for the game). */
	fun stopAccepting() {
		accepting = false
	}

	fun close() {
		peers.clear()
		socket.shutdown()
	}
}

/** Joins a relay room by code; [onConnected] gets the link to the host. */
class RelayClient(url: String, code: String) {
	var onConnected: ((Link) -> Unit)? = null
	var onError: ((String) -> Unit)? = null

	private var link: HostLink? = null

	private inner class HostLink : MainThreadLink() {
		override fun doSend(text: String) {
			socket.ws.send(text)
		}

		override fun doClose() = socket.shutdown()
	}

	private val socket = object : RelaySocket(url, "#join ${code.trim().uppercase()}") {
		override fun handle(text: String) {
			when {
				text == "#joined" -> HostLink().also { link = it; onConnected?.invoke(it) }
				text == "#closed" -> link?.lost()
				text.startsWith("#error ") -> {
					val msg = text.removePrefix("#error ")
					link?.lost() ?: onError?.invoke(msg)
				}
				text.startsWith("#") -> {}
				else -> link?.received(text)
			}
		}

		override fun dropped(reason: String) {
			link?.lost() ?: onError?.invoke(reason)
		}
	}

	fun close() = socket.shutdown()
}
