package com.smorgasbork.hotdeath

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * What a client needs to get back into a multiplayer game after the
 * connection drops: the seat's token and how the host was reached.
 */
object Rejoin {
	private const val TOKEN = "rejoin_token"
	private const val VIA = "rejoin_via"
	private const val HOST = "rejoin_host"
	private const val URL = "rejoin_url"
	private const val CODE = "rejoin_code"

	const val VIA_NEARBY = "nearby"
	const val VIA_ONLINE = "online"

	class Info(val token: String, val via: String, val hostName: String?, val url: String?, val code: String?)

	private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)

	fun save(ctx: Context, token: String, via: String, hostName: String? = null, url: String? = null, code: String? = null) {
		prefs(ctx).edit()
			.putString(TOKEN, token).putString(VIA, via)
			.putString(HOST, hostName).putString(URL, url).putString(CODE, code)
			.apply()
	}

	fun load(ctx: Context): Info? {
		val p = prefs(ctx)
		val token = p.getString(TOKEN, null) ?: return null
		val via = p.getString(VIA, null) ?: return null
		return Info(token, via, p.getString(HOST, null), p.getString(URL, null), p.getString(CODE, null))
	}

	fun saved(ctx: Context): Boolean = load(ctx) != null

	fun clear(ctx: Context) {
		val p = prefs(ctx)
		if (!p.contains(TOKEN)) return
		p.edit().remove(TOKEN).remove(VIA).remove(HOST).remove(URL).remove(CODE).apply()
	}
}
