import AppKit
import Foundation

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var window: NSWindow!
    private let statusLabel = NSTextField(labelWithString: "Idle")
    private let deviceLabel = NSTextField(labelWithString: "Device: unknown")
    private let widthField = NSTextField(string: "1170")
    private let heightField = NSTextField(string: "1080")
    private let fpsField = NSTextField(string: "30")
    private let bitrateField = NSTextField(string: "5000")
    private let fitPopup = NSPopUpButton()
    private var serverProcess: Process?
    private var logPipe: Pipe?

    private var scriptURL: URL {
        if let resourceURL = Bundle.main.resourceURL {
            let bundled = resourceURL.appendingPathComponent("mac-screen-server.py")
            if FileManager.default.fileExists(atPath: bundled.path) {
                return bundled
            }
        }
        let projectDir = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? FileManager.default.currentDirectoryPath)
        return projectDir.appendingPathComponent("server/mac-screen-server.py")
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        buildWindow()
        refreshDevice()
        statusLabel.stringValue = "Starting server..."
        startServer()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }

    func applicationWillTerminate(_ notification: Notification) {
        stopServer()
    }

    private func buildWindow() {
        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 520, height: 300),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        window.title = "ScreenVR Launcher"
        window.center()

        let root = NSStackView()
        root.orientation = .vertical
        root.spacing = 14
        root.edgeInsets = NSEdgeInsets(top: 18, left: 18, bottom: 18, right: 18)
        root.translatesAutoresizingMaskIntoConstraints = false

        let title = NSTextField(labelWithString: "Android Screen VR")
        title.font = .boldSystemFont(ofSize: 20)
        root.addArrangedSubview(title)

        deviceLabel.textColor = .secondaryLabelColor
        root.addArrangedSubview(deviceLabel)

        let grid = NSGridView(views: [
            [label("Width"), widthField, label("Height"), heightField],
            [label("FPS"), fpsField, label("Kbps"), bitrateField],
            [label("Fit"), fitPopup, NSView(), NSView()]
        ])
        grid.column(at: 0).xPlacement = .trailing
        grid.column(at: 2).xPlacement = .trailing
        grid.rowSpacing = 8
        grid.columnSpacing = 10
        root.addArrangedSubview(grid)

        fitPopup.addItems(withTitles: ["contain", "cover"])
        [widthField, heightField, fpsField, bitrateField].forEach { field in
            field.alignment = .right
            field.widthAnchor.constraint(equalToConstant: 90).isActive = true
        }

        let presets = NSStackView()
        presets.orientation = .horizontal
        presets.spacing = 8
        presets.addArrangedSubview(button("Native", #selector(setNative)))
        presets.addArrangedSubview(button("Balanced", #selector(setBalanced)))
        presets.addArrangedSubview(button("Fast", #selector(setFast)))
        root.addArrangedSubview(presets)

        let actions = NSStackView()
        actions.orientation = .horizontal
        actions.spacing = 10
        actions.addArrangedSubview(button("Refresh Device", #selector(refreshDeviceAction)))
        actions.addArrangedSubview(button("Start", #selector(startAction)))
        actions.addArrangedSubview(button("Apply", #selector(applyAction)))
        actions.addArrangedSubview(button("Stop", #selector(stopAction)))
        root.addArrangedSubview(actions)

        statusLabel.lineBreakMode = .byTruncatingMiddle
        statusLabel.textColor = .secondaryLabelColor
        root.addArrangedSubview(statusLabel)

        window.contentView = NSView()
        window.contentView?.addSubview(root)
        NSLayoutConstraint.activate([
            root.leadingAnchor.constraint(equalTo: window.contentView!.leadingAnchor),
            root.trailingAnchor.constraint(equalTo: window.contentView!.trailingAnchor),
            root.topAnchor.constraint(equalTo: window.contentView!.topAnchor),
            root.bottomAnchor.constraint(equalTo: window.contentView!.bottomAnchor)
        ])
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    private func label(_ text: String) -> NSTextField {
        let label = NSTextField(labelWithString: text)
        label.textColor = .secondaryLabelColor
        return label
    }

    private func button(_ title: String, _ action: Selector) -> NSButton {
        let button = NSButton(title: title, target: self, action: action)
        button.bezelStyle = .rounded
        return button
    }

    @objc private func setNative() {
        widthField.stringValue = "1170"
        heightField.stringValue = "1080"
        fpsField.stringValue = "30"
        bitrateField.stringValue = "5000"
        fitPopup.selectItem(withTitle: "contain")
    }

    @objc private func setBalanced() {
        widthField.stringValue = "960"
        heightField.stringValue = "540"
        fpsField.stringValue = "30"
        bitrateField.stringValue = "2500"
        fitPopup.selectItem(withTitle: "contain")
    }

    @objc private func setFast() {
        widthField.stringValue = "800"
        heightField.stringValue = "450"
        fpsField.stringValue = "30"
        bitrateField.stringValue = "1600"
        fitPopup.selectItem(withTitle: "contain")
    }

    @objc private func refreshDeviceAction() {
        refreshDevice()
    }

    @objc private func startAction() {
        startServer()
    }

    @objc private func applyAction() {
        applyConfig()
    }

    @objc private func stopAction() {
        stopServer()
    }

    private func refreshDevice() {
        let output = shell("/opt/homebrew/bin/adb", ["devices", "-l"])
        let deviceLine = output.split(separator: "\n").dropFirst().first(where: { $0.contains("device") })
        deviceLabel.stringValue = "Device: \(deviceLine.map(String.init) ?? "not connected")"
    }

    private func startServer() {
        stopServer()
        _ = shell("/opt/homebrew/bin/adb", ["reverse", "--remove-all"])
        _ = shell("/opt/homebrew/bin/adb", ["reverse", "tcp:8094", "tcp:8094"])
        _ = shell("/opt/homebrew/bin/adb", ["reverse", "tcp:8095", "tcp:8095"])

        let process = Process()
        process.currentDirectoryURL = scriptURL.deletingLastPathComponent()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        process.environment = [
            "PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
        ]
        process.arguments = [
            "python3",
            scriptURL.path,
            "--fps", fpsField.stringValue,
            "--size", "\(widthField.stringValue)x\(heightField.stringValue)",
            "--bitrate", "\(bitrateField.stringValue)k",
            "--fit", fitPopup.titleOfSelectedItem ?? "contain"
        ]

        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        logPipe = pipe
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            guard !data.isEmpty, let text = String(data: data, encoding: .utf8) else { return }
            DispatchQueue.main.async {
                self?.statusLabel.stringValue = text.trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }

        do {
            try process.run()
            serverProcess = process
            statusLabel.stringValue = "Server started"
            applyConfig()
        } catch {
            statusLabel.stringValue = "Start failed: \(error.localizedDescription)"
        }
    }

    private func applyConfig() {
        let width = intValue(widthField.stringValue, fallback: 1170)
        let height = intValue(heightField.stringValue, fallback: 1080)
        let fps = intValue(fpsField.stringValue, fallback: 30)
        let bitrate = intValue(bitrateField.stringValue, fallback: 5000)
        let fit = fitPopup.titleOfSelectedItem ?? "contain"
        let json = #"{"width":\#(width),"height":\#(height),"fps":\#(fps),"bitrate":\#(bitrate),"fit":"\#(fit)"}"#
        _ = shell("/usr/bin/curl", [
            "--max-time", "2",
            "-s",
            "-X", "POST",
            "http://127.0.0.1:8095/config",
            "-H", "Content-Type: application/json",
            "-d", json
        ])
        statusLabel.stringValue = "Applied \(width)x\(height) \(fps)fps \(bitrate)kbps \(fit)"
    }

    private func stopServer() {
        logPipe?.fileHandleForReading.readabilityHandler = nil
        serverProcess?.terminate()
        serverProcess = nil
        logPipe = nil
        _ = shell("/usr/bin/pkill", ["-f", "mac-screen-server.py"])
        killMatchingProcesses(needles: ["ffmpeg", "-f h264", "pipe:1"])
        statusLabel.stringValue = "Stopped"
    }

    private func killMatchingProcesses(needles: [String]) {
        let output = shell("/bin/ps", ["-axo", "pid=,command="])
        for line in output.split(separator: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard let separator = trimmed.firstIndex(of: " ") else { continue }
            let pidText = String(trimmed[..<separator])
            let command = String(trimmed[separator...])
            guard needles.allSatisfy({ command.contains($0) }), Int(pidText) != nil else { continue }
            _ = shell("/bin/kill", ["-9", pidText])
        }
    }

    private func intValue(_ value: String, fallback: Int) -> Int {
        Int(value.trimmingCharacters(in: .whitespacesAndNewlines)) ?? fallback
    }

    @discardableResult
    private func shell(_ executable: String, _ arguments: [String]) -> String {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        do {
            try process.run()
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            process.waitUntilExit()
            return String(data: data, encoding: .utf8) ?? ""
        } catch {
            return error.localizedDescription
        }
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
