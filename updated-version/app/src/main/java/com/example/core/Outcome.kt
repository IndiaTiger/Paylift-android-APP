package com.example.core

/**
 * Result of any operation that can fail. Every service/repository call returns one of these, so
 * failures cannot be silently swallowed and the UI can always distinguish the states it must show.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>
}

sealed class AppError(open val message: String) {
    /** No network connectivity. Retryable. */
    data object Offline : AppError("You're offline. Check your connection and retry.")

    /** The request timed out; the outcome on the server is unknown. Retryable with the same idempotency key. */
    data object Timeout : AppError("The request timed out. Please retry.")

    /** Session expired or revoked; the user must sign in again. */
    data object Unauthorized : AppError("Your session expired. Please sign in again.")

    /** A backend/provider is not configured for this build (e.g. missing credentials). */
    data class NotConfigured(val service: String, override val message: String) : AppError(message)

    /** The server rejected the request with a structured error. */
    data class Api(val httpStatus: Int, val code: String, override val message: String) : AppError(message)

    /** Anything unexpected (malformed response, bug). */
    data class Unexpected(override val message: String) : AppError(message)

    val isRetryable: Boolean get() = this is Offline || this is Timeout || (this is Api && httpStatus >= 500)
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(block: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) block(value)
    return this
}

inline fun <T> Outcome<T>.onFailure(block: (AppError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) block(error)
    return this
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value
