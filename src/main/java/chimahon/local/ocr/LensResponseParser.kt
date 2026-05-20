package chimahon.local.ocr

import chimahon.ocr.EngineLine
import chimahon.ocr.NormalizedBBox
import chimahon.ocr.OcrLanguage
import chimahon.ocr.WritingDirection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ParsedLensResponse(
    val lines: List<EngineLine>,
    val boxSource: String,
    val coordinateWidth: Int,
    val coordinateHeight: Int,
)

object LensResponseParser {

    private data class Field(
        val number: Int,
        val wireType: Int,
        val text: String? = null,
        val numberValue: Double? = null,
        val child: Message? = null,
    )

    private class Message(
        val start: Int,
        val end: Int,
        val parent: Message?,
        val depth: Int,
    ) {
        val fields = mutableListOf<Field>()
    }

    private data class TextHit(
        val text: String,
        val owner: Message,
        val ordinal: Int,
    )

    fun parse(data: ByteArray, imageWidth: Int = 0, imageHeight: Int = 0): ParsedLensResponse {
        val root = parseMessage(data, 0, data.size, null, 0)
        val coordinateWidth = root?.let { findCoordinateWidth(it) } ?: imageWidth
        val coordinateHeight = if (coordinateWidth > 0 && imageWidth > 0 && imageHeight > 0) {
            (coordinateWidth.toDouble() * imageHeight.toDouble() / imageWidth.toDouble()).toInt()
        } else {
            imageHeight
        }
        val hits = root?.let { collectTextHits(it) }.orEmpty()
        val filtered = chooseOcrText(hits)
        if (filtered.isEmpty()) {
            return ParsedLensResponse(emptyList(), "none", coordinateWidth, coordinateHeight)
        }

        val withNativeBoxes = filtered.mapNotNull { hit ->
            findBox(hit.owner, coordinateWidth, coordinateHeight)?.let { box ->
                EngineLine(
                    text = hit.text,
                    bbox = box,
                    writingDirection = inferDirection(box),
                    language = OcrLanguage.JAPANESE,
                )
            }
        }

        val useNativeBoxes = withNativeBoxes.size >= max(2, filtered.size / 3)
        val lines = if (useNativeBoxes) {
            withNativeBoxes
        } else {
            fallbackLayout(filtered.map { it.text })
        }
        val source = if (useNativeBoxes) "native" else "heuristic"
        return ParsedLensResponse(lines, source, coordinateWidth, coordinateHeight)
    }

    private fun findCoordinateWidth(root: Message): Int? {
        var best: Int? = null

        fun visit(message: Message) {
            val repeatedLineChildren = message.fields.count { it.number == 7 && it.child != null }
            val width = message.fields
                .firstOrNull { it.number == 1 && it.wireType == 0 && it.numberValue != null }
                ?.numberValue
                ?.toInt()
            if (repeatedLineChildren >= 10 && width != null && width in 100..20000) {
                best = width
                return
            }
            for (field in message.fields) {
                field.child?.let { visit(it) }
                if (best != null) return
            }
        }

        visit(root)
        return best
    }

    private fun parseMessage(
        data: ByteArray,
        start: Int,
        end: Int,
        parent: Message?,
        depth: Int,
    ): Message? {
        if (depth > 14 || start >= end) return null
        var pos = start
        val message = Message(start, end, parent, depth)

        while (pos < end) {
            val keyRead = readVarint(data, pos, end) ?: return null
            val key = keyRead.first
            pos = keyRead.second
            val fieldNumber = (key ushr 3).toInt()
            val wireType = (key and 7).toInt()
            if (fieldNumber == 0) return null

            when (wireType) {
                0 -> {
                    val valueRead = readVarint(data, pos, end) ?: return null
                    pos = valueRead.second
                    message.fields += Field(fieldNumber, wireType, numberValue = valueRead.first.toDouble())
                }
                1 -> {
                    if (pos + 8 > end) return null
                    val value = ByteBuffer.wrap(data, pos, 8)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .double
                    pos += 8
                    message.fields += Field(fieldNumber, wireType, numberValue = value)
                }
                2 -> {
                    val lenRead = readVarint(data, pos, end) ?: return null
                    val len = lenRead.first
                    if (len < 0L || len > Int.MAX_VALUE) return null
                    pos = lenRead.second
                    val next = pos + len.toInt()
                    if (next < pos || next > end) return null

                    val text = readableText(data, pos, next)
                    val child = parseMessage(data, pos, next, message, depth + 1)
                    message.fields += Field(fieldNumber, wireType, text = text, child = child)
                    pos = next
                }
                5 -> {
                    if (pos + 4 > end) return null
                    val value = ByteBuffer.wrap(data, pos, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .float
                        .toDouble()
                    pos += 4
                    message.fields += Field(fieldNumber, wireType, numberValue = value)
                }
                else -> return null
            }
        }

        return if (pos == end && message.fields.isNotEmpty()) message else null
    }

    private fun readVarint(data: ByteArray, start: Int, end: Int): Pair<Long, Int>? {
        var pos = start
        var shift = 0
        var value = 0L
        while (pos < end && shift < 64) {
            val b = data[pos].toInt() and 0xFF
            value = value or ((b and 0x7F).toLong() shl shift)
            pos++
            if ((b and 0x80) == 0) return value to pos
            shift += 7
        }
        return null
    }

    private fun readableText(data: ByteArray, start: Int, end: Int): String? {
        val size = end - start
        if (size !in 2..500) return null
        val text = try {
            data.copyOfRange(start, end).toString(Charsets.UTF_8)
        } catch (_: Throwable) {
            return null
        }
        if ('\uFFFD' in text) return null
        val cleaned = text
            .replace('\u0000', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
        if (cleaned.length < 2) return null
        val printable = cleaned.count { it.code in 0x20..0x7E || it.code >= 0xA0 }
        return if (printable >= cleaned.length * 9 / 10) cleaned else null
    }

    private fun collectTextHits(root: Message): List<TextHit> {
        val out = mutableListOf<TextHit>()
        var ordinal = 0

        fun visit(message: Message) {
            for (field in message.fields) {
                field.text?.let { out += TextHit(it, message, ordinal++) }
                field.child?.let { visit(it) }
            }
        }

        visit(root)
        return out
    }

    private fun chooseOcrText(hits: List<TextHit>): List<TextHit> {
        val candidates = hits
            .filter { isLikelyOcrText(it.text) }
            .distinctBy { normalizeText(it.text) }

        return candidates.filter { hit ->
            candidates.none { other ->
                other !== hit &&
                        other.text.length >= hit.text.length + 2 &&
                        normalizeText(other.text).contains(normalizeText(hit.text))
            }
        }.sortedBy { it.ordinal }
    }

    private fun isLikelyOcrText(text: String): Boolean {
        if (text.length !in 2..80) return false
        val blocked = listOf("google_ocr", "Unsupported", "Request", "mediapipe")
        if (blocked.any { text.contains(it, ignoreCase = true) }) return false
        val ascii = text.count { it.code in 0x21..0x7E }
        return ascii <= text.length * 3 / 4 || text.any { it.code in 0x3040..0x9FFF || it.code in 0xAC00..0xD7AF }
    }

    private fun normalizeText(text: String): String {
        return text.filterNot { it.isWhitespace() }
    }

    private fun findBox(owner: Message, imageWidth: Int, imageHeight: Int): NormalizedBBox? {
        var current: Message? = owner
        var hops = 0
        while (current != null && hops < 5) {
            candidateBox(current, imageWidth, imageHeight)?.let { return it }
            directChildBox(current, imageWidth, imageHeight)?.let { return it }
            current = current.parent
            hops++
        }
        return null
    }

    private fun directChildBox(message: Message, imageWidth: Int, imageHeight: Int): NormalizedBBox? {
        for (field in message.fields) {
            val child = field.child ?: continue
            candidateBox(child, imageWidth, imageHeight)?.let { return it }
        }
        return null
    }

    private fun candidateBox(message: Message, imageWidth: Int, imageHeight: Int): NormalizedBBox? {
        pointCloudBox(message, imageWidth, imageHeight)?.let { return it }

        val values = linkedMapOf<Int, MutableList<Double>>()
        for (field in message.fields) {
            val value = field.numberValue ?: continue
            if (!value.isFinite()) continue
            values.getOrPut(field.number) { mutableListOf() } += value
        }
        fun v(number: Int): Double? = values[number]?.firstOrNull()

        val f1 = v(1)
        val f2 = v(2)
        val f3 = v(3)
        val f4 = v(4)
        val f5 = v(5) ?: 0.0

        if (f1 != null && f2 != null && f3 != null && f4 != null) {
            screenAiRect(f1, f2, f3, f4, f5, imageWidth, imageHeight)?.let { return it }
            centerBox(f1, f2, f3, f4, f5, imageWidth, imageHeight)?.let { return it }
        }

        for (field in message.fields) {
            val child = field.child ?: continue
            candidateBox(child, imageWidth, imageHeight)?.let { return it }
        }
        return null
    }

    private fun pointCloudBox(message: Message, imageWidth: Int, imageHeight: Int): NormalizedBBox? {
        val points = message.fields.mapNotNull { it.child }.mapNotNull { child ->
            val xs = child.fields.firstOrNull { it.number == 1 && it.numberValue != null }?.numberValue
            val ys = child.fields.firstOrNull { it.number == 2 && it.numberValue != null }?.numberValue
            if (xs != null && ys != null && xs.isFinite() && ys.isFinite()) xs to ys else null
        }
        if (points.size < 4) return null

        val minX = points.minOf { it.first }
        val maxX = points.maxOf { it.first }
        val minY = points.minOf { it.second }
        val maxY = points.maxOf { it.second }
        return cornerBox(minX, minY, maxX, maxY, imageWidth, imageHeight)
    }

    private fun screenAiRect(
        x: Double, y: Double,
        width: Double, height: Double,
        angleDegrees: Double,
        imageWidth: Int, imageHeight: Int,
    ): NormalizedBBox? {
        if (width <= 0.0 || height <= 0.0) return null
        if (imageWidth <= 0 || imageHeight <= 0) return null
        if (width <= 1.5 && height <= 1.5) return null

        val angleRad = Math.toRadians(angleDegrees)

        val cxOffset = (width / 2.0) * Math.cos(angleRad) - (height / 2.0) * Math.sin(angleRad)
        val cyOffset = (width / 2.0) * Math.sin(angleRad) + (height / 2.0) * Math.cos(angleRad)
        val centerX = x + cxOffset
        val centerY = y + cyOffset

        val normLeft = ((centerX - width / 2.0) / imageWidth)
        val normTop = ((centerY - height / 2.0) / imageHeight)
        val normRight = ((centerX + width / 2.0) / imageWidth)
        val normBottom = ((centerY + height / 2.0) / imageHeight)

        return makeBox(normLeft, normTop, normRight, normBottom, angleRad)
    }

    private fun cornerBox(
        left: Double, top: Double, right: Double, bottom: Double,
        imageWidth: Int, imageHeight: Int,
    ): NormalizedBBox? {
        if (right <= left || bottom <= top) return null
        if (right <= 1.5 || bottom <= 1.5) return null
        if (imageWidth <= 0 || imageHeight <= 0) return null
        if (right > imageWidth * 1.2 || bottom > imageHeight * 1.2) return null
        return makeBox(
            left / imageWidth,
            top / imageHeight,
            right / imageWidth,
            bottom / imageHeight,
            0.0,
        )
    }

    private fun centerBox(
        centerX: Double, centerY: Double,
        width: Double, height: Double,
        rotation: Double,
        imageWidth: Int, imageHeight: Int,
    ): NormalizedBBox? {
        if (width <= 0.0 || height <= 0.0) return null
        if (centerX !in -0.2..1.2 || centerY !in -0.2..1.2) return null
        if (width > 1.2 || height > 1.2) return null
        return makeBox(
            centerX - width / 2.0,
            centerY - height / 2.0,
            centerX + width / 2.0,
            centerY + height / 2.0,
            rotation,
        )
    }

    private fun makeBox(left: Double, top: Double, right: Double, bottom: Double, rotation: Double): NormalizedBBox? {
        val l = left.coerceIn(-0.1, 1.1)
        val t = top.coerceIn(-0.1, 1.1)
        val r = right.coerceIn(-0.1, 1.1)
        val b = bottom.coerceIn(-0.1, 1.1)
        val lc = l.coerceIn(0.0, 1.0)
        val tc = t.coerceIn(0.0, 1.0)
        val rc = r.coerceIn(0.0, 1.0)
        val bc = b.coerceIn(0.0, 1.0)
        if (rc - lc < 0.003 || bc - tc < 0.003) return null
        if (rc - lc > 0.98 && bc - tc > 0.98) return null
        return NormalizedBBox(lc, tc, rc, bc, rotation)
    }

    private fun inferDirection(box: NormalizedBBox): WritingDirection {
        val width = box.width
        val height = box.height
        val rotation = normalizedRadians(box.rotation)
        val verticalByRotation = if (abs(rotation) > 0.1) {
            abs(abs(rotation) - Math.PI / 2.0) < 0.5
        } else {
            false
        }
        val verticalByShape = width <= height
        return if (verticalByRotation || verticalByShape) {
            WritingDirection.TTB
        } else {
            WritingDirection.LTR
        }
    }

    private fun normalizedRadians(value: Double): Double {
        var radians = value
        val fullTurn = Math.PI * 2.0
        while (radians > Math.PI) radians -= fullTurn
        while (radians < -Math.PI) radians += fullTurn
        return radians
    }

    private fun fallbackLayout(texts: List<String>): List<EngineLine> {
        val count = texts.size.coerceAtLeast(1)
        val rows = ceil(sqrt(count.toDouble()) * 1.35).toInt().coerceIn(3, 7)
        val columns = ceil(count.toDouble() / rows).toInt().coerceAtLeast(1)
        val boxWidth = min(0.2, 0.78 / columns)
        val boxHeight = min(0.13, 0.82 / rows)
        val gapX = if (columns <= 1) 0.0 else (0.78 - boxWidth * columns) / (columns - 1)
        val gapY = if (rows <= 1) 0.0 else (0.82 - boxHeight * rows) / (rows - 1)

        return texts.mapIndexed { index, text ->
            val column = index / rows
            val row = index % rows
            val right = 0.95 - column * (boxWidth + gapX)
            val left = right - boxWidth
            val top = 0.08 + row * (boxHeight + gapY)
            EngineLine(
                text = text,
                bbox = NormalizedBBox(left, top, right, top + boxHeight),
                writingDirection = WritingDirection.TTB,
                language = OcrLanguage.JAPANESE,
            )
        }
    }
}
