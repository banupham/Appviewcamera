package com.banupham.appviewcamera.gateway.server

import java.security.MessageDigest
import java.util.Base64

class RtspAuthenticator(
    private val username: String,
    private val password: String
) {
    private var challenge: Challenge? = null
    private var nonceCount = 0

    fun acceptChallenge(header: String): Boolean {
        val scheme = header.substringBefore(' ').trim().lowercase()
        val parameters = parseParameters(header.substringAfter(' ', ""))
        challenge = when (scheme) {
            "basic" -> Challenge.Basic
            "digest" -> Challenge.Digest(
                realm = parameters["realm"].orEmpty(),
                nonce = parameters["nonce"].orEmpty(),
                opaque = parameters["opaque"],
                algorithm = parameters["algorithm"].orEmpty().ifBlank { "MD5" },
                qop = parameters["qop"]?.split(',')?.map(String::trim)?.firstOrNull { it.equals("auth", true) }
            ).takeIf { it.realm.isNotBlank() && it.nonce.isNotBlank() }
            else -> null
        }
        nonceCount = 0
        return challenge != null
    }

    fun authorization(method: String, uri: String): String? = when (val current = challenge) {
        null -> null
        Challenge.Basic -> "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        is Challenge.Digest -> digest(current, method, uri)
    }

    private fun digest(challenge: Challenge.Digest, method: String, uri: String): String {
        nonceCount += 1
        val nc = nonceCount.toString(16).padStart(8, '0')
        val cnonce = md5Hex("$username:${challenge.nonce}:$nc").take(16)
        val baseHa1 = md5Hex("$username:${challenge.realm}:$password")
        val ha1 = if (challenge.algorithm.equals("MD5-sess", true)) {
            md5Hex("$baseHa1:${challenge.nonce}:$cnonce")
        } else baseHa1
        val ha2 = md5Hex("$method:$uri")
        val response = if (challenge.qop != null) {
            md5Hex("$ha1:${challenge.nonce}:$nc:$cnonce:${challenge.qop}:$ha2")
        } else {
            md5Hex("$ha1:${challenge.nonce}:$ha2")
        }
        return buildString {
            append("Digest username=\"").append(escape(username)).append("\"")
            append(", realm=\"").append(escape(challenge.realm)).append("\"")
            append(", nonce=\"").append(escape(challenge.nonce)).append("\"")
            append(", uri=\"").append(escape(uri)).append("\"")
            append(", response=\"").append(response).append("\"")
            append(", algorithm=").append(challenge.algorithm)
            challenge.opaque?.let { append(", opaque=\"").append(escape(it)).append("\"") }
            challenge.qop?.let { append(", qop=").append(it).append(", nc=").append(nc).append(", cnonce=\"").append(cnonce).append("\"") }
        }
    }

    private fun md5Hex(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun parseParameters(value: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < value.length) {
            while (index < value.length && (value[index].isWhitespace() || value[index] == ',')) index++
            val equals = value.indexOf('=', index)
            if (equals < 0) break
            val key = value.substring(index, equals).trim().lowercase()
            index = equals + 1
            val parsed = if (index < value.length && value[index] == '"') {
                index++
                val output = StringBuilder()
                while (index < value.length && value[index] != '"') {
                    if (value[index] == '\\' && index + 1 < value.length) index++
                    output.append(value[index++])
                }
                if (index < value.length) index++
                output.toString()
            } else {
                val comma = value.indexOf(',', index).let { if (it < 0) value.length else it }
                value.substring(index, comma).trim().also { index = comma }
            }
            result[key] = parsed
        }
        return result
    }

    private sealed interface Challenge {
        data object Basic : Challenge
        data class Digest(
            val realm: String,
            val nonce: String,
            val opaque: String?,
            val algorithm: String,
            val qop: String?
        ) : Challenge
    }
}
