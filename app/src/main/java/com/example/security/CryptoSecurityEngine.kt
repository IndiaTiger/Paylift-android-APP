package com.example.security

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Enterprise Cryptographic Engine for PayLift Escrow & Gateway Webhooks.
 * Uses HMAC-SHA256 verification to ensure payment callbacks and webhook payloads
 * cannot be forged or tampered with.
 */
object CryptoSecurityEngine {

    // Server-side simulated secret key for PayLift Escrow Gateway
    private const val DEFAULT_SECRET = "paylift_escrow_sec_key_9942a7c4f801"

    /**
     * Computes HMAC-SHA256 signature for a payment payload.
     * Payload format: "$gateway|$amount|$referenceId|$timestamp"
     */
    fun computeHmacSha256(
        payload: String,
        secretKey: String = DEFAULT_SECRET
    ): String {
        return try {
            val keySpec = SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(keySpec)
            val bytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Fallback hash if crypto provider is restricted
            val md = MessageDigest.getInstance("SHA-256")
            val fallbackBytes = md.digest("$payload:$secretKey".toByteArray(Charsets.UTF_8))
            fallbackBytes.joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Validates an incoming payment gateway callback signature against the reconstructed payload.
     */
    fun verifySignature(
        gateway: String,
        amount: Double,
        referenceId: String,
        timestamp: Long,
        receivedSignature: String,
        secretKey: String = DEFAULT_SECRET
    ): Boolean {
        val payload = "$gateway|${"%.2f".format(amount)}|$referenceId|$timestamp"
        val expectedSignature = computeHmacSha256(payload, secretKey)
        return expectedSignature.equals(receivedSignature, ignoreCase = true)
    }

    /**
     * Generates a realistic unique Transaction / Escrow Reference ID.
     */
    fun generateReferenceId(gateway: String): String {
        val prefix = when (gateway.uppercase()) {
            "RAZORPAY" -> "pay_rzp_"
            "CASHFREE" -> "cf_order_"
            "STRIPE" -> "pi_strp_"
            else -> "pl_escrow_"
        }
        val randomAlpha = (1..8)
            .map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }
            .joinToString("")
        return "$prefix$randomAlpha"
    }
}
