import RemoteBleAgent
import SwiftUI

/// Hosts the shared Compose Multiplatform UI (`:agent`'s `AgentApp`) inside SwiftUI. This file
/// has no UI logic of its own — everything it shows comes from the Kotlin framework's
/// `IosAgentEntry()` factory. The `Coordinator` exists solely to tie the `IosAgentSession`
/// (runner + observing scope) it creates to this view's lifetime: `deinit` fires when SwiftUI
/// tears the hosting view down, disposing the session instead of leaking it.
struct ComposeView: UIViewControllerRepresentable {
    final class Coordinator {
        var session: IosAgentSession?

        deinit {
            session?.dispose()
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func updateUIViewController(_ uiViewController: UIViewControllerType, context: Context) {
    }

    func makeUIViewController(context: Context) -> some UIViewController {
        let session = IosAgentEntryKt.IosAgentEntry()
        context.coordinator.session = session
        return session.makeViewController()
    }
}
