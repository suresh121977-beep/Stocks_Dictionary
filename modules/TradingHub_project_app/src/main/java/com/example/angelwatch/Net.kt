package com.example.angelwatch

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Finds the phone's current public IPv4 (what Angel One sees for order calls). */
object Net {
    @Volatile var lastIp: String? = null
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    fun publicIp(): String {
        var err: String? = null
        for (u in listOf("https://api.ipify.org", "https://ipv4.icanhazip.com", "https://checkip.amazonaws.com")) {
            try {
                http.newCall(Request.Builder().url(u).build()).execute().use { r ->
                    val t = r.body?.string().orEmpty().trim()
                    if (r.isSuccessful && IPV4.matches(t)) {
                        lastIp = t
                        return t
                    }
                }
            } catch (e: Exception) {
                err = e.message
            }
        }
        throw IOException("Could not read your IP" + (err?.let { ": $it" } ?: ""))
    }

    private val IP_WORDS = Regex("(?i)\\bip\\b|static|whitelist")

    /** Adds a plain explanation when Angel One rejects an order because of the IP rule. */
    fun explain(message: String): String =
        if (IP_WORDS.containsMatchIn(message))
            "$message\n\nBlocked by Angel One's static IP rule. Your current IP is ${lastIp ?: "unknown (open Account to see it)"}; " +
                "orders only work from the IP registered in your SmartAPI app."
        else message
}
