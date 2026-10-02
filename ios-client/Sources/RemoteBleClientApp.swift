import RemoteBleClient
import SwiftUI

@main
struct RemoteBleClientApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                // An agent's pairing link, as the Camera app opens it from a QR code. The shared UI
                // asks the user before using it.
                .onOpenURL { url in
                    MainViewControllerKt.offerPairing(link: url.absoluteString)
                }
        }
    }
}
