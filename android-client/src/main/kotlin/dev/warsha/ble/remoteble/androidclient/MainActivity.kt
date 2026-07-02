package dev.warsha.ble.remoteble.androidclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/**
 * Entry point for the RemoteBLE central client. The whole UI ([RemoteBleApp]) and the
 * orchestration logic ([RemoteBleController]) are shared `commonMain`; this Activity only wires a
 * platform [ViewModel] around the controller (so an in-flight scan/connection survives rotation,
 * as before) and hosts the Compose tree.
 */
class MainActivity : ComponentActivity() {

    // Retained across configuration changes so an in-flight scan/connection (and its agent
    // socket) survives rotation instead of being torn down and rebuilt.
    private val viewModel: AndroidRemoteBleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemoteBleApp(viewModel.controller)
        }
    }
}

/** Thin [ViewModel] wrapper so [RemoteBleController] stays platform-agnostic (see its doc). */
class AndroidRemoteBleViewModel : ViewModel() {
    val controller = RemoteBleController(viewModelScope)

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }
}
