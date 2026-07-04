package dev.warsha.remoteble.client

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injection seam for the dispatcher backing the SDK's own coroutine scopes.
 *
 * Exists so tests can substitute a deterministic dispatcher (e.g. a `TestDispatcher`)
 * for [Dispatchers.Default]. Only [default] is modelled because it is the only
 * dispatcher this library schedules work on — it has no UI and does no blocking I/O
 * itself, so `Main`/`IO` belong to the caller, not here. (`Dispatchers.IO` is not even
 * available in `commonMain`.)
 */
public interface DispatcherProvider {
    public val default: CoroutineDispatcher
}

/** Production implementation backed by [Dispatchers.Default]. */
public object DefaultDispatcherProvider : DispatcherProvider {
    override val default: CoroutineDispatcher get() = Dispatchers.Default
}
