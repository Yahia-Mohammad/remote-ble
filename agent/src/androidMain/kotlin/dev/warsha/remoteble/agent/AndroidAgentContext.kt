package dev.warsha.remoteble.agent

import android.content.Context

/**
 * Application [Context], set once by the launcher Activity before any Android `actual` that
 * needs one (`LanAddress.android.kt`, `TokenStore.android.kt`) is used. `expect`/`actual`
 * functions can't take platform-specific parameters, so a process-held reference is the seam —
 * same idea as `AgentService`'s static `runnerRef` handoff.
 */
internal var androidAgentContext: Context? = null

fun initAndroidAgentContext(context: Context) {
    androidAgentContext = context.applicationContext
}
