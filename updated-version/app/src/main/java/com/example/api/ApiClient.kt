package com.example.api

import com.example.api.dto.ErrorEnvelope
import com.example.api.dto.RefreshBody
import com.example.auth.TokenStore
import com.example.auth.Tokens
import com.example.core.AppError
import com.example.core.Outcome
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Builds the authenticated HTTP stack.
 *
 *  * Every request carries the current access token.
 *  * A 401 triggers exactly one refresh (single-flight, rotating refresh token); concurrent
 *    requests wait for it and retry with the new token.
 *  * If the refresh fails the session is cleared and [sessionExpired] fires; the UI returns to
 *    sign-in. No request is ever retried with a stale identity.
 */
/** Thrown (inside OkHttp) when no backend URL is configured for this build. */
class BackendNotConfiguredException : IOException("Backend not configured")

class ApiClient(
    configuredBaseUrl: String,
    private val tokenStore: TokenStore,
    extraInterceptors: List<Interceptor> = emptyList(),
    timeoutSeconds: Long = 20,
) {
    val moshi: Moshi = Moshi.Builder().build()

    val isConfigured: Boolean = configuredBaseUrl.isNotBlank()
    // Retrofit needs a syntactically valid URL; when unconfigured every call is short-circuited.
    private val baseUrl = if (isConfigured) configuredBaseUrl else "https://backend-not-configured.invalid/"

    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds * 2, TimeUnit.SECONDS)
        .addInterceptor { chain -> if (!isConfigured) throw BackendNotConfiguredException() else chain.proceed(chain.request()) }
        .apply { extraInterceptors.forEach { addInterceptor(it) } }
        .build()

    /** Unauthenticated API used only for token refresh (no authenticator -> no recursion). */
    // Own dispatcher: requests blocked in the authenticator occupy the main dispatcher's per-host
    // slots, so a refresh queued there would never run (deadlock under concurrent 401s).
    private val refreshApi: PayLiftApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(baseClient.newBuilder().dispatcher(okhttp3.Dispatcher()).build())
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(PayLiftApi::class.java)

    private val refreshLock = Any()

    private val authInterceptor = Interceptor { chain ->
        val token = tokenStore.read()?.accessToken
        val req = if (token != null) chain.request().newBuilder().header("Authorization", "Bearer $token").build() else chain.request()
        chain.proceed(req)
    }

    private val authenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.request.url.encodedPath.contains("/auth/")) return null
            if (responseCount(response) >= 2) return null
            val usedToken = response.request.header("Authorization")?.removePrefix("Bearer ")
            synchronized(refreshLock) {
                val current = tokenStore.read() ?: return null
                // Another request already refreshed while we were waiting.
                if (current.accessToken != usedToken) {
                    return response.request.newBuilder().header("Authorization", "Bearer ${current.accessToken}").build()
                }
                val refreshed = try {
                    runBlocking { refreshApi.refresh(RefreshBody(current.refreshToken)) }
                } catch (e: HttpException) {
                    tokenStore.clear()
                    _sessionExpired.tryEmit(Unit)
                    return null
                } catch (e: IOException) {
                    return null // network failure: surface the 401 without logging the user out
                }
                val tokens = Tokens(refreshed.accessToken, refreshed.refreshToken)
                tokenStore.write(tokens)
                return response.request.newBuilder().header("Authorization", "Bearer ${tokens.accessToken}").build()
            }
        }
    }

    val api: PayLiftApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(baseClient.newBuilder().addInterceptor(authInterceptor).authenticator(authenticator).build())
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(PayLiftApi::class.java)

    private fun responseCount(response: Response): Int {
        var r: Response? = response
        var count = 0
        while (r != null) {
            count++
            r = r.priorResponse
        }
        return count
    }

    /** Executes an API call and maps every failure mode to an [AppError]. */
    suspend fun <T> call(block: suspend PayLiftApi.() -> T): Outcome<T> = try {
        Outcome.Success(api.block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        Outcome.Failure(mapHttpError(e))
    } catch (e: BackendNotConfiguredException) {
        Outcome.Failure(AppError.NotConfigured("BACKEND", "PayLift services are not configured in this build"))
    } catch (e: SocketTimeoutException) {
        Outcome.Failure(AppError.Timeout)
    } catch (e: InterruptedIOException) {
        Outcome.Failure(AppError.Timeout)
    } catch (e: UnknownHostException) {
        Outcome.Failure(AppError.Offline)
    } catch (e: ConnectException) {
        Outcome.Failure(AppError.Offline)
    } catch (e: IOException) {
        Outcome.Failure(AppError.Offline)
    } catch (e: com.squareup.moshi.JsonDataException) {
        Outcome.Failure(AppError.Unexpected("Unexpected response from server"))
    } catch (e: com.squareup.moshi.JsonEncodingException) {
        Outcome.Failure(AppError.Unexpected("Malformed response from server"))
    }

    private fun mapHttpError(e: HttpException): AppError {
        val body = try {
            e.response()?.errorBody()?.string()?.let { moshi.adapter(ErrorEnvelope::class.java).fromJson(it)?.error }
        } catch (_: Exception) {
            null
        }
        val code = body?.code ?: "HTTP_${e.code()}"
        val message = body?.message ?: "Request failed (${e.code()})"
        return when {
            e.code() == 401 -> AppError.Unauthorized
            e.code() == 503 && code.endsWith("_NOT_CONFIGURED") -> AppError.NotConfigured(code.removeSuffix("_NOT_CONFIGURED"), message)
            else -> AppError.Api(e.code(), code, message)
        }
    }
}
