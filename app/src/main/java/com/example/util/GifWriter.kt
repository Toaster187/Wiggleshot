package com.example.util

import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Writes an animated GIF89a from ARGB pixel arrays (no Android dependencies, so it can be
 * tested on the JVM).
 *
 * All frames share one palette, built by median cut over the colours of every frame and
 * refined with a few k-means passes, so the wiggle does not flicker in colour between frames.
 * Pixels are mapped with a light ordered (Bayer) dither: unlike error diffusion its pattern
 * stays put where the frames agree, which keeps the still parts of a wiggle calm.
 */
object GifWriter {

    /** Peak-to-peak amplitude of the ordered dither, in 8-bit levels. */
    const val DEFAULT_DITHER = 12

    fun write(
        out: OutputStream,
        frames: List<IntArray>,
        width: Int,
        height: Int,
        delayMs: Int,
        dither: Int = DEFAULT_DITHER
    ) {
        require(frames.isNotEmpty()) { "No frames" }
        require(width in 1..0xFFFF && height in 1..0xFFFF) { "Bad size ${width}x$height" }
        frames.forEach { require(it.size >= width * height) { "Frame smaller than ${width}x$height" } }

        val palette = GifPalette.build(frames, width * height)

        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(out, width)
        writeShort(out, height)
        // Global colour table, 8 bits per primary, 256 entries (2^(7+1)).
        out.write(0xF7)
        out.write(0) // background colour index
        out.write(0) // pixel aspect ratio
        out.write(palette.colorTable())

        if (frames.size > 1) writeLoopExtension(out)

        val delay = ((delayMs + 5) / 10).coerceIn(2, 0xFFFF)
        val indices = ByteArray(width * height)
        val distinct = HashMap<IntArray, ByteArray>()
        for (frame in frames) {
            // Ping-pong exports repeat frames; map each distinct frame only once.
            val mapped = distinct.getOrPut(frame) {
                palette.map(frame, width, height, dither, ByteArray(width * height))
            }
            mapped.copyInto(indices)

            // Graphic control extension: disposal "do not dispose", no transparency.
            out.write(0x21); out.write(0xF9); out.write(4)
            out.write(0x04)
            writeShort(out, delay)
            out.write(0)
            out.write(0)

            // Image descriptor: full frame, no local colour table, not interlaced.
            out.write(0x2C)
            writeShort(out, 0)
            writeShort(out, 0)
            writeShort(out, width)
            writeShort(out, height)
            out.write(0)

            out.write(LzwEncoder.MIN_CODE_SIZE)
            LzwEncoder(out).encode(indices)
            out.write(0) // end of image data
        }
        out.write(0x3B)
        out.flush()
    }

    private fun writeLoopExtension(out: OutputStream) {
        out.write(0x21); out.write(0xFF); out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(1)
        writeShort(out, 0) // loop forever
        out.write(0)
    }

    private fun writeShort(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }
}

/** A 256-colour palette shared by all frames, with a cached nearest-colour lookup. */
internal class GifPalette private constructor(private val colors: IntArray) {

    /** Nearest palette index per 6-bit-per-channel colour, filled on demand (-1 = unknown). */
    private val lookup = IntArray(1 shl 18) { -1 }

    val size: Int get() = colors.size

    fun color(index: Int): Int = colors[index]

    fun colorTable(): ByteArray {
        val table = ByteArray(256 * 3)
        for (i in colors.indices) {
            table[i * 3] = (colors[i] shr 16).toByte()
            table[i * 3 + 1] = (colors[i] shr 8).toByte()
            table[i * 3 + 2] = colors[i].toByte()
        }
        return table
    }

    /** Index of the palette colour nearest to (r, g, b). */
    fun indexOf(r: Int, g: Int, b: Int): Int {
        val key = ((r shr 2) shl 12) or ((g shr 2) shl 6) or (b shr 2)
        val cached = lookup[key]
        if (cached >= 0) return cached
        val index = nearest(colors, (r and 0xFC) + 2, (g and 0xFC) + 2, (b and 0xFC) + 2)
        lookup[key] = index
        return index
    }

    fun map(pixels: IntArray, width: Int, height: Int, dither: Int, into: ByteArray): ByteArray {
        val exact = exactIndex
        for (y in 0 until height) {
            val row = y * width
            val bayerRow = (y and 7) * 8
            for (x in 0 until width) {
                val p = pixels[row + x] and 0xFFFFFF
                val known = exact?.get(p)
                if (known != null) {
                    into[row + x] = known.toByte()
                    continue
                }
                val offset = if (dither > 0) ((BAYER[bayerRow + (x and 7)] * 2 + 1 - 64) * dither) / 128 else 0
                val r = ((p shr 16) + offset).coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + offset).coerceIn(0, 255)
                val b = ((p and 0xFF) + offset).coerceIn(0, 255)
                into[row + x] = indexOf(r, g, b).toByte()
            }
        }
        return into
    }

    /** For images with at most 256 colours the palette is exact and no dithering is needed. */
    private var exactIndex: Map<Int, Int>? = null

    companion object {
        private const val MAX_COLORS = 256
        private const val BITS = 5
        private const val BINS = 1 shl (3 * BITS)
        private const val REFINE_PASSES = 3

        /** 8×8 Bayer threshold matrix, values 0..63. */
        private val BAYER = intArrayOf(
            0, 32, 8, 40, 2, 34, 10, 42,
            48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38,
            60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41,
            51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37,
            63, 31, 55, 23, 61, 29, 53, 21
        )

        fun build(frames: List<IntArray>, pixelCount: Int): GifPalette {
            exactColors(frames, pixelCount)?.let { exact ->
                return GifPalette(exact).also { palette ->
                    palette.exactIndex = exact.withIndex().associate { (i, c) -> c to i }
                }
            }

            // Histogram of 5-bit-per-channel bins with the true mean colour of each bin.
            val count = IntArray(BINS)
            val sumR = LongArray(BINS)
            val sumG = LongArray(BINS)
            val sumB = LongArray(BINS)
            val step = max(1, (frames.size * pixelCount.toLong() / 2_000_000L).toInt())
            for (frame in frames) {
                var i = 0
                while (i < pixelCount) {
                    val p = frame[i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val bin = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                    count[bin]++
                    sumR[bin] += r.toLong(); sumG[bin] += g.toLong(); sumB[bin] += b.toLong()
                    i += step
                }
            }
            val used = (0 until BINS).filter { count[it] > 0 }
            val n = used.size
            val weight = IntArray(n) { count[used[it]] }
            val channels = Array(3) { DoubleArray(n) }
            for (k in 0 until n) {
                val bin = used[k]
                channels[0][k] = sumR[bin].toDouble() / count[bin]
                channels[1][k] = sumG[bin].toDouble() / count[bin]
                channels[2][k] = sumB[bin].toDouble() / count[bin]
            }

            val boxes = medianCut(weight, channels)
            val colors = IntArray(boxes.size) { boxes[it].meanColor() }
            refine(colors, weight, channels)
            return GifPalette(colors)
        }

        /** The distinct colours if there are no more than 256 of them, else null. */
        private fun exactColors(frames: List<IntArray>, pixelCount: Int): IntArray? {
            val seen = HashSet<Int>()
            for (frame in frames) {
                for (i in 0 until pixelCount) {
                    if (seen.add(frame[i] and 0xFFFFFF) && seen.size > MAX_COLORS) return null
                }
            }
            return seen.toIntArray()
        }

        private class Box(
            val members: IntArray,
            val weight: IntArray,
            val channels: Array<DoubleArray>
        ) {
            val total: Long = members.sumOf { weight[it].toLong() }
            val mean = DoubleArray(3)
            val variance = DoubleArray(3)

            init {
                for (c in 0..2) {
                    var s = 0.0
                    for (m in members) s += channels[c][m] * weight[m]
                    mean[c] = s / total
                    var v = 0.0
                    for (m in members) {
                        val d = channels[c][m] - mean[c]
                        v += d * d * weight[m]
                    }
                    variance[c] = v
                }
            }

            /** Squared error this box adds to the image. */
            val error: Double get() = variance[0] + variance[1] + variance[2]

            fun meanColor(): Int {
                val r = mean[0].roundToInt().coerceIn(0, 255)
                val g = mean[1].roundToInt().coerceIn(0, 255)
                val b = mean[2].roundToInt().coerceIn(0, 255)
                return (r shl 16) or (g shl 8) or b
            }

            /** Splits at the weighted median of the channel with the largest spread. */
            fun split(): Pair<Box, Box>? {
                if (members.size < 2) return null
                val c = (0..2).maxBy { variance[it] }
                val sorted = members.sortedBy { channels[c][it] }.toIntArray()
                var acc = 0L
                var cut = 1
                for (i in sorted.indices) {
                    acc += weight[sorted[i]]
                    if (acc * 2 >= total) {
                        cut = (i + 1).coerceIn(1, sorted.size - 1)
                        break
                    }
                }
                return Box(sorted.copyOfRange(0, cut), weight, channels) to
                    Box(sorted.copyOfRange(cut, sorted.size), weight, channels)
            }
        }

        private fun medianCut(weight: IntArray, channels: Array<DoubleArray>): List<Box> {
            val boxes = mutableListOf(Box(IntArray(weight.size) { it }, weight, channels))
            while (boxes.size < MAX_COLORS) {
                val candidate = boxes.filter { it.members.size > 1 }.maxByOrNull { it.error } ?: break
                if (candidate.error <= 0.0) break
                val (a, b) = candidate.split() ?: break
                boxes.remove(candidate)
                boxes += a
                boxes += b
            }
            return boxes
        }

        /** A few k-means passes over the histogram bins pull the colours to where the pixels are. */
        private fun refine(colors: IntArray, weight: IntArray, channels: Array<DoubleArray>) {
            val k = colors.size
            repeat(REFINE_PASSES) {
                val sr = DoubleArray(k)
                val sg = DoubleArray(k)
                val sb = DoubleArray(k)
                val sw = LongArray(k)
                for (m in weight.indices) {
                    val r = channels[0][m]
                    val g = channels[1][m]
                    val b = channels[2][m]
                    val i = nearest(colors, r.roundToInt(), g.roundToInt(), b.roundToInt())
                    val w = weight[m]
                    sr[i] += r * w; sg[i] += g * w; sb[i] += b * w; sw[i] += w.toLong()
                }
                for (i in 0 until k) {
                    if (sw[i] == 0L) continue
                    val r = (sr[i] / sw[i]).roundToInt().coerceIn(0, 255)
                    val g = (sg[i] / sw[i]).roundToInt().coerceIn(0, 255)
                    val b = (sb[i] / sw[i]).roundToInt().coerceIn(0, 255)
                    colors[i] = (r shl 16) or (g shl 8) or b
                }
            }
        }

        private fun nearest(colors: IntArray, r: Int, g: Int, b: Int): Int {
            var best = 0
            var bestDistance = Int.MAX_VALUE
            for (i in colors.indices) {
                val c = colors[i]
                val dr = ((c shr 16) and 0xFF) - r
                val dg = ((c shr 8) and 0xFF) - g
                val db = (c and 0xFF) - b
                // Weighted towards green, as the eye is most sensitive to it.
                val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                if (d < bestDistance) {
                    bestDistance = d
                    best = i
                }
            }
            return best
        }
    }
}

/**
 * GIF variant of LZW: 8-bit pixel codes, variable code width from 9 to 12 bits, a clear code
 * when the table is full, data in sub-blocks of at most 255 bytes.
 */
internal class LzwEncoder(private val out: OutputStream) {

    companion object {
        const val MIN_CODE_SIZE = 8
        private const val CLEAR = 1 shl MIN_CODE_SIZE
        private const val END = CLEAR + 1
        private const val FIRST_FREE = CLEAR + 2
        private const val MAX_CODES = 1 shl 12
        private const val HASH_SIZE = 1 shl 13
    }

    // Open-addressing table: key (prefix code << 8 | pixel) + 1, 0 = empty.
    private val keys = IntArray(HASH_SIZE)
    private val values = IntArray(HASH_SIZE)

    private var codeSize = MIN_CODE_SIZE + 1
    private var nextCode = FIRST_FREE

    private var bitBuffer = 0L
    private var bitCount = 0
    private val block = ByteArray(255)
    private var blockSize = 0

    fun encode(pixels: ByteArray) {
        emit(CLEAR)
        if (pixels.isEmpty()) {
            emit(END)
            flush()
            return
        }
        var prefix = pixels[0].toInt() and 0xFF
        for (i in 1 until pixels.size) {
            val pixel = pixels[i].toInt() and 0xFF
            val key = (prefix shl 8) or pixel
            var slot = (key * -0x61c88647 ushr 19) and (HASH_SIZE - 1)
            var found = -1
            while (keys[slot] != 0) {
                if (keys[slot] == key + 1) {
                    found = values[slot]
                    break
                }
                slot = (slot + 1) and (HASH_SIZE - 1)
            }
            if (found >= 0) {
                prefix = found
                continue
            }

            emit(prefix)
            if (nextCode < MAX_CODES) {
                // The decoder adds this entry one code later; the width grows when the
                // next free code no longer fits.
                keys[slot] = key + 1
                values[slot] = nextCode
                if (nextCode >= (1 shl codeSize) && codeSize < 12) codeSize++
                nextCode++
            } else {
                emit(CLEAR)
                keys.fill(0)
                codeSize = MIN_CODE_SIZE + 1
                nextCode = FIRST_FREE
            }
            prefix = pixel
        }
        emit(prefix)
        if (nextCode < MAX_CODES && nextCode >= (1 shl codeSize) && codeSize < 12) codeSize++
        emit(END)
        flush()
    }

    private fun emit(code: Int) {
        bitBuffer = bitBuffer or (code.toLong() shl bitCount)
        bitCount += codeSize
        while (bitCount >= 8) {
            writeByte((bitBuffer and 0xFF).toInt())
            bitBuffer = bitBuffer ushr 8
            bitCount -= 8
        }
    }

    private fun writeByte(value: Int) {
        block[blockSize++] = value.toByte()
        if (blockSize == block.size) flushBlock()
    }

    private fun flushBlock() {
        if (blockSize == 0) return
        out.write(blockSize)
        out.write(block, 0, blockSize)
        blockSize = 0
    }

    private fun flush() {
        if (bitCount > 0) {
            writeByte((bitBuffer and 0xFF).toInt())
            bitBuffer = 0
            bitCount = 0
        }
        flushBlock()
    }
}
