package com.openrs.dash.ui.trip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.openrs.dash.data.DrivePointEntity
import java.io.File
import java.io.FileOutputStream

/**
 * Generates and caches small route preview bitmaps from GPS points.
 * Used for drive history list thumbnails in the TRIP tab.
 */
object RouteThumbnail {
    private const val WIDTH = 120
    private const val HEIGHT = 80
    private const val PADDING = 8f

    /**
     * Generate a route thumbnail bitmap from GPS points.
     * Returns null if fewer than 2 points have valid coordinates.
     */
    fun generate(points: List<DrivePointEntity>, lineColor: Int = 0xFF0091EA.toInt()): Bitmap? {
        // Filter to points with valid GPS coordinates
        val validPoints = points.filter { it.lat != 0.0 || it.lng != 0.0 }
        if (validPoints.size < 2) return null

        // Find bounding box
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLng = Double.MAX_VALUE
        var maxLng = -Double.MAX_VALUE
        for (pt in validPoints) {
            if (pt.lat < minLat) minLat = pt.lat
            if (pt.lat > maxLat) maxLat = pt.lat
            if (pt.lng < minLng) minLng = pt.lng
            if (pt.lng > maxLng) maxLng = pt.lng
        }

        val latRange = maxLat - minLat
        val lngRange = maxLng - minLng

        // Drawable area after padding
        val drawW = WIDTH - 2 * PADDING
        val drawH = HEIGHT - 2 * PADDING

        // Compute scale to fit route into drawable area, preserving aspect ratio.
        // If the route is a single point or a straight line along one axis,
        // use a small default range so we don't divide by zero.
        val effectiveLatRange = if (latRange < 1e-9) 0.0001 else latRange
        val effectiveLngRange = if (lngRange < 1e-9) 0.0001 else lngRange

        val scaleX = drawW / effectiveLngRange
        val scaleY = drawH / effectiveLatRange
        val scale = minOf(scaleX, scaleY)

        // Center the route within the drawable area
        val scaledW = effectiveLngRange * scale
        val scaledH = effectiveLatRange * scale
        val offsetX = PADDING + (drawW - scaledW) / 2f
        val offsetY = PADDING + (drawH - scaledH) / 2f

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Background is transparent (ARGB_8888 default)

        val paint = Paint().apply {
            color = lineColor
            strokeWidth = 2f
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // Project lat/lng to pixel coords and draw polyline.
        // Latitude is inverted (higher lat = lower y pixel).
        var prevX = Float.NaN
        var prevY = Float.NaN
        for (pt in validPoints) {
            val px = (offsetX + (pt.lng - minLng) * scale).toFloat()
            val py = (offsetY + (maxLat - pt.lat) * scale).toFloat()
            if (!prevX.isNaN()) {
                canvas.drawLine(prevX, prevY, px, py, paint)
            }
            prevX = px
            prevY = py
        }

        return bitmap
    }

    /**
     * Generate and cache a thumbnail PNG for a drive.
     * Returns the cached file, or null if generation failed.
     */
    fun generateAndCache(context: Context, driveId: Long, points: List<DrivePointEntity>): File? {
        val dir = File(context.filesDir, "route_thumbs")
        dir.mkdirs()
        val file = File(dir, "drive_$driveId.png")
        if (file.exists()) return file

        val bitmap = generate(points) ?: return null
        FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 80, it)
        }
        bitmap.recycle()
        return file
    }

    /** Get cached thumbnail file, or null if not cached. */
    fun getCached(context: Context, driveId: Long): File? {
        val file = File(context.filesDir, "route_thumbs/drive_$driveId.png")
        return if (file.exists()) file else null
    }
}
