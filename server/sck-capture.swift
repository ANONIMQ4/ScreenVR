import CoreMedia
import CoreVideo
import Foundation
import ScreenCaptureKit

@available(macOS 12.3, *)
final class ScreenCapture: NSObject, SCStreamOutput, SCStreamDelegate {
    private let width: Int
    private let height: Int
    private let fps: Int
    private let queueDepth: Int
    private let queue = DispatchQueue(label: "dev.screenvr.sck-capture")
    private var stream: SCStream?
    private var statsStarted = Date()
    private var framesInWindow = 0
    private var lastFrameTime: TimeInterval?
    private var frameIntervals: [Double] = []

    init(width: Int, height: Int, fps: Int, queueDepth: Int) {
        self.width = width
        self.height = height
        self.fps = fps
        self.queueDepth = queueDepth
    }

    func start() async throws {
        let content = try await SCShareableContent.current
        guard let display = content.displays.first else {
            throw CaptureError.noDisplay
        }

        let filter = SCContentFilter(display: display, excludingWindows: [])
        let configuration = SCStreamConfiguration()
        configuration.width = width
        configuration.height = height
        configuration.pixelFormat = kCVPixelFormatType_32BGRA
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(fps))
        configuration.queueDepth = queueDepth
        configuration.showsCursor = false

        let stream = SCStream(filter: filter, configuration: configuration, delegate: self)
        try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: queue)
        try await stream.startCapture()
        self.stream = stream
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen,
              sampleBuffer.isValid,
              let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            return
        }

        CVPixelBufferLockBaseAddress(imageBuffer, .readOnly)
        defer {
            CVPixelBufferUnlockBaseAddress(imageBuffer, .readOnly)
        }

        guard let baseAddress = CVPixelBufferGetBaseAddress(imageBuffer) else {
            return
        }

        let frameWidth = CVPixelBufferGetWidth(imageBuffer)
        let frameHeight = CVPixelBufferGetHeight(imageBuffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(imageBuffer)
        let outputRowBytes = width * 4

        if frameWidth == width && frameHeight == height && bytesPerRow == outputRowBytes {
            let data = Data(bytesNoCopy: baseAddress, count: outputRowBytes * height, deallocator: .none)
            FileHandle.standardOutput.write(data)
            recordFrame()
            return
        }

        var frame = Data(capacity: outputRowBytes * height)
        let copiedRowBytes = min(width, frameWidth) * 4
        let copiedRows = min(height, frameHeight)
        let padding = [UInt8](repeating: 0, count: max(0, outputRowBytes - copiedRowBytes))

        for row in 0..<copiedRows {
            let rowAddress = baseAddress.advanced(by: row * bytesPerRow)
            frame.append(rowAddress.assumingMemoryBound(to: UInt8.self), count: copiedRowBytes)
            if !padding.isEmpty {
                frame.append(contentsOf: padding)
            }
        }
        while frame.count < outputRowBytes * height {
            frame.append(contentsOf: [UInt8](repeating: 0, count: outputRowBytes))
        }
        FileHandle.standardOutput.write(frame)
        recordFrame()
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        FileHandle.standardError.write("ScreenCaptureKit stopped: \(error)\n".data(using: .utf8)!)
        exit(1)
    }

    private func recordFrame() {
        let now = Date().timeIntervalSinceReferenceDate
        if let lastFrameTime {
            frameIntervals.append(now - lastFrameTime)
        }
        lastFrameTime = now

        framesInWindow += 1
        let elapsed = Date().timeIntervalSince(statsStarted)
        guard elapsed >= 1.0 else {
            return
        }
        let measured = Double(framesInWindow) / elapsed
        let expectedInterval = 1.0 / Double(fps)
        let lateThreshold = expectedInterval * 1.5
        let intervals = frameIntervals.sorted()
        let intervalAverage = frameIntervals.isEmpty ? 0.0 : frameIntervals.reduce(0.0, +) / Double(frameIntervals.count)
        let intervalP95 = percentile(intervals, 0.95)
        let intervalMax = intervals.last ?? 0.0
        let lateFrames = frameIntervals.filter { $0 > lateThreshold }.count
        FileHandle.standardError.write(
            String(
                format: "sck fps %.1f interval_avg_ms %.2f interval_p95_ms %.2f interval_max_ms %.2f late %d\n",
                measured,
                intervalAverage * 1000.0,
                intervalP95 * 1000.0,
                intervalMax * 1000.0,
                lateFrames
            ).data(using: .utf8)!
        )
        framesInWindow = 0
        frameIntervals.removeAll(keepingCapacity: true)
        statsStarted = Date()
    }

    private func percentile(_ sortedValues: [Double], _ quantile: Double) -> Double {
        guard !sortedValues.isEmpty else {
            return 0.0
        }
        let clamped = min(max(quantile, 0.0), 1.0)
        let index = Int((Double(sortedValues.count - 1) * clamped).rounded())
        return sortedValues[index]
    }
}

enum CaptureError: Error {
    case noDisplay
}

func intArg(_ name: String, fallback: Int) -> Int {
    let args = CommandLine.arguments
    guard let index = args.firstIndex(of: name),
          index + 1 < args.count,
          let value = Int(args[index + 1]) else {
        return fallback
    }
    return value
}

if #available(macOS 12.3, *) {
    let capture = ScreenCapture(
        width: intArg("--width", fallback: 960),
        height: intArg("--height", fallback: 540),
        fps: intArg("--fps", fallback: 30),
        queueDepth: intArg("--queue-depth", fallback: 3)
    )

    Task {
        do {
            try await capture.start()
        } catch {
            FileHandle.standardError.write("ScreenCaptureKit failed: \(error)\n".data(using: .utf8)!)
            exit(1)
        }
    }

    RunLoop.main.run()
} else {
    FileHandle.standardError.write("ScreenCaptureKit requires macOS 12.3 or newer\n".data(using: .utf8)!)
    exit(1)
}
