package com.example.data

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import java.io.File

@Entity(tableName = "wiggle_captures")
data class WiggleCapture(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imagePaths: String, // Comma-separated list of image paths
    val timestamp: Long = System.currentTimeMillis(),
    val prompt: String = "Hier sind Bilder aus leicht unterschiedlichen Winkeln. Bitte erstelle ein genaues Zwischenbild, das den exakten Übergang zwischen ihnen darstellt, um einen stereoskopischen 3D-Wiggle-Effekt zu erzeugen."
) {
    fun getImagePathList(): List<String> {
        if (imagePaths.isEmpty()) return emptyList()
        return imagePaths.split(",").map { it.trim() }
    }

    /** Returns a File for the first captured image (for Coil/image loading) */
    fun getThumbnailFile(): File? {
        val path = getImagePathList().firstOrNull() ?: return null
        val f = File(path)
        return if (f.exists()) f else null
    }

    // Backward compatibility for existing code paths
    @get:Ignore
    val imageAPath: String
        get() = getImagePathList().firstOrNull() ?: ""

    @get:Ignore
    val imageBPath: String
        get() = getImagePathList().getOrNull(1) ?: ""
}
