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

private final class AgentMenuController: NSObject {
    private let dashboardURL: URL
    private let statusItem: NSStatusItem
    private let statusLine: NSMenuItem
    private let logItems: [NSMenuItem]
    // Dedicated session with a short per-request timeout: poll() fires every 2s, so the default
    // 60s request timeout would let requests pile up against a hung/unreachable dashboard. A tight
    // timeout just surfaces as the "unreachable" (🟡) state until the next tick.
    private let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 1.5
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }()

    init(port: String) {
        dashboardURL = URL(string: "http://127.0.0.1:\(port)/")!
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
        let url = dashboardURL.appendingPathComponent("api/state")
        session.dataTask(with: url) { [weak self] data, _, error in
            DispatchQueue.main.async { self?.handlePollResult(data: data, error: error) }
        }.resume()
    }

    private func handlePollResult(data: Data?, error: Error?) {
        guard error == nil, let data,
              let state = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            statusItem.button?.title = "🟡 RemoteBLE"
            statusLine.title = "Agent starting or unreachable…"
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
public func agent_menu_run(_ portPtr: UnsafePointer<CChar>) {
    let port = String(cString: portPtr)
    let app = NSApplication.shared
    app.setActivationPolicy(.accessory) // no Dock icon

    let c = AgentMenuController(port: port)
    controller = c
    c.poll()
    Timer.scheduledTimer(timeInterval: 2.0, target: c, selector: #selector(AgentMenuController.poll),
                          userInfo: nil, repeats: true)

    app.run()
}
