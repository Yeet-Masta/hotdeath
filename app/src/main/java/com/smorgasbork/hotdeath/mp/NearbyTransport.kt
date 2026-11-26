package com.smorgasbork.hotdeath.mp

import android.Manifest
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

/*
 * Local multiplayer over Google Nearby Connections (Bluetooth / Wi-Fi, no
 * internet). The host advertises; players discover it and connect. Nearby
 * calls back on the main thread.
 */

private const val SERVICE_ID = "com.smorgasbork.hotdeath.mp1"
private val STRATEGY = Strategy.P2P_STAR
private const val TAG = "HDU-nearby"

/**
 * Runtime permissions Nearby Connections needs on this Android version.
 * Location is asked for on every version: discovery fails with
 * MISSING_PERMISSION_ACCESS_COARSE_LOCATION (8034) without it, even on Android 13+.
 */
fun nearbyPermissions(): Array<String> {
	val location = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
	val radios = when {
		Build.VERSION.SDK_INT >= 33 -> listOf(
			Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
			Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES,
		)
		Build.VERSION.SDK_INT >= 31 -> listOf(
			Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
			Manifest.permission.BLUETOOTH_SCAN,
		)
		else -> emptyList()
	}
	return (radios + location).toTypedArray()
}

private class NearbyLink(private val client: ConnectionsClient, private val endpointId: String) : MainThreadLink() {
	override fun doSend(text: String) {
		val bytes = text.toByteArray(Charsets.UTF_8)
		if (bytes.size > ConnectionsClient.MAX_BYTES_DATA_SIZE) {
			Log.e(TAG, "message too large for Nearby (${bytes.size} bytes); dropped")
			return
		}
		client.sendPayload(endpointId, Payload.fromBytes(bytes))
	}

	override fun doClose() = client.disconnectFromEndpoint(endpointId)
}

/** Payloads for a set of endpoints, routed to their links. */
private class Router {
	val links = HashMap<String, NearbyLink>()

	val payloads = object : PayloadCallback() {
		override fun onPayloadReceived(endpointId: String, payload: Payload) {
			if (payload.type != Payload.Type.BYTES) return
			payload.asBytes()?.let { links[endpointId]?.received(String(it, Charsets.UTF_8)) }
		}

		override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
	}

	fun lifecycle(onConnected: (NearbyLink) -> Unit, onFailed: (String) -> Unit, client: ConnectionsClient) =
		object : ConnectionLifecycleCallback() {
			override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
				client.acceptConnection(endpointId, payloads)
			}

			override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
				if (result.status.isSuccess) {
					val link = NearbyLink(client, endpointId)
					links[endpointId] = link
					onConnected(link)
				} else {
					onFailed(result.status.statusMessage ?: "connection refused")
				}
			}

			override fun onDisconnected(endpointId: String) {
				links.remove(endpointId)?.lost()
			}
		}
}

/** Advertises a game and hands each player that connects to [onLink]. */
class NearbyHost(context: Context, private val name: String) {
	private val client = Nearby.getConnectionsClient(context.applicationContext)
	private val router = Router()

	var onLink: ((com.smorgasbork.hotdeath.net.Link) -> Unit)? = null
	var onError: ((String) -> Unit)? = null

	fun start() {
		val lifecycle = router.lifecycle({ onLink?.invoke(it) }, { Log.w(TAG, it) }, client)
		client.startAdvertising(name, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
			.addOnFailureListener { onError?.invoke(it.message ?: "could not advertise") }
	}

	/** Stops letting new players in; connected players stay connected. */
	fun stopAccepting() = client.stopAdvertising()

	fun close() {
		client.stopAdvertising()
		client.stopAllEndpoints()
		router.links.clear()
	}
}

/** Finds nearby hosts and connects to one. */
class NearbyClient(context: Context, private val name: String) {
	private val client = Nearby.getConnectionsClient(context.applicationContext)
	private val router = Router()

	var onFound: ((endpointId: String, hostName: String) -> Unit)? = null
	var onLost: ((endpointId: String) -> Unit)? = null
	var onConnected: ((com.smorgasbork.hotdeath.net.Link) -> Unit)? = null
	var onError: ((String) -> Unit)? = null

	fun startDiscovery() {
		val callback = object : EndpointDiscoveryCallback() {
			override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
				if (info.serviceId == SERVICE_ID) onFound?.invoke(endpointId, info.endpointName)
			}

			override fun onEndpointLost(endpointId: String) {
				onLost?.invoke(endpointId)
			}
		}
		client.startDiscovery(SERVICE_ID, callback, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
			.addOnFailureListener { onError?.invoke(it.message ?: "could not search") }
	}

	fun connect(endpointId: String) {
		val lifecycle = router.lifecycle({
			client.stopDiscovery()
			onConnected?.invoke(it)
		}, { onError?.invoke(it) }, client)
		client.requestConnection(name, endpointId, lifecycle)
			.addOnFailureListener { onError?.invoke(it.message ?: "could not connect") }
	}

	fun close() {
		client.stopDiscovery()
		client.stopAllEndpoints()
		router.links.clear()
	}
}
