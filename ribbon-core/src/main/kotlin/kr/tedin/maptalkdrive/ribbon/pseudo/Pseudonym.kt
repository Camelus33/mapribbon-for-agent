package kr.tedin.maptalkdrive.ribbon.pseudo

import java.time.LocalDate
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The only vehicle identity a knot carries: an HMAC of the date under a secret that never leaves the vehicle.
 * Two knots from the same car on the same day share a pseudonym (so "3 distinct vehicles" can be counted);
 * knots from different days cannot be linked, and no account or VIN is involved.
 */
object Pseudonym {
    fun forDay(deviceSecret: ByteArray, day: LocalDate): String {
        require(deviceSecret.size >= 16) { "device secret too short" }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(deviceSecret, "HmacSHA256")) }
        val digest = mac.doFinal("maptalk.knot:$day".toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(16)
    }
}
