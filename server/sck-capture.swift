import CoreMedia
import CoreVideo
import Foundation
import ScreenCaptureKit

@available(macOS 12.3, *)
final class ScreenCapture: NSObject, SCStreamOutput, SCStreamDelegate {
    private let width: Int
    private let height: Int
    private let fps: Int
    private let queue = DispatchQueue(label: "dev.screenvr.sck-capture")
    private var stream: SCStream?
    private var statsStarted = Date()
    private var framesInWindow = 0

    init(width: Int, height: Int, fps: Int) {
        self.width = width
        self.height = height
        self.fps = fps
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
        configuration.queueDepth = 2
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
        framesInWindow += 1
        let elapsed = Date().timeIntervalSince(statsStarted)
        guard elapsed >= 1.0 else {
            return
        }
        let measured = Double(framesInWindow) / elapsed
        FileHandle.standardError.write(String(format: "sck fps %.1f\n", measured).data(using: .utf8)!)
        framesInWindow = 0
        statsStarted = Date()
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
        fps: intArg("--fps", fallback: 30)
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
