package com.example.services

import com.example.core.AppError
import com.example.core.Outcome

/**
 * Used when no payment gateway SDK is integrated (release builds until real credentials and the
 * provider SDK are added). Reports itself unavailable; it can never produce a successful payment.
 */
class NotConfiguredPaymentCheckout : PaymentCheckout {
    override val providerLabel: String = "Not configured"
    override val isAvailable: Boolean = false
    override suspend fun launch(order: PaymentOrder): Outcome<CheckoutResult> =
        Outcome.Failure(AppError.NotConfigured("PAYMENT_PROVIDER", "Online payments are not available yet in this build"))
}
