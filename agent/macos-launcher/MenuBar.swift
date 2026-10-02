// The agent's menu bar status item: a small always-visible indicator (green when the
// agent's own dashboard answers, yellow otherwise) with a dropdown showing client/device
// counts and the most recent activity-log lines. Polls the agent's existing
// `/api/state` endpoint (see Dashboard.kt / AgentMonitor.kt) rather than talking to the
// JVM directly, so there's no new IPC between this launcher and the agent.
//
// `agent_menu_run` is the only symbol the C side (launcher.c) calls; it's exported with
// a flat C name via `@_cdecl` and invoked from `main()` on the process's main thread,
// blocking here for the life of the app.
import Cocoa

// The agent's certificate is self-signed, so no system trust evaluation passes it. Accepted for the
// loopback poll only, which leaves it exactly as exposed as the plain `http://` poll: either way the
// operator token goes to whatever holds the port on this machine. The dashboard opened in the
// browser still shows its own warning, which the operator checks against the certificate SHA-256
// the agent logs.
private final class LoopbackAgentTrust: NSObject, URLSessionDelegate {
    private let host: String

    init(host: String) {
        self.host = host
    }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        let space = challenge.protectionSpace
        guard space.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              space.host == host, let trust = space.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }
}

private final class AgentMenuController: NSObject {
    private let dashboardURL: URL
    private let statusItem: NSStatusItem
    private let statusLine: NSMenuItem
    private let logItems: [NSMenuItem]
    // Dedicated session with a short per-request timeout: poll() fires every 2s, so the default
    // 60s request timeout would let requests pile up against a hung/unreachable dashboard. A tight
    // timeout just surfaces as the "unreachable" (🟡) state until the next tick.
    private let session: URLSession
    // Every dashboard route needs the operator credential, and without one the agent serves no
    // dashboard at all. `open --env` forwards it from run-agent.sh's environment.
    private let operatorToken = ProcessInfo.processInfo.environment["REMOTE_BLE_OPERATOR_TOKEN"]
        .flatMap { $0.isEmpty ? nil : $0 }

    init(port: String, tls: Bool) {
        // With --tls the port serves only `https://`, behind the agent's self-signed certificate.
        dashboardURL = URL(string: "\(tls ? "https" : "http")://127.0.0.1:\(port)/")!
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 1.5
        config.waitsForConnectivity = false
        session = URLSession(configuration: config, delegate: LoopbackAgentTrust(host: "127.0.0.1"), delegateQueue: nil)
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusLine = NSMenuItem(title: "Starting…", action: nil, keyEquivalent: "")
        logItems = (0..<5).map { _ in NSMenuItem(title: "", action: nil, keyEquivalent: "") }
        super.init()

        statusItem.button?.title = "🟡 RemoteBLE"
        statusLine.isEnabled = false

        let menu = NSMenu()
        menu.addItem(statusLine)
        menu.addItem(.separator())

        let logHeader = NSMenuItem(title: "Recent activity", action: nil, keyEquivalent: "")
        logHeader.isEnabled = false
        menu.addItem(logHeader)
        for item in logItems {
            item.isEnabled = false
            item.isHidden = true
            menu.addItem(item)
        }
        menu.addItem(.separator())

        let openItem = NSMenuItem(title: "Open Dashboard", action: #selector(openDashboard), keyEquivalent: "")
        openItem.target = self
        menu.addItem(openItem)

        let quitItem = NSMenuItem(title: "Quit Agent", action: #selector(quitAgent), keyEquivalent: "")
        quitItem.target = self
        menu.addItem(quitItem)

        statusItem.menu = menu
    }

    @objc func openDashboard() {
        NSWorkspace.shared.open(dashboardURL)
    }

    @objc func quitAgent() {
        statusLine.title = "Stopping…"
        // Same signal the shell wrapper's `pkill` sends today: the JVM's default handler
        // runs Main.kt's shutdown hook (graceful peripheral disconnect) then halts the
        // whole process — including this app — via System.exit()'s native halt.
        kill(getpid(), SIGTERM)
    }

    @objc func poll() {
        var request = URLRequest(url: dashboardURL.appendingPathComponent("api/state"))
        if let operatorToken {
            request.setValue("Bearer \(operatorToken)", forHTTPHeaderField: "Authorization")
        }
        session.dataTask(with: request) { [weak self] data, response, error in
            let status = (response as? HTTPURLResponse)?.statusCode
            DispatchQueue.main.async { self?.handlePollResult(data: data, status: status, error: error) }
        }.resume()
    }

    private func handlePollResult(data: Data?, status: Int?, error: Error?) {
        guard error == nil, let status else {
            statusItem.button?.title = "🟡 RemoteBLE"
            statusLine.title = "Agent starting or unreachable…"
            return
        }
        // Any answer means the agent is up; only a 200 carries the status to show.
        guard status == 200, let data,
              let state = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            statusItem.button?.title = "🟢 RemoteBLE"
            statusLine.title = status == 404
                ? "Running — set REMOTE_BLE_OPERATOR_TOKEN to see its status"
                : "Running — dashboard answered \(status)"
            for item in logItems { item.isHidden = true }
            return
        }
        let clientCount = (state["clients"] as? [Any])?.count ?? 0
        let leaseCount = (state["leases"] as? [Any])?.count ?? 0
        statusItem.button?.title = "🟢 RemoteBLE"
        statusLine.title = "Running — \(clientCount) client(s), \(leaseCount) device(s)"

        let logs = (state["logs"] as? [[String: Any]]) ?? []
        for (i, item) in logItems.enumerated() {
            let idx = logs.count - 1 - i // newest first
            if idx >= 0, let message = logs[idx]["message"] as? String {
                item.title = "  \(message)"
                item.isHidden = false
            } else {
                item.isHidden = true
            }
        }
    }
}

// Keeps the controller (and its NSStatusItem) alive for the process lifetime.
private var controller: AgentMenuController?

@_cdecl("agent_menu_run")
public func agent_menu_run(_ portPtr: UnsafePointer<CChar>, _ tls: Int32) {
    let port = String(cString: portPtr)
    let app = NSApplication.shared
    app.setActivationPolicy(.accessory) // no Dock icon

    let c = AgentMenuController(port: port, tls: tls != 0)
    controller = c
    c.poll()
    Timer.scheduledTimer(timeInterval: 2.0, target: c, selector: #selector(AgentMenuController.poll),
                          userInfo: nil, repeats: true)

    app.run()
}
