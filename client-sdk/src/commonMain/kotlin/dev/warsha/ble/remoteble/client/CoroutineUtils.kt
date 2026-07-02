package dev.warsha.ble.remoteble.client

import kotlinx.coroutines.CancellationException

/**
 * Executes [block], catching non-cancellation exceptions while ensuring that
 * [CancellationException] is always rethrown to preserve structured coroutine cancellation.
 */
public inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
