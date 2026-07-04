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
    ComposeUIViewController { RemoteBleApp(controller) }
}
