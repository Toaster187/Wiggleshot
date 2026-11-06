package com.example.align

/**
 * Per frame capture result values that matter for the alignment and for diagnostics. For a
 * dual physical capture these come from the physical camera's own capture result, so they
 * describe exactly the frame they belong to (focus breathing changes the focal length a bit
 * with every focus distance).
 */
data class FrameMetadata(
    val cameraId: String,
    val intrinsics: FloatArray? = null,
    val distortion: FloatArray? = null,
    val cropRegion: LensGeometry.IntRect? = null,
    val focusDistanceDiopters: Float? = null,
    val focalLengthMm: Float? = null,
    val exposureTimeNs: Long? = null,
    val sensitivity: Int? = null,
    val distortionCorrectionMode: Int? = null,
    val timestampNs: Long? = null,
    val activePhysicalId: String? = null,
    val zoomRatio: Float? = null
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
