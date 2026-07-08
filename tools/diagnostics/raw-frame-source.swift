import Darwin
import Foundation

struct Config {
    var width = 900
    var height = 600
    var fps = 60
    var duration = 8
    var barWidth = 48
    var motion = 0
    var ring = 8
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

func sleepUntil(_ targetNs: UInt64) {
    while true {
        let now = DispatchTime.now().uptimeNanoseconds
        if now >= targetNs {
            return
        }
        let remaining = targetNs - now
        var request = timespec(
            tv_sec: time_t(remaining / 1_000_000_000),
            tv_nsec: Int(remaining % 1_000_000_000)
        )
        nanosleep(&request, nil)
    }
}

func log(_ message: String) {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
}

let config = Config(
    width: max(2, intArg("--width", fallback: 900)),
    height: max(2, intArg("--height", fallback: 600)),
    fps: max(1, intArg("--fps", fallback: 60)),
    duration: max(1, intArg("--duration", fallback: 8)),
    barWidth: max(2, intArg("--bar-width", fallback: 48)),
    motion: intArg("--motion", fallback: 0),
    ring: max(1, intArg("--ring", fallback: 8))
)

let frameBytes = config.width * config.height * 4
var frame = Data(count: frameBytes)

frame.withUnsafeMutableBytes { raw in
    guard let pixels = raw.bindMemory(to: UInt8.self).baseAddress else {
        return
    }
    for y in 0..<config.height {
        for x in 0..<config.width {
            let offset = (y * config.width + x) * 4
            let checker = ((x / 48) + (y / 48)) % 2 == 0
            pixels[offset + 0] = checker ? 28 : 8
            pixels[offset + 1] = checker ? 22 : 10
            pixels[offset + 2] = checker ? 18 : 14
            pixels[offset + 3] = 255
        }
    }
}

let frameIntervalNs = UInt64(1_000_000_000 / config.fps)
let totalFrames = config.duration * config.fps
let startedNs = DispatchTime.now().uptimeNanoseconds
var nextFrameNs = startedNs
var statsStartedNs = startedNs
var framesInWindow = 0
var lateFrames = 0

func paintBar(on data: inout Data, x barX: Int, color: (UInt8, UInt8, UInt8)) {
    let width = min(config.barWidth, config.width)
    data.withUnsafeMutableBytes { raw in
        guard let pixels = raw.bindMemory(to: UInt8.self).baseAddress else {
            return
        }
        for y in 0..<config.height {
            for dx in 0..<width {
                let x = (barX + dx) % config.width
                let offset = (y * config.width + x) * 4
                pixels[offset + 0] = color.0
                pixels[offset + 1] = color.1
                pixels[offset + 2] = color.2
                pixels[offset + 3] = 255
            }
        }
    }
}

var ringFrames: [Data] = []
if config.motion != 0 {
    for index in 0..<config.ring {
        var next = frame
        let barX = (index * max(1, config.width / config.ring)) % config.width
        paintBar(on: &next, x: barX, color: (255, 230, 40))
        ringFrames.append(next)
    }
}

func writeFrame(_ frame: Data) -> Bool {
    return frame.withUnsafeBytes { raw in
        guard let base = raw.bindMemory(to: UInt8.self).baseAddress else {
            return false
        }
        var written = 0
        while written < frame.count {
            let result = Darwin.write(STDOUT_FILENO, base.advanced(by: written), frame.count - written)
            if result <= 0 {
                return false
            }
            written += result
        }
        return true
    }
}

for frameIndex in 0..<totalFrames {
    sleepUntil(nextFrameNs)
    let now = DispatchTime.now().uptimeNanoseconds
    if now > nextFrameNs + frameIntervalNs {
        lateFrames += 1
    }

    let outputFrame = config.motion != 0 ? ringFrames[frameIndex % ringFrames.count] : frame
    if !writeFrame(outputFrame) {
        log("raw-source stopped: write failed")
        exit(1)
    }

    framesInWindow += 1
    let elapsedNs = now - statsStartedNs
    if elapsedNs >= 1_000_000_000 {
        let fps = Double(framesInWindow) * 1_000_000_000.0 / Double(elapsedNs)
        let totalElapsed = Double(now - startedNs) / 1_000_000_000.0
        log(String(format: "source fps %.1f late %d elapsed %.2f", fps, lateFrames, totalElapsed))
        framesInWindow = 0
        statsStartedNs = now
    }

    nextFrameNs += frameIntervalNs
}

let endedNs = DispatchTime.now().uptimeNanoseconds
let totalElapsed = Double(endedNs - startedNs) / 1_000_000_000.0
let measured = Double(totalFrames) / max(totalElapsed, 0.001)
log(String(format: "source done fps %.1f late %d elapsed %.2f", measured, lateFrames, totalElapsed))
