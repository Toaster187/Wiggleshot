package com.example.diagnostics

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.align.FrameMetadata
import com.example.align.LensGeometry
import com.example.align.Similarity
import com.example.align.WiggleAligner
import com.example.camera.RawCapture
import com.example.camera.LensMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes everything needed to analyse the lens matching offline: the camera's factory
 * calibration, raw (unaligned) frames, per frame capture results and the alignment reports.
 *
 * Files go to `Download/Wiggleshot/<folder>` so they can be copied off the phone with the
 * Files app or over USB without root.
 */
object DiagnosticsExporter {
    private const val TAG = "Diagnostics"

    /** A folder in Downloads (API 29+) or in the app's external files dir (older). */
    class Folder(private val context: Context, val name: String) {
        private val legacyDir: File? = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            File(context.getExternalFilesDir(null), "diagnostics/$name").apply { mkdirs() }
        } else {
            null
        }

        val displayPath: String
            get() = legacyDir?.absolutePath ?: "Download/Wiggleshot/$name"

        fun write(fileName: String, mime: String, bytes: ByteArray): Boolean = try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                File(checkNotNull(legacyDir), fileName).writeBytes(bytes)
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Wiggleshot/$name")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("insert returned null")
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not write $name/$fileName", e)
            false
        }

        fun writeJson(fileName: String, json: JSONObject): Boolean =
            write(fileName, "application/json", json.toString(2).toByteArray())
    }

    fun newFolder(context: Context, prefix: String): Folder {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return Folder(context, "${prefix}_$stamp")
    }

    /** Factory calibration of every back camera (logical and physical). */
    fun exportCameraInfo(context: Context): String? {
        val folder = newFolder(context, "camera_info")
        return if (folder.writeJson("camera_info.json", cameraInfo(context))) folder.displayPath else null
    }

    /** Saves one capture's raw frames plus metadata and alignment reports. */
    fun saveCapture(
        context: Context,
        capture: RawCapture,
        reports: List<WiggleAligner.Report>,
        folder: Folder = newFolder(context, "capture"),
        extra: JSONObject? = null
    ): String? {
        val framesJson = JSONArray()
        capture.frames.forEachIndexed { index, frame ->
            val fileName = "frame_${index}_lens_${frame.lens.id}.jpg"
            val saved = folder.write(fileName, "image/jpeg", frame.bytes)
            // Night mode burst: the further frames of this lens.
            val burstFiles = frame.allBytes.drop(1).mapIndexedNotNull { k, bytes ->
                val name = "frame_${index}_lens_${frame.lens.id}_burst${k + 1}.jpg"
                if (folder.write(name, "image/jpeg", bytes)) name else null
            }
            framesJson.put(
                JSONObject()
                    .put("index", index)
                    .put("file", if (saved) fileName else JSONObject.NULL)
                    .put("burstFiles", JSONArray(burstFiles))
                    .put("lensId", frame.lens.id)
                    .put("lensName", frame.lens.name)
                    .put("logicalId", frame.lens.logicalId)
                    .put("rotationDegrees", frame.rotationDegrees)
                    .put("appliedZoom", frame.appliedZoom.toDouble())
                    .put("metadata", frame.metadata?.let(::metadataJson) ?: JSONObject.NULL)
                    .put("note", "file is the sensor-oriented JPEG; rotate clockwise by rotationDegrees to get the upright frame")
            )
        }
        val json = JSONObject()
            .put("device", deviceJson())
            .put("simultaneous", capture.simultaneous)
            .put("timestamp", capture.timestamp)
            .put("frames", framesJson)
            .put("alignment", JSONArray().apply { reports.forEach { put(reportJson(it)) } })
            .put("extra", extra ?: JSONObject.NULL)
            .put("cameras", cameraInfo(context).optJSONArray("cameras"))
        return if (folder.writeJson("capture.json", json)) folder.displayPath else null
    }

    fun deviceJson(): JSONObject = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("device", Build.DEVICE)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("fingerprint", Build.FINGERPRINT)

    fun cameraInfo(context: Context): JSONObject {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameras = JSONArray()
        val pairs = JSONArray()
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        val seen = mutableSetOf<String>()
        for (id in ids) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            cameras.put(characteristicsJson(id, null, chars))
            seen += id
            val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { chars.physicalCameraIds.toList() }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            for (pid in physicalIds) {
                if (pid in seen) continue
                val pChars = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                cameras.put(characteristicsJson(pid, id, pChars))
                seen += pid
            }
            // Metadata prior of every physical pair for a 4:3 portrait 4000x3000 frame.
            for (a in physicalIds) for (b in physicalIds) {
                if (a == b) continue
                val ga = LensMetadata.geometry(manager, a) ?: continue
                val gb = LensMetadata.geometry(manager, b) ?: continue
                val pa = ga.projection(3000, 4000) ?: continue
                val pb = gb.projection(3000, 4000) ?: continue
                pairs.put(
                    JSONObject()
                        .put("logicalId", id)
                        .put("primary", a)
                        .put("secondary", b)
                        .put("priorFor3000x4000", similarityJson(pb.priorFrom(pa)))
                        .put("equivalentZoom", 1.0 / pb.priorFrom(pa).scale)
                        .put("poseRollDegrees", LensGeometry.relativeRollDegrees(ga, gb) ?: JSONObject.NULL)
                        .put("baselineMm", LensGeometry.baselineMm(ga, gb) ?: JSONObject.NULL)
                )
            }
        }
        return JSONObject()
            .put("device", deviceJson())
            .put("cameras", cameras)
            .put("physicalPairs", pairs)
    }

    private fun characteristicsJson(id: String, parent: String?, c: CameraCharacteristics): JSONObject {
        val o = JSONObject().put("id", id).put("parentLogicalId", parent ?: JSONObject.NULL)
        fun put(name: String, value: Any?) {
            o.put(name, toJson(value))
        }
        put("sensorOrientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION))
        put("focalLengths", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS))
        put("apertures", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES))
        put("physicalSizeMm", c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { "${it.width}x${it.height}" })
        put("pixelArraySize", c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.toString())
        put("activeArray", c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.flattenToString())
        put("preCorrectionArray", c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.flattenToString())
        put("intrinsicCalibration", c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION))
        put("poseRotation", c.get(CameraCharacteristics.LENS_POSE_ROTATION))
        put("poseTranslation", c.get(CameraCharacteristics.LENS_POSE_TRANSLATION))
        put("maxDigitalZoom", c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))
        put("hardwareLevel", c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
        put("capabilities", c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            put("distortion", c.get(CameraCharacteristics.LENS_DISTORTION))
            put("poseReference", c.get(CameraCharacteristics.LENS_POSE_REFERENCE))
            put("distortionCorrectionModes", c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES))
            put("logicalSyncType", c.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE))
            put("physicalIds", runCatching { c.physicalCameraIds.toList() }.getOrNull())
            put("physicalRequestKeys", runCatching { c.availablePhysicalCameraRequestKeys?.map { it.name } }.getOrNull())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            put("zoomRatioRange", c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { "${it.lower}..${it.upper}" })
        }
        put(
            "jpegSizes",
            c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
                ?.sortedByDescending { it.width * it.height }
                ?.take(8)
                ?.map { it.toString() }
        )
        return o
    }

    fun metadataJson(m: FrameMetadata): JSONObject = JSONObject()
        .put("cameraId", m.cameraId)
        .put("intrinsics", toJson(m.intrinsics))
        .put("distortion", toJson(m.distortion))
        .put("cropRegion", m.cropRegion?.let { "${it.left},${it.top} ${it.width}x${it.height}" } ?: JSONObject.NULL)
        .put("focusDistanceDiopters", toJson(m.focusDistanceDiopters))
        .put("focalLengthMm", toJson(m.focalLengthMm))
        .put("exposureTimeNs", toJson(m.exposureTimeNs))
        .put("sensitivity", toJson(m.sensitivity))
        .put("distortionCorrectionMode", toJson(m.distortionCorrectionMode))
        .put("timestampNs", toJson(m.timestampNs))
        .put("activePhysicalId", toJson(m.activePhysicalId))
        .put("zoomRatio", toJson(m.zoomRatio))

    fun reportJson(r: WiggleAligner.Report): JSONObject = JSONObject()
        .put("primaryId", r.primaryId)
        .put("lensId", r.lensId)
        .put("source", r.source.name)
        .put("transform", similarityJson(r.transform))
        .put("global", r.global?.let(::similarityJson) ?: JSONObject.NULL)
        .put("prior", r.prior?.let(::similarityJson) ?: JSONObject.NULL)
        .put("priorSource", r.priorSource?.name ?: JSONObject.NULL)
        .put("confidence", toJson(r.confidence))
        .put("pivotShiftPx", r.pivotShiftPx)
        .put("equivalentZoom", r.equivalentZoom)
        .put("colorCorrection", r.colorCorrection)
        .put("elapsedMs", r.elapsedMs)
        .put("metadataRollDegrees", toJson(r.metadataRollDegrees))
        .put("baselineMm", toJson(r.baselineMm))
        .put("primarySize", "${r.primaryWidth}x${r.primaryHeight}")
        .put("secondarySize", "${r.secondaryWidth}x${r.secondaryHeight}")
        .put("uncertain", r.uncertain)
        .put("field", r.field?.let { f ->
            JSONObject()
                .put("base", similarityJson(f.base))
                .put("normal", JSONArray(listOf(f.normalX, f.normalY)))
                .put("coefficients", JSONArray(f.coefficients.toList()))
                .put("primarySize", "${f.primaryWidth.toInt()}x${f.primaryHeight.toInt()}")
                .put("maxOffsetPx", f.maxOffset())
                .put("note", "secondary = base(p) + normal * sum(c_i * basis_i(u, v)); u=(x-w/2)/w, v=(y-h/2)/w; basis 1,u,v,u2,uv,v2,u3,u2v,uv2,v3")
        } ?: JSONObject.NULL)
        .put("rectifiedPatches", r.rectifiedPatches)
        .put("depthPercent", toJson(r.depthPercent))
        .put("depthPivotShiftPx", r.depthPivotShiftPx)
        .put("focusFound", r.focusFound ?: JSONObject.NULL)
        .put("dofMaxBlurPx", toJson(r.dofMaxBlurPx))
        .put("inBetweenFrames", r.inBetweenFrames)
        .put("depthMs", r.depthMs)
        .put("noiseSigma", toJson(r.noiseSigma))
        .put("speckPixels", r.speckPixels)
        .put("denoiseMs", r.denoiseMs)
        .put("rectifyRmsBefore", toJson(r.rectifyRmsBefore))
        .put("rectifyRmsAfter", toJson(r.rectifyRmsAfter))
        .put("attempts", JSONObject(r.attempts))
        .put("summary", r.summary())

    fun similarityJson(t: Similarity): JSONObject = JSONObject()
        .put("a", t.a).put("b", t.b).put("tx", t.tx).put("ty", t.ty)
        .put("scale", t.scale).put("rotationDegrees", t.rotationDegrees)
        .put("note", "maps primary px (x,y) to secondary px: xs = a*x - b*y + tx, ys = b*x + a*y + ty")

    private fun toJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is FloatArray -> JSONArray().apply { value.forEach { put(it.toDouble()) } }
        is IntArray -> JSONArray().apply { value.forEach { put(it) } }
        is List<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
        is Float -> value.toDouble()
        is Double -> if (value.isFinite()) value else JSONObject.NULL
        else -> value
    }
}
