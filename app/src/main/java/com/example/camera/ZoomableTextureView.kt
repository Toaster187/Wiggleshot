package com.example.camera

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * TextureView that keeps the Camera2 preview centre-cropped and undistorted and applies a
 * purely visual zoom on top.
 */
class ZoomableTextureView(context: Context) : TextureView(context) {

    var currentZoom: Float = 1f
        private set

    var sensorOrientation: Int = 90
        set(value) {
            field = value
            applyTransform()
        }

    var bufferWidth: Float = 1440f
        set(value) {
            field = value
            applyTransform()
        }

    var bufferHeight: Float = 1080f
        set(value) {
            field = value
            applyTransform()
        }

    private var lastWidth = 0f
    private var lastHeight = 0f

    private val layoutListener = OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        lastWidth = (right - left).toFloat()
        lastHeight = (bottom - top).toFloat()
        applyTransform()
    }

    init {
        addOnLayoutChangeListener(layoutListener)
    }

    fun updateZoom(newZoom: Float) {
        if (newZoom == currentZoom) return
        currentZoom = newZoom
        applyTransform()
    }

    fun cleanup() {
        removeOnLayoutChangeListener(layoutListener)
        surfaceTextureListener = null
    }

    private fun applyTransform() {
        if (lastWidth <= 0f || lastHeight <= 0f) return
        setTransform(
            previewTransform(
                viewWidth = lastWidth,
                viewHeight = lastHeight,
                zoom = currentZoom,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight
            )
        )
    }
}

/**
 * Suspends until the view's [SurfaceTexture] exists, then sizes its buffer.
 *
 * Replaces the nested `SurfaceTextureListener` callbacks of the previous implementation:
 * those only registered the second listener from inside the first one, so whenever the
 * first surface was already available the second listener was never installed and the
 * camera silently never started — the main reason the shutter was dead after a cold start.
 */
suspend fun ZoomableTextureView.awaitSurfaceTexture(
    bufferWidth: Int,
    bufferHeight: Int,
    timeoutMs: Long
): SurfaceTexture? {
    val texture = withTimeoutOrNull(timeoutMs) {
        surfaceTexture ?: suspendCancellableCoroutine { continuation ->
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    surfaceTextureListener = null
                    if (continuation.isActive) continuation.resume(texture)
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
            continuation.invokeOnCancellation { surfaceTextureListener = null }
            // The surface may have arrived between the check above and the listener being set.
            val existing = surfaceTexture
            if (existing != null && continuation.isActive) {
                surfaceTextureListener = null
                continuation.resume(existing)
            }
        }
    } ?: return null

    texture.setDefaultBufferSize(bufferWidth, bufferHeight)
    return texture
}

/**
 * Builds the centre-crop matrix for a Camera2 preview buffer.
 *
 * The SurfaceTexture already rotates the stream to the device's native portrait
 * orientation, so no extra rotation is applied here — only the buffer's width and height
 * are swapped before scaling.
 */
internal fun previewTransform(
    viewWidth: Float,
    viewHeight: Float,
    zoom: Float,
    bufferWidth: Float,
    bufferHeight: Float
): Matrix {
    val matrix = Matrix()
    if (viewWidth <= 0f || viewHeight <= 0f) return matrix

    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f

    val portraitWidth = bufferHeight
    val portraitHeight = bufferWidth

    val scaleFill = maxOf(viewWidth / portraitWidth, viewHeight / portraitHeight)
    matrix.postScale(
        scaleFill * (portraitWidth / viewWidth),
        scaleFill * (portraitHeight / viewHeight),
        centerX,
        centerY
    )
    matrix.postScale(zoom, zoom, centerX, centerY)
    return matrix
}
