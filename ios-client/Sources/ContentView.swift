import SwiftUI
import RemoteBleClient

struct ContentView: View {
    var body: some View {
        // Debug-only hardware probe for the SDK's LOCAL mode (open item 18), selected by a launch
        // argument so the normal app is untouched and the run needs no taps:
        //   xcrun devicectl device process launch --device $UDID --console \
        //     dev.warsha.remoteble.iosclient --local-mode-probe
        //
        // The control run overrides the Apple workaround back off and is expected to FAIL; a pass
        // must not be believed without it. Select it with the CONTROL environment variable:
        //   xcrun devicectl device process launch --device $UDID --console \
        //     --environment-variables '{"CONTROL":"1"}' \
        //     dev.warsha.remoteble.iosclient --local-mode-probe
        //
        // An env var rather than a second `--control` argument on purpose: devicectl silently
        // swallowed the extra flag, so the app ran the NON-control probe while the command line
        // said otherwise. That is the one failure this harness must not have — a control that
        // quietly isn't one looks exactly like a control that passed. The probe therefore prints
        // the arguments and the env var it actually saw, so the run states its own configuration
        // rather than relying on what was typed.
        if CommandLine.arguments.contains("--local-mode-probe") {
            LocalModeProbeView(
                forceUuidEquality: ProcessInfo.processInfo.environment["CONTROL"] != "1"
            )
        } else {
            ComposeView()
                .ignoresSafeArea()
        }
    }
}

/// Runs `runLocalModeProbe` and mirrors its lines to both the screen and the console, so the result
/// is readable from a `devicectl … --console` launch without anyone looking at the phone.
struct LocalModeProbeView: View {
    let forceUuidEquality: Bool
    @State private var lines: [String] = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                    Text(line).font(.system(.footnote, design: .monospaced))
                }
            }
            .padding()
        }
        .onAppear {
            // The run states its own configuration, so a control that silently isn't one is
            // visible in the log rather than indistinguishable from a control that passed.
            let env = ProcessInfo.processInfo.environment["CONTROL"] ?? "(unset)"
            print("[probe] argv=\(Array(CommandLine.arguments.dropFirst())) CONTROL=\(env) -> forceUuidEquality=\(forceUuidEquality)")
            LocalModeProbeKt.runLocalModeProbe(
                forceUuidEquality: forceUuidEquality,
                log: { line in
                    print("[probe] \(line)")
                    DispatchQueue.main.async { lines.append(line) }
                },
                onDone: { verdict in print("[probe] DONE \(verdict)") }
            )
        }
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
