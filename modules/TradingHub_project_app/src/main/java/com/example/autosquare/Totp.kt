package com.example.autosquare
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Totp {
    private fun b32(s: String): ByteArray {
        val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buf = 0; var bits = 0; val out = java.io.ByteArrayOutputStream()
        for (c in s.uppercase().filter { it in a }) {
            buf = (buf shl 5) or a.indexOf(c); bits += 5
            if (bits >= 8) { out.write((buf shr (bits - 8)) and 0xFF); bits -= 8 }
        }
        return out.toByteArray()
    }
    fun now(secret: String): String {
        val t = System.currentTimeMillis() / 1000 / 30
        val m = Mac.getInstance("HmacSHA1"); m.init(SecretKeySpec(b32(secret), "HmacSHA1"))
        val h = m.doFinal(java.nio.ByteBuffer.allocate(8).putLong(t).array())
        val o = h[h.size - 1].toInt() and 0xF
        val bin = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or
                ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        return (bin % 1_000_000).toString().padStart(6, '0')
    }
}
