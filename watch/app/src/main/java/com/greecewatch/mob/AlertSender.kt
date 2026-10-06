package com.greecewatch.mob

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Posts events to the relay server, which forwards them to the WhatsApp group. */
class AlertSender(
    private val relayUrl: String = BuildConfig.RELAY_URL,
    private val token: String = BuildConfig.RELAY_TOKEN,
    private val name: String = BuildConfig.CREW_NAME,
) {
    val isConfigured: Boolean get() = relayUrl.startsWith("http") && token.length >= 16

    fun payload(type: String, eventId: String, location: Location?, seq: Int = 0): JSONObject =
        JSONObject().apply {
            put("type", type)
            put("name", name)
            put("eventId", eventId)
            put("seq", seq)
            put("timestamp", System.currentTimeMillis())
            if (location != null) {
                put("lat", location.latitude)
                put("lon", location.longitude)
                if (location.hasAccuracy()) put("accuracy", location.accuracy.toDouble())
            }
        }

    /** One attempt. Returns the HTTP status, or null if the network failed. */
    suspend fun post(body: JSONObject): Int? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(relayUrl.trimEnd('/') + "/alert").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            conn.responseCode.also { conn.disconnect() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Keeps retrying until the relay accepts the message (2xx) or [giveUpAfterMs] passes.
     * Under water there is no signal, so this is what gets the alert out once the
     * wearer surfaces. Returns true if delivered.
     */
    suspend fun postUntilDelivered(
        body: JSONObject,
        giveUpAfterMs: Long = Long.MAX_VALUE,
        onAttempt: (attempt: Int, status: Int?) -> Unit = { _, _ -> },
    ): Boolean {
        val start = System.currentTimeMillis()
        var backoffMs = 1_000L
        var attempt = 0
        while (true) {
            attempt++
            val status = post(body)
            onAttempt(attempt, status)
            if (status != null && status in 200..299) return true
            // 4xx other than rate limiting means a config problem; retrying won't help.
            if (status != null && status in 400..499 && status != 429) return false
            if (System.currentTimeMillis() - start + backoffMs > giveUpAfterMs) return false
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(10_000)
        }
    }
}
