package dev.warsha.remoteble.androidclient

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Entry point the `ios-client` launcher shell calls into
 * (`MainViewControllerKt.MainViewController()` from Swift). Builds its own [CoroutineScope] —
 * there's no platform lifecycle owner to borrow one from the way Android's `viewModelScope` does
 * — and hosts [RemoteBleApp] over it via a fresh [RemoteBleController].
 */
fun MainViewController() = run {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val controller = RemoteBleController(scope)
    current = controller
    pendingLink?.let { controller.offerPairing(it) }
    pendingLink = null
    ComposeUIViewController { RemoteBleApp(controller) }
}

/**
 * A `remoteble://` pairing link the app was opened with (SwiftUI's `onOpenURL`), held for the user's
 * confirmation. On a cold launch the link can arrive before the UI exists; it waits for it.
 */
fun offerPairing(link: String) {
    current?.offerPairing(link) ?: run { pendingLink = link }
}

// Main thread only, like everything SwiftUI calls into.
private var current: RemoteBleController? = null
private var pendingLink: String? = null
