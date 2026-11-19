package com.adam.assistant.core

import java.util.Locale

object PhoneNumber {

    private val countryCodes = mapOf(
        "AE" to "971", "AR" to "54", "AT" to "43", "AU" to "61", "BD" to "880",
        "BE" to "32", "BR" to "55", "CA" to "1", "CH" to "41", "CL" to "56",
        "CN" to "86", "CO" to "57", "CZ" to "420", "DE" to "49", "DK" to "45",
        "EG" to "20", "ES" to "34", "FI" to "358", "FR" to "33", "GB" to "44",
        "GR" to "30", "HK" to "852", "ID" to "62", "IE" to "353", "IL" to "972",
        "IN" to "91", "IT" to "39", "JP" to "81", "KE" to "254", "KR" to "82",
        "MX" to "52", "MY" to "60", "NG" to "234", "NL" to "31", "NO" to "47",
        "NZ" to "64", "PE" to "51", "PH" to "63", "PK" to "92", "PL" to "48",
        "PT" to "351", "RO" to "40", "RU" to "7", "SA" to "966", "SE" to "46",
        "SG" to "65", "TH" to "66", "TR" to "90", "UA" to "380", "US" to "1",
        "VN" to "84", "ZA" to "27"
    )

    private const val PUNCTUATION = "+-(). "

    fun normalize(raw: String, region: String?): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { !it.isDigit() && it !in PUNCTUATION }) return null
        var digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return null

        val international = when {
            trimmed.startsWith("+") -> true
            trimmed.startsWith("00") -> {
                digits = digits.drop(2)
                true
            }
            else -> false
        }

        val full = if (international) {
            digits
        } else {
            val code = region?.trim()?.uppercase(Locale.ROOT)
                ?.takeIf { it.length == 2 }
                ?.let(countryCodes::get)
                ?: return null
            val national = if (digits.startsWith(code) && digits.length > code.length + 5) {
                digits
            } else {
                digits.trimStart('0')
            }
            if (national.length < 6) return null
            code + national
        }

        if (full.length !in 8..15) return null
        return "+$full"
    }

    fun isPlausible(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.any { !it.isDigit() && it !in PUNCTUATION }) return false
        return trimmed.count { it.isDigit() } in 5..15
    }

    fun same(first: String, second: String, region: String?): Boolean {
        val a = normalize(first, region)
        val b = normalize(second, region)
        if (a != null && b != null) return a == b
        val da = first.filter { it.isDigit() }
        val db = second.filter { it.isDigit() }
        return da.length >= 5 && da == db
    }

    fun digits(raw: String): String = raw.filter { it.isDigit() }

    fun display(raw: String, region: String?): String =
        normalize(raw, region) ?: raw.trim()
}
