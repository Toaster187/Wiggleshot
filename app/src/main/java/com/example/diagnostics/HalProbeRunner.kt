package com.example.diagnostics

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.align.Similarity
import com.example.align.WiggleAligner
import com.example.camera.CameraImages
import com.example.camera.CameraLensDetails
import com.example.camera.CapturedFrame
import com.example.camera.HalZoomProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Runs [HalZoomProbe], compares the HAL's own ultra wide -> 1x matching with Wiggleshot's
 * estimate and saves everything to `Download/Wiggleshot/hal_probe_<time>`.
 *
 * Per probe shot below 1.0x (where the HAL serves the logical stream from the ultra wide):
 *  - `ours`: main -> ultra wide, measured by [WiggleAligner] on the raw physical frames,
 *  - `hal`: logical -> ultra wide, i.e. how the HAL cropped/warped the ultra wide, rescaled
 *    to 1.0x and to the main frame's pixel grid,
 *  - `deviationPx4k`: how far apart the two put the frame corners, in pixels of a 4000 px
 *    wide frame. A few pixels means our matching equals the Pixel camera's.
 */
object HalProbeRunner {
    private const val TAG = "HalProbeRunner"

    class Summary(val text: String, val folder: String?)

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun run(context: Context, lenses: List<CameraLensDetails>, onProgress: (String) -> Unit): Summary {
        val groups = lenses.filter { it.isPhysical }.groupBy { it.logicalId }.filterValues { it.size >= 2 }
        val (logicalId, physical) = groups.entries.firstOrNull()?.toPair()
            ?: return Summary("Keine logische Mehrfachkamera mit zwei physischen Linsen gefunden.", null)

        val sorted = physical.filter { it.focalLength != null }.sortedBy { it.focalLength }
        val ultraWide = sorted.firstOrNull() ?: return Summary("Brennweiten unbekannt.", null)
        val main = sorted.getOrNull(1) ?: return Summary("Keine Hauptlinse gefunden.", null)

        val probe = HalZoomProbe(context, logicalId, listOf(main.id, ultraWide.id))
        val outcome = probe.run(onProgress = onProgress)
        if (outcome.shots.isEmpty()) {
            // Keep the protocol so the failure can be analysed.
            val folder = DiagnosticsExporter.newFolder(context, "hal_probe_failed")
            folder.writeJson(
                "hal_probe.json",
                JSONObject()
                    .put("device", DiagnosticsExporter.deviceJson())
                    .put("logicalId", logicalId)
                    .put("mainId", main.id)
                    .put("ultraWideId", ultraWide.id)
                    .put("error", outcome.error ?: JSONObject.NULL)
                    .put("log", JSONArray(outcome.log))
                    .put("cameras", DiagnosticsExporter.cameraInfo(context).optJSONArray("cameras"))
            )
            return Summary("HAL-Test fehlgeschlagen: ${outcome.error}\nProtokoll: ${folder.displayPath}", folder.displayPath)
        }

        onProgress("Analysiere …")
        return withContext(Dispatchers.Default) {
            val aligner = WiggleAligner(context)
            val folder = DiagnosticsExporter.newFolder(context, "hal_probe")
            val shotsJson = JSONArray()
            val lines = mutableListOf<String>()

            var oursAtSwitch: Similarity? = null
            for (shot in outcome.shots) {
                val tag = String.format(Locale.US, "z%.2f", shot.requestedZoom)
                shot.logicalJpeg?.let { folder.write("${tag}_logical.jpg", "image/jpeg", it) }
                shot.physicalJpegs.forEach { (id, bytes) -> folder.write("${tag}_phys_$id.jpg", "image/jpeg", bytes) }

                val json = JSONObject()
                    .put("requestedZoom", shot.requestedZoom.toDouble())
                    .put("logical", shot.logical?.let(DiagnosticsExporter::metadataJson) ?: JSONObject.NULL)
                    .put("physical", JSONObject().apply {
                        shot.physical.forEach { (id, m) -> put(id, DiagnosticsExporter.metadataJson(m)) }
                    })
                    .put("activePhysicalSensorCrop", shot.activePhysicalSensorCrop?.let {
                        "${it.left},${it.top} ${it.width}x${it.height}"
                    } ?: JSONObject.NULL)

                try {
                    analyzeShot(aligner, shot, outcome, main, ultraWide, json)?.let { (line, ours) ->
                        lines += line
                        if (shot.requestedZoom < 1f) oursAtSwitch = ours
                    }
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "Out of memory analysing $tag", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Analysis of $tag failed", e)
                    json.put("analysisError", e.toString())
                }
                shotsJson.put(json)
            }

            val root = JSONObject()
                .put("device", DiagnosticsExporter.deviceJson())
                .put("logicalId", logicalId)
                .put("mainId", main.id)
                .put("ultraWideId", ultraWide.id)
                .put("withPhysicalStreams", outcome.withPhysicalStreams)
                .put("log", JSONArray(outcome.log))
                .put("logicalSensorOrientation", outcome.sensorOrientation)
                .put("physicalSensorOrientations", JSONObject(outcome.physicalOrientations.mapValues { it.value }))
                .put("logicalJpegSize", outcome.logicalJpegSize.toString())
                .put("physicalJpegSizes", JSONObject(outcome.physicalJpegSizes.mapValues { it.value.toString() }))
                .put("shots", shotsJson)
                .put("cameras", DiagnosticsExporter.cameraInfo(context).optJSONArray("cameras"))
            folder.writeJson("hal_probe.json", root)

            val header = if (outcome.withPhysicalStreams) {
                "HAL-Test mit Roh-Streams beider Linsen"
            } else {
                "HAL-Test nur mit logischem Stream (Gerät erlaubt keine Roh-Streams parallel)"
            }
            val body = lines.takeLast(4).joinToString("\n").ifEmpty { "Keine auswertbaren Bildpaare." }
            Log.d(TAG, "$header\n$body (ours at switch: $oursAtSwitch)")
            Summary("$header\n$body\nGespeichert in ${folder.displayPath}", folder.displayPath)
        }
    }

    /** Returns a one line summary and our main -> ultra wide estimate, or null. */
    private fun analyzeShot(
        aligner: WiggleAligner,
        shot: HalZoomProbe.Shot,
        outcome: HalZoomProbe.Outcome,
        main: CameraLensDetails,
        ultraWide: CameraLensDetails,
        json: JSONObject
    ): Pair<String, Similarity?>? {
        val zoom = (shot.logical?.zoomRatio ?: shot.requestedZoom).toDouble()
        val logicalBitmap = shot.logicalJpeg?.let { CameraImages.fromJpeg(it, outcome.sensorOrientation) } ?: return null
        val mainBytes = shot.physicalJpegs[main.id]
        val uwBytes = shot.physicalJpegs[ultraWide.id]
        val bitmaps = mutableListOf<Bitmap>(logicalBitmap)
        try {
            if (mainBytes == null || uwBytes == null) return null
            val rotM = outcome.physicalOrientations[main.id] ?: outcome.sensorOrientation
            val rotU = outcome.physicalOrientations[ultraWide.id] ?: outcome.sensorOrientation
            val mainBitmap = CameraImages.fromJpeg(mainBytes, rotM)?.also { bitmaps += it } ?: return null
            val uwBitmap = CameraImages.fromJpeg(uwBytes, rotU)?.also { bitmaps += it } ?: return null

            val mainFrame = CapturedFrame(main, mainBitmap, rotM, 1f, shot.physical[main.id])
            val uwFrame = CapturedFrame(ultraWide, uwBitmap, rotU, 1f, shot.physical[ultraWide.id])
            val prior = aligner.metadataPriorFor(mainFrame, uwFrame)
            val ours = aligner.measurePair(mainBitmap, uwBitmap, prior) ?: return null
            json.put("ours", DiagnosticsExporter.similarityJson(ours.global).put("confidence", ours.confidence))
            json.put("metadataPrior", prior?.let(DiagnosticsExporter::similarityJson) ?: JSONObject.NULL)

            val wm = mainBitmap.width.toDouble()
            val hm = mainBitmap.height.toDouble()
            val wl = logicalBitmap.width.toDouble()
            val hl = logicalBitmap.height.toDouble()
            val uwActive = shot.logical?.activePhysicalId?.let { it == ultraWide.id } ?: (zoom < 1.0)
            json.put("ultraWideActive", uwActive)

            if (uwActive) {
                // One logical pixel at zoom z covers (wm / wl) / z main pixels.
                val logicalToMain = Similarity.of(wm / wl / zoom, 0.0, 0.0, 0.0).anchoredAt(wl / 2, hl / 2, wm / 2, hm / 2)
                val halPrior = ours.global.compose(logicalToMain)
                val hal = aligner.measurePair(logicalBitmap, uwBitmap, halPrior) ?: return null
                // HAL transform at 1.0x expressed in the main frame's grid.
                val mainToLogicalAt1 = Similarity.of(wl / wm * zoom, 0.0, 0.0, 0.0).anchoredAt(wm / 2, hm / 2, wl / 2, hl / 2)
                val halInMain = hal.global.compose(mainToLogicalAt1)
                val deviation = halInMain.maxCornerDeviation(ours.global, wm, hm) * 4000.0 / wm
                val zoomOurs = uwBitmap.width / (wm * ours.global.scale)
                val zoomHal = uwBitmap.width / (wm * halInMain.scale)
                json.put("halLogicalToUltraWide", DiagnosticsExporter.similarityJson(hal.global).put("confidence", hal.confidence))
                json.put("halMainToUltraWideAt1x", DiagnosticsExporter.similarityJson(halInMain))
                json.put("deviationPx4k", deviation)
                json.put("equivalentZoomOurs", zoomOurs)
                json.put("equivalentZoomHal", zoomHal)
                val line = String.format(
                    Locale.GERMANY,
                    "@%.2f×: HAL-Zoom %.3f× / unserer %.3f×, Rot %.2f°/%.2f°, Abweichung %.1f px (4K)",
                    zoom, zoomHal, zoomOurs, halInMain.rotationDegrees, ours.global.rotationDegrees, deviation
                )
                return line to ours.global
            } else {
                // Main lens active: how does the logical frame relate to the raw main stream?
                val mainToLogical = Similarity.of(wl / wm / zoom, 0.0, 0.0, 0.0).anchoredAt(wm / 2, hm / 2, wl / 2, hl / 2)
                val ml = aligner.measurePair(mainBitmap, logicalBitmap, mainToLogical, fallbackScale = wl / wm) ?: return null
                val residual = ml.global.maxCornerDeviation(mainToLogical, wm, hm) * 4000.0 / wm
                json.put("mainToLogical", DiagnosticsExporter.similarityJson(ml.global).put("confidence", ml.confidence))
                json.put("logicalResidualPx4k", residual)
                return String.format(Locale.GERMANY, "@%.2f×: Hauptlinse aktiv, Rest-Versatz %.1f px (4K)", zoom, residual) to null
            }
        } finally {
            bitmaps.forEach { it.recycle() }
        }
    }
}
