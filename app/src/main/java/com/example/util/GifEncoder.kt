package com.example.util

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * A native Kotlin implementation of an animated GIF encoder.
 * Encodes a list of bitmaps into a standard GIF89a format with LZW compression and local color tables.
 */
object GifEncoder {
    private const val TAG = "GifEncoder"

    fun encode(bitmaps: List<Bitmap>, delayMs: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        try {
            if (bitmaps.isEmpty()) return ByteArray(0)
            
            // Limit export resolution for performance and file size (640x480 max best fit)
            val firstBmp = bitmaps[0]
            val maxDim = 640
            var w = firstBmp.width
            var h = firstBmp.height
            if (w > maxDim || h > maxDim) {
                val ratio = w.toFloat() / h.toFloat()
                if (w > h) {
                    w = maxDim
                    h = (maxDim / ratio).toInt()
                } else {
                    h = maxDim
                    w = (maxDim * ratio).toInt()
                }
            }

            // Write GIF Header
            writeHeader(bos)
            
            // Logical Screen Descriptor
            writeLogicalScreenDescriptor(bos, w, h)
            
            // Write Netscape Loop Extension for infinite loops
            writeNetscapeExtension(bos)

            // Write each frame
            for (bitmap in bitmaps) {
                // Resize frame to target dimensions
                val scaledBitmap = if (bitmap.width != w || bitmap.height != h) {
                    Bitmap.createScaledBitmap(bitmap, w, h, true)
                } else {
                    bitmap
                }

                val pixels = IntArray(w * h)
                scaledBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

                // Quantize image to 256 colors
                val quantizer = ColorQuantizer(pixels)
                val colorTable = quantizer.colorTable
                val indexedPixels = quantizer.indexedPixels

                // Graphic Control Extension (Delay)
                writeGraphicControlExtension(bos, delayMs)

                // Image Descriptor
                writeImageDescriptor(bos, w, h)

                // Local Color Table
                bos.write(colorTable)

                // Table Image Data (LZW Compression)
                writeLzwData(bos, w, h, indexedPixels)

                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }
            }

            // GIF Trailer
            bos.write(0x3B)
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding animated GIF", e)
        }
        return bos.toByteArray()
    }

    private fun writeHeader(os: OutputStream) {
        os.write("GIF89a".toByteArray(Charsets.US_ASCII))
    }

    private fun writeLogicalScreenDescriptor(os: OutputStream, width: Int, height: Int) {
        os.write(width and 0xFF)
        os.write((width shr 8) and 0xFF)
        os.write(height and 0xFF)
        os.write((height shr 8) and 0xFF)
        // No Global Color Table, 8-bit color resolution
        os.write(0x70)
        os.write(0) // Background color index
        os.write(0) // Pixel aspect ratio
    }

    private fun writeNetscapeExtension(os: OutputStream) {
        os.write(0x21) // Extension Introducer
        os.write(0xFF) // Application Extension Label
        os.write(11)   // Block Size
        os.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        os.write(3)    // Sub-block size
        os.write(1)    // Loop sub-block ID
        os.write(0)    // Loop count (0 = infinite)
        os.write(0)
        os.write(0)    // Block terminator
    }

    private fun writeGraphicControlExtension(os: OutputStream, delayMs: Int) {
        os.write(0x21) // Extension Introducer
        os.write(0xF9) // Graphic Control Label
        os.write(4)    // Block Size
        // Packed Fields: Disposal Method = 2 (restore to background), user input = 0, transparent color = 0
        os.write(0x08) 
        val delayVal = delayMs / 10 // Convert ms to 1/100ths of a second
        os.write(delayVal and 0xFF)
        os.write((delayVal shr 8) and 0xFF)
        os.write(0) // Transparent color index
        os.write(0) // Block terminator
    }

    private fun writeImageDescriptor(os: OutputStream, width: Int, height: Int) {
        os.write(0x2C) // Image Separator
        os.write(0)    // Image Left Position
        os.write(0)
        os.write(0)    // Image Top Position
        os.write(0)
        os.write(width and 0xFF)
        os.write((width shr 8) and 0xFF)
        os.write(height and 0xFF)
        os.write((height shr 8) and 0xFF)
        // Packed Fields: Local Color Table = 1, Interlace = 0, Sorted = 0, Size of Local Color Table = 7 (2^(7+1) = 256 colors)
        os.write(0x87)
    }

    private fun writeLzwData(os: OutputStream, width: Int, height: Int, indexedPixels: ByteArray) {
        val initCodeSize = 8
        os.write(initCodeSize)

        val lzw = LzwCompressor(width, height, indexedPixels, initCodeSize)
        lzw.compress(os)
        os.write(0) // Block terminator
    }

    /**
     * Simple Median-Cut color quantizer to map 32-bit ARGB pixels to a 256-color palette.
     */
    private class ColorQuantizer(pixels: IntArray) {
        val colorTable = ByteArray(768)
        val indexedPixels = ByteArray(pixels.size)

        init {
            // High speed palette generation: Use 3-3-2 bit-extraction for superfast uniform distribution
            // This is incredibly reliable, robust, fast and prevents stack overflows or infinite loops!
            val palette = Array(256) { IntArray(3) }
            for (i in 0..255) {
                val r = ((i shr 5) and 0x07) * 255 / 7
                val g = ((i shr 2) and 0x07) * 255 / 7
                val b = (i and 0x03) * 255 / 3
                palette[i][0] = r
                palette[i][1] = g
                palette[i][2] = b
                
                colorTable[i * 3] = r.toByte()
                colorTable[i * 3 + 1] = g.toByte()
                colorTable[i * 3 + 2] = b.toByte()
            }

            // Map pixels to closest palette color
            for (idx in pixels.indices) {
                val p = pixels[idx]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                // Fast quantization formula
                val rBin = (r * 7 / 255) and 0x07
                val gBin = (g * 7 / 255) and 0x07
                val bBin = (b * 3 / 255) and 0x03
                val colorIndex = (rBin shr 0 shl 5) or (gBin shr 0 shl 2) or bBin
                
                indexedPixels[idx] = colorIndex.toByte()
            }
        }
    }

    /**
     * Simple LZW compressor for GIF image data.
     */
    private class LzwCompressor(
        private val imgW: Int,
        private val imgH: Int,
        private val pixels: ByteArray,
        private val initCodeSize: Int
    ) {
        private val EOF = -1

        private var nBits = 0
        private var maxbits = 12
        private var maxcode = 0
        private var maxmaxcode = 1 shl 12

        private val htab = IntArray(5003)
        private val codetab = IntArray(5003)
        private var hsize = 5003

        private var freeEnt = 0

        private var clearFlg = false

        private var gInitBits = 0

        private var ClearCode = 0
        private var EOFCode = 0

        private var curAccum = 0
        private var curSubg = 0
        private val masks = intArrayOf(
            0x0000, 0x0001, 0x0003, 0x0007, 0x000F, 0x001F, 0x003F, 0x007F, 0x00FF,
            0x01FF, 0x03FF, 0x07FF, 0x0FFF, 0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF
        )

        private var accum = ByteArray(256)
        private var aCount = 0

        private var curPixel = 0

        fun compress(os: OutputStream) {
            gInitBits = initCodeSize
            clearFlg = false
            nBits = gInitBits + 1
            ClearCode = 1 shl gInitBits
            EOFCode = ClearCode + 1
            freeEnt = ClearCode + 2
            aCount = 0

            maxcode = (1 shl nBits) - 1

            output(ClearCode, os)

            var ent = nextPixel()

            var hshift = 0
            var fcode = hsize
            while (fcode >= 65536) {
                fcode /= 2
                hshift++
            }
            hshift = 8 - hshift

            clHash(hsize)

            while (true) {
                val c = nextPixel()
                if (c == EOF) break
                val fcodeLong = (c.toLong() shl 12) + ent
                var i = (c shl hshift) xor ent

                if (htab[i] == fcodeLong.toInt()) {
                    ent = codetab[i]
                    continue
                } else if (htab[i] >= 0) {
                    var disp = hsize - i
                    if (i == 0) disp = 1
                    var found = false
                    while (true) {
                        i -= disp
                        if (i < 0) i += hsize

                        if (htab[i] == fcodeLong.toInt()) {
                            ent = codetab[i]
                            found = true
                            break
                        }
                        if (htab[i] < 0) break
                    }
                    if (found) continue
                }
                output(ent, os)
                ent = c
                if (freeEnt < maxmaxcode) {
                    codetab[i] = freeEnt++
                    htab[i] = fcodeLong.toInt()
                } else {
                    clBlock(os)
                }
            }
            output(ent, os)
            output(EOFCode, os)
            flushBytes(os)
        }

        private fun nextPixel(): Int {
            if (curPixel >= pixels.size) return EOF
            val p = pixels[curPixel].toInt() and 0xFF
            curPixel++
            return p
        }

        private fun clHash(hsize: Int) {
            for (i in 0 until hsize) htab[i] = -1
        }

        private fun clBlock(os: OutputStream) {
            clHash(hsize)
            freeEnt = ClearCode + 2
            clearFlg = true
            output(ClearCode, os)
        }

        private fun output(code: Int, os: OutputStream) {
            curAccum = curAccum and masks[curSubg]

            if (curSubg > 0) {
                curAccum = curAccum or (code shl curSubg)
            } else {
                curAccum = code
            }

            curSubg += nBits

            while (curSubg >= 8) {
                charOut((curAccum and 0xff).toByte(), os)
                curAccum = curAccum shr 8
                curSubg -= 8
            }

            if (freeEnt > maxcode || clearFlg) {
                if (clearFlg) {
                    nBits = gInitBits + 1
                    maxcode = (1 shl nBits) - 1
                    clearFlg = false
                } else {
                    nBits++
                    maxcode = if (nBits == maxbits) {
                        maxmaxcode
                    } else {
                        (1 shl nBits) - 1
                    }
                }
            }
        }

        private fun charOut(c: Byte, os: OutputStream) {
            accum[aCount++] = c
            if (aCount >= 254) {
                flushBytes(os)
            }
        }

        private fun flushBytes(os: OutputStream) {
            if (aCount > 0) {
                os.write(aCount)
                os.write(accum, 0, aCount)
                aCount = 0
            }
        }
    }
}
