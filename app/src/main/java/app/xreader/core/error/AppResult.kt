package app.xreader.core.error

import kotlin.coroutines.cancellation.CancellationException

/**
 * The single result type that crosses every layer boundary (PLAN §1.5).
 * Nothing in data / domain throws across a boundary: failures are returned
 * as [Err] carrying a typed [AppError].
 */
sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>
    data class Err(val error: AppError) : AppResult<Nothing>

    val isOk: Boolean get() = this is Ok

    fun getOrNull(): T? = (this as? Ok)?.value

    fun errorOrNull(): AppError? = (this as? Err)?.error

    fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }

    fun <R> flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
        is Ok -> transform(value)
        is Err -> this
    }

    fun onOk(action: (T) -> Unit): AppResult<T> {
        if (this is Ok) action(value)
        return this
    }

    fun onErr(action: (AppError) -> Unit): AppResult<T> {
        if (this is Err) action(error)
        return this
    }

    fun <R> fold(onOk: (T) -> R, onErr: (AppError) -> R): R = when (this) {
        is Ok -> onOk(value)
        is Err -> onErr(error)
    }
}

/** Runs [block], mapping any thrown exception to [AppError.Unknown].
 * Cancellation always propagates — it is not a failure. */
inline fun <T> runCatchingApp(block: () -> T): AppResult<T> =
    try {
        AppResult.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppResult.Err(AppError.Unknown(e))
    }

/** Suspending twin of [runCatchingApp]. */
suspend inline fun <T> runCatchingAppSuspend(crossinline block: suspend () -> T): AppResult<T> =
    try {
        AppResult.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppResult.Err(AppError.Unknown(e))
    }
