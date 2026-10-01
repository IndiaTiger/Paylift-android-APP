package com.example.di

import com.example.api.ApiClient
import com.example.config.AppConfig
import com.example.dev.TestGatewayCheckout
import com.example.services.PaymentCheckout
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor

/**
 * DEBUG wiring (this source set is not compiled into release builds).
 *
 * Adds HTTP logging (headers redacted) and the test-gateway checkout, which is only functional
 * against a backend running PAYMENT_PROVIDER=test (the local test backend). A production
 * backend returns 404 for the test checkout endpoint, so this can never produce money there.
 */
object VariantModule {
    fun interceptors(): List<Interceptor> = listOf(
        HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
            redactHeader("Authorization")
        }
    )

    fun paymentCheckout(client: ApiClient, config: AppConfig): PaymentCheckout = TestGatewayCheckout(client)

    /** Debug talks to a real (test) backend; no in-process container. */
    @Suppress("UNUSED_PARAMETER")
    fun createContainer(context: android.content.Context, config: AppConfig): AppContainer? = null
}
