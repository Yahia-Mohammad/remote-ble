import RemoteBleClient
import SwiftUI

/// Hosts the shared Compose Multiplatform UI (`:client-ui`'s commonMain `RemoteBleApp`) inside
/// SwiftUI. This file has no logic of its own — everything it shows comes from the Kotlin
/// framework's `MainViewController()` factory.
struct ComposeView: UIViewControllerRepresentable {
    func updateUIViewController(_ uiViewController: UIViewControllerType, context: Context) {
    }

    func makeUIViewController(context: Context) -> some UIViewController {
        MainViewControllerKt.MainViewController()
    }
}
