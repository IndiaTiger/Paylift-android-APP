package com.example.di

import com.example.api.ApiClient
import com.example.config.AppConfig
import com.example.services.NotConfiguredPaymentCheckout
import com.example.services.PaymentCheckout
import okhttp3.Interceptor

/**
 * RELEASE wiring. No test gateway, no HTTP logging, no dev tools exist in this source set.
 *
 * To go live, replace [paymentCheckout] with the real gateway SDK adapter (e.g. Razorpay
 * Checkout) initialised with [AppConfig.paymentPublicKey]. Its result is sent to
 * POST /payments/verify; the server remains the only party that can credit the wallet.
 */
object VariantModule {
    fun interceptors(): List<Interceptor> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    fun paymentCheckout(client: ApiClient, config: AppConfig): PaymentCheckout = NotConfiguredPaymentCheckout()

    /** Release always uses the remote backend. */
    @Suppress("UNUSED_PARAMETER")
    fun createContainer(context: android.content.Context, config: AppConfig): AppContainer? = null
}
