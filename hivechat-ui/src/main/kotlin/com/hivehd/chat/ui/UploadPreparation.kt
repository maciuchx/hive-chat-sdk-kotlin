package com.hivehd.chat.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns whatever the picker handed us into something Hive accepts.
 *
 * Hive takes JPEG, PNG, GIF, WebP and PDF up to 5 MB. A phone photo is
 * routinely an 8–12 MB HEIC, which the server refuses on both counts, so every
 * image is re-encoded here as a JPEG no larger than 2048 px on its longest
 * side, upright, and under the limit.
 */
internal object UploadPreparation {

    const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024
    private const val MAX_SIDE = 2048
    private const val START_QUALITY = 80
    private const val MIN_QUALITY = 50

    class Prepared(val bytes: ByteArray, val filename: String, val contentType: String)

    class Unusable(message: String) : Exception(message)

    /** Blocking: call off the main thread. */
    fun prepare(bytes: ByteArray, filename: String, contentType: String): Prepared {
        val type = contentType.lowercase()
        /* A GIF is usually animated, and a JPEG of its first frame is not
           what the customer meant to send. Hive accepts GIFs as they are. */
        val keepAsIs = type == "image/gif" && bytes.size <= MAX_UPLOAD_BYTES
        if (type.startsWith("image/") && !keepAsIs) {
            val jpeg = toJpeg(bytes)
                ?: throw Unusable("That photo couldn't be read. Try a different one.")
            return Prepared(jpeg, jpegName(filename), "image/jpeg")
        }
        if (bytes.size > MAX_UPLOAD_BYTES) {
            throw Unusable("That file is too large to send — the limit is 5 MB.")
        }
        return Prepared(bytes, filename, contentType)
    }

    private fun jpegName(filename: String): String {
        val base = filename.substringBeforeLast('.', filename).ifBlank { "photo" }
        return "$base.jpg"
    }

    private fun toJpeg(bytes: ByteArray): ByteArray? {
        var bitmap = decodeUpright(bytes) ?: return null
        try {
            while (true) {
                for (quality in START_QUALITY downTo MIN_QUALITY step 10) {
                    val out = ByteArrayOutputStream()
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
                    if (out.size() <= MAX_UPLOAD_BYTES) return out.toByteArray()
                }
                /* Still too big at the lowest quality worth sending: shrink
                   and go again. Practically unreachable at 2048 px. */
                val w = (bitmap.width * 0.75f).roundToInt()
                val h = (bitmap.height * 0.75f).roundToInt()
                if (w < 64 || h < 64) return null
                val smaller = Bitmap.createScaledBitmap(bitmap, w, h, true)
                if (smaller !== bitmap) bitmap.recycle()
                bitmap = smaller
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Decoded, scaled to [MAX_SIDE] and rotated per EXIF. */
    private fun decodeUpright(bytes: ByteArray): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            /* ImageDecoder reads HEIC/HEIF and applies the EXIF orientation
               itself. */
            return runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    // Software, so Bitmap.compress can read the pixels.
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = max(info.size.width, info.size.height)
                    if (longest > MAX_SIDE) {
                        val scale = MAX_SIDE.toFloat() / longest
                        decoder.setTargetSize(
                            (info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                }
            }.getOrNull()
        }

        /* Below API 28: BitmapFactory with a power-of-two sample to keep the
           decode itself small, then an exact scale and a manual rotation.
           HEIC is not decodable here and returns null. */
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val longest = max(decoded.width, decoded.height)
        val scale = if (longest > MAX_SIDE) MAX_SIDE.toFloat() / longest else 1f
        val matrix = Matrix().apply {
            if (scale != 1f) postScale(scale, scale)
            applyExif(this, exifOrientation(bytes))
        }
        if (matrix.isIdentity) return decoded
        val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (transformed !== decoded) decoded.recycle()
        return transformed
    }

    private fun exifOrientation(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyExif(matrix: Matrix, orientation: Int) {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
    }
}
