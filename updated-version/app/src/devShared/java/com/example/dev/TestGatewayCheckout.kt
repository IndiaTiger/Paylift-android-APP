package com.example.dev

import com.example.api.ApiClient
import com.example.api.dto.TestCheckoutBody
import com.example.core.Outcome
import com.example.core.map
import com.example.services.CheckoutResult
import com.example.services.PaymentCheckout
import com.example.services.PaymentOrder

// Shared by the debug and demo build types only (src/devShared). Not part of release.

/**
 * Stands in for a gateway SDK against the local test backend: asks the test gateway to "pay" and
 * returns the provider payment id + signature exactly like a real SDK would. The backend verifies
 * the signature; this class cannot credit anything by itself.
 */
class TestGatewayCheckout(private val client: ApiClient, private val outcome: () -> String = { "SUCCESS" }) : PaymentCheckout {
    override val providerLabel: String = "Test Gateway"
    override val isAvailable: Boolean get() = client.isConfigured

    override suspend fun launch(order: PaymentOrder): Outcome<CheckoutResult> =
        client.call { testCheckout(TestCheckoutBody(order.paymentId, outcome())) }.map { r ->
            when (r.outcome) {
                "SUCCESS" -> CheckoutResult.Completed(r.providerPaymentId.orEmpty(), r.signature.orEmpty())
                "CANCELLED" -> CheckoutResult.Cancelled
                else -> CheckoutResult.Failed("Declined by test gateway")
            }
        }
}
