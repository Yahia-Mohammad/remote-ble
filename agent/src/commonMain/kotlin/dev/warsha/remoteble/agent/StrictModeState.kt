package dev.warsha.remoteble.agent

import kotlinx.atomicfu.atomic

/**
 * Agent-wide, live-toggleable switch for identifier **strict mode**. When on, the agent stops
 * translating device handles (see [HandleTranslator]) and passes real handles through untranslated,
 * so a cross-platform format mismatch surfaces loudly on the client as an unavailable `.identifier`
 * — useful in dev/CI to catch handle-format assumptions.
 *
 * A single instance is shared across every connection's [BleAgent] (like the peripheral registry),
 * and flipped at runtime from the dashboard (`POST /api/strict`). The flag is read live on each
 * forward translation, so a toggle takes effect on the next emitted handle.
 */
class StrictModeState(initial: Boolean = false) {
    private val flag = atomic(initial)

    var enabled: Boolean
        get() = flag.value
        set(value) {
            flag.value = value
        }
}
