package dev.warsha.ble.remoteble.agent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injection seam for the dispatcher backing the agent's lifetime scope.
 *
 * Exists so tests can substitute a deterministic dispatcher (e.g. a `TestDispatcher`)
 * for [Dispatchers.Default]. Only [default] is modelled because it is the only
 * dispatcher the agent schedules its own work on.
 */
public interface DispatcherProvider {
    public val default: CoroutineDispatcher
}

/** Production implementation backed by [Dispatchers.Default]. */
public object DefaultDispatcherProvider : DispatcherProvider {
    override val default: CoroutineDispatcher get() = Dispatchers.Default
}
