package com.example.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodes the written GIFs with the JDK's independent GIF reader. */
class GifWriterTest {

    private class Decoded(val frames: List<BufferedImage>, val delays: List<Int>, val loops: Boolean)

    private fun decode(bytes: ByteArray): Decoded {
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        val count = reader.getNumImages(true)
        val frames = (0 until count).map { reader.read(it) }
        val delays = (0 until count).map { index ->
            val root = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
            val gce = root.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
            gce.getAttribute("delayTime").toInt()
        }
        val stream = reader.streamMetadata?.getAsTree("javax_imageio_gif_stream_1.0")
        val loops = bytes.toString(Charsets.ISO_8859_1).contains("NETSCAPE2.0")
        check(stream != null)
        return Decoded(frames, delays, loops)
    }

    private fun encode(frames: List<IntArray>, w: Int, h: Int, delayMs: Int, dither: Int = GifWriter.DEFAULT_DITHER): ByteArray {
        val out = ByteArrayOutputStream()
        GifWriter.write(out, frames, w, h, delayMs, dither)
        return out.toByteArray()
    }

    @Test
    fun fewColoursRoundTripExactly() {
        // Random pixels from 200 colours: incompressible data that fills the LZW table many
        // times over, so clear codes and every code width are exercised.
        val random = Random(7)
        val colours = IntArray(200) { random.nextInt(0x1000000) }
        val w = 517
        val h = 389
        val a = IntArray(w * h) { colours[random.nextInt(colours.size)] }
        val b = IntArray(w * h) { colours[(it / 97) % colours.size] } // long runs
        val decoded = decode(encode(listOf(a, b), w, h, 150))

        assertEquals(2, decoded.frames.size)
        assertEquals(listOf(15, 15), decoded.delays)
        assertTrue(decoded.loops)
        for ((frame, source) in decoded.frames.zip(listOf(a, b))) {
            assertEquals(w, frame.width)
            assertEquals(h, frame.height)
            for (y in 0 until h) for (x in 0 until w) {
                assertEquals("pixel $x,$y", source[y * w + x] and 0xFFFFFF, frame.getRGB(x, y) and 0xFFFFFF)
            }
        }
    }

    @Test
    fun photoLikeFramesStayClose() {
        // Smooth gradients plus noise: far more than 256 colours, so the palette and dither
        // have to approximate.
        val random = Random(3)
        val w = 640
        val h = 480
        fun frame(shift: Int) = IntArray(w * h) { i ->
            val x = i % w + shift
            val y = i / w
            val r = (x * 255 / w).coerceIn(0, 255)
            val g = (y * 255 / h).coerceIn(0, 255)
            val b = (128 + 60 * kotlin.math.sin(x / 40.0) + random.nextInt(-6, 7)).toInt().coerceIn(0, 255)
            (r shl 16) or (g shl 8) or b
        }
        val frames = listOf(frame(0), frame(12))
        val decoded = decode(encode(frames, w, h, 120))

        assertEquals(2, decoded.frames.size)
        assertEquals(listOf(12, 12), decoded.delays)
        for ((image, source) in decoded.frames.zip(frames)) {
            var error = 0L
            var worst = 0
            for (y in 0 until h) for (x in 0 until w) {
                val p = source[y * w + x]
                val q = image.getRGB(x, y)
                for (shift in intArrayOf(16, 8, 0)) {
                    val d = abs(((p shr shift) and 0xFF) - ((q shr shift) and 0xFF))
                    error += d
                    worst = maxOf(worst, d)
                }
            }
            val mean = error.toDouble() / (3 * w * h)
            assertTrue("mean error $mean", mean < 6.0)
            assertTrue("worst error $worst", worst < 48)
        }
    }

    @Test
    fun repeatedFramesAndSinglePixel() {
        val w = 3
        val h = 2
        val a = IntArray(w * h) { 0x102030 * (it + 1) }
        val b = IntArray(w * h) { 0xFFFFFF - a[it] }
        // Ping-pong of three frames repeats the middle one.
        val decoded = decode(encode(listOf(a, b, a, b), w, h, 40))
        assertEquals(4, decoded.frames.size)
        assertEquals(b[4] and 0xFFFFFF, decoded.frames[3].getRGB(1, 1) and 0xFFFFFF)

        val single = decode(encode(listOf(intArrayOf(0x00FF00)), 1, 1, 100))
        assertEquals(1, single.frames.size)
        assertEquals(0x00FF00, single.frames[0].getRGB(0, 0) and 0xFFFFFF)
    }
}
