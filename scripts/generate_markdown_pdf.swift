import Foundation
import AppKit
import CoreText
import CoreGraphics

struct Style {
    let font: NSFont
    let color: NSColor
    let paragraphStyle: NSParagraphStyle
}

enum Block {
    case h1(String)
    case h2(String)
    case h3(String)
    case body(String)
    case blank
}

let arguments = CommandLine.arguments
guard arguments.count >= 3 else {
    fputs("Usage: swift generate_markdown_pdf.swift <input.md> <output.pdf>\n", stderr)
    exit(1)
}

let inputPath = arguments[1]
let outputPath = arguments[2]
let inputURL = URL(fileURLWithPath: inputPath)
let outputURL = URL(fileURLWithPath: outputPath)

let markdown = try String(contentsOf: inputURL, encoding: .utf8)

func paragraphStyle(alignment: NSTextAlignment = .left,
                    lineSpacing: CGFloat = 3,
                    paragraphSpacing: CGFloat = 10,
                    firstLineHeadIndent: CGFloat = 0,
                    headIndent: CGFloat = 0) -> NSParagraphStyle {
    let style = NSMutableParagraphStyle()
    style.alignment = alignment
    style.lineSpacing = lineSpacing
    style.paragraphSpacing = paragraphSpacing
    style.firstLineHeadIndent = firstLineHeadIndent
    style.headIndent = headIndent
    style.lineBreakMode = .byWordWrapping
    return style
}

func preferredFont(_ name: String, size: CGFloat, weight: NSFont.Weight = .regular) -> NSFont {
    if let font = NSFont(name: name, size: size) {
        return font
    }
    return NSFont.systemFont(ofSize: size, weight: weight)
}

let titleFont = preferredFont("Hiragino Sans GB W6", size: 24, weight: .bold)
let h2Font = preferredFont("Hiragino Sans GB W6", size: 17, weight: .semibold)
let h3Font = preferredFont("Hiragino Sans GB W6", size: 13, weight: .semibold)
let bodyFont = preferredFont("Hiragino Sans GB W3", size: 10.5)
let monoFont = preferredFont("Menlo", size: 10.2)

let titleStyle = Style(font: titleFont,
                       color: NSColor.black,
                       paragraphStyle: paragraphStyle(lineSpacing: 4, paragraphSpacing: 16))

let h2Style = Style(font: h2Font,
                    color: NSColor.black,
                    paragraphStyle: paragraphStyle(lineSpacing: 3, paragraphSpacing: 12))

let h3Style = Style(font: h3Font,
                    color: NSColor.black,
                    paragraphStyle: paragraphStyle(lineSpacing: 2, paragraphSpacing: 6))

let bodyStyle = Style(font: bodyFont,
                      color: NSColor(calibratedWhite: 0.12, alpha: 1),
                      paragraphStyle: paragraphStyle(lineSpacing: 3, paragraphSpacing: 9))

let bulletStyle = Style(font: bodyFont,
                        color: NSColor(calibratedWhite: 0.12, alpha: 1),
                        paragraphStyle: paragraphStyle(lineSpacing: 3,
                                                      paragraphSpacing: 8,
                                                      firstLineHeadIndent: 0,
                                                      headIndent: 14))

let codeStyle = Style(font: monoFont,
                      color: NSColor(calibratedWhite: 0.12, alpha: 1),
                      paragraphStyle: paragraphStyle(lineSpacing: 2, paragraphSpacing: 8))

func parseBlocks(from text: String) -> [Block] {
    var blocks: [Block] = []
    for rawLine in text.components(separatedBy: .newlines) {
        let line = rawLine.trimmingCharacters(in: .whitespaces)
        if line.isEmpty {
            blocks.append(.blank)
        } else if line.hasPrefix("# ") {
            blocks.append(.h1(String(line.dropFirst(2))))
        } else if line.hasPrefix("## ") {
            blocks.append(.h2(String(line.dropFirst(3))))
        } else if line.hasPrefix("### ") {
            blocks.append(.h3(String(line.dropFirst(4))))
        } else {
            blocks.append(.body(line))
        }
    }
    return blocks
}

func styleForBody(_ text: String) -> Style {
    if text.hasPrefix("- ") {
        return bulletStyle
    }
    if text.contains("`") {
        return codeStyle
    }
    return bodyStyle
}

func append(_ text: String,
            style: Style,
            into target: NSMutableAttributedString) {
    let attributes: [NSAttributedString.Key: Any] = [
        .font: style.font,
        .foregroundColor: style.color,
        .paragraphStyle: style.paragraphStyle
    ]
    target.append(NSAttributedString(string: text + "\n", attributes: attributes))
}

let attributed = NSMutableAttributedString()
for block in parseBlocks(from: markdown) {
    switch block {
    case .h1(let text):
        append(text, style: titleStyle, into: attributed)
    case .h2(let text):
        append(text, style: h2Style, into: attributed)
    case .h3(let text):
        append(text, style: h3Style, into: attributed)
    case .body(let text):
        append(text, style: styleForBody(text), into: attributed)
    case .blank:
        append(" ", style: bodyStyle, into: attributed)
    }
}

let pageWidth: CGFloat = 595
let pageHeight: CGFloat = 842
let topMargin: CGFloat = 70
let bottomMargin: CGFloat = 52
let horizontalMargin: CGFloat = 52
let headerGap: CGFloat = 22
let footerGap: CGFloat = 20
let bodyRect = CGRect(x: horizontalMargin,
                      y: bottomMargin + footerGap,
                      width: pageWidth - horizontalMargin * 2,
                      height: pageHeight - topMargin - bottomMargin - headerGap - footerGap)

let framesetter = CTFramesetterCreateWithAttributedString(attributed as CFAttributedString)
var currentRange = CFRange(location: 0, length: 0)
var pageCount = 0

guard let consumer = CGDataConsumer(url: outputURL as CFURL),
      let context = CGContext(consumer: consumer, mediaBox: nil, nil) else {
    fputs("Failed to create PDF context.\n", stderr)
    exit(1)
}

let headerAttributes: [NSAttributedString.Key: Any] = [
    .font: preferredFont("Hiragino Sans GB W3", size: 9.5),
    .foregroundColor: NSColor(calibratedWhite: 0.35, alpha: 1)
]

let footerAttributes: [NSAttributedString.Key: Any] = [
    .font: preferredFont("Hiragino Sans GB W3", size: 9),
    .foregroundColor: NSColor(calibratedWhite: 0.45, alpha: 1)
]

func renderPageImage(pageNumber: Int, range: CFRange) -> (CGImage, CFRange)? {
    let scale: CGFloat = 2.0
    let pixelWidth = Int(pageWidth * scale)
    let pixelHeight = Int(pageHeight * scale)
    guard let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
          let bitmap = CGContext(data: nil,
                                 width: pixelWidth,
                                 height: pixelHeight,
                                 bitsPerComponent: 8,
                                 bytesPerRow: 0,
                                 space: colorSpace,
                                 bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
        return nil
    }

    bitmap.scaleBy(x: scale, y: scale)
    bitmap.setFillColor(NSColor.white.cgColor)
    bitmap.fill(CGRect(x: 0, y: 0, width: pageWidth, height: pageHeight))

    let nsContext = NSGraphicsContext(cgContext: bitmap, flipped: false)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = nsContext

    let header = NSAttributedString(string: "Kafka 面试题整理", attributes: headerAttributes)
    header.draw(in: CGRect(x: horizontalMargin,
                           y: pageHeight - topMargin + 6,
                           width: 220,
                           height: 18))

    let footer = NSAttributedString(string: "第 \(pageNumber) 页", attributes: footerAttributes)
    footer.draw(in: CGRect(x: horizontalMargin,
                           y: 18,
                           width: pageWidth - horizontalMargin * 2,
                           height: 16))

    NSGraphicsContext.restoreGraphicsState()

    let path = CGMutablePath()
    path.addRect(bodyRect)
    let frame = CTFramesetterCreateFrame(framesetter, range, path, nil)
    CTFrameDraw(frame, bitmap)
    let visible = CTFrameGetVisibleStringRange(frame)

    guard let image = bitmap.makeImage() else {
        return nil
    }
    return (image, visible)
}

while currentRange.location < attributed.length {
    pageCount += 1
    let mediaBox = CGRect(x: 0, y: 0, width: pageWidth, height: pageHeight)
    context.beginPDFPage([kCGPDFContextMediaBox as String: mediaBox] as CFDictionary)

    guard let (pageImage, visible) = renderPageImage(pageNumber: pageCount, range: currentRange) else {
        fputs("Failed to render page image.\n", stderr)
        exit(1)
    }
    context.draw(pageImage, in: mediaBox)
    currentRange.location += visible.length
    currentRange.length = 0

    context.endPDFPage()
}

context.closePDF()
print("Generated \(outputPath) with \(pageCount) pages")
