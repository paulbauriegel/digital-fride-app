package com.example.groceriesapp.feature.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a single-photo receipt came from: the system camera, or the photo picker. */
sealed interface ReceiptPhotoSource {
    data class Captured(val file: File) : ReceiptPhotoSource

    data class Picked(val uri: Uri) : ReceiptPhotoSource
}

/** Longest edge kept. Beyond this the extra pixels cost memory without helping OCR. */
private const val MAXIMUM_SIDE = 4_000
private const val JPEG_QUALITY = 92

/**
 * Writes [source] into [target] as the one keyframe of a scan session.
 *
 * The photo is decoded through [ImageDecoder], which applies the EXIF rotation itself — a URI from
 * the photo picker serves a redacted copy of the file, so orientation read back by hand is not
 * dependable. Downscaling here keeps a full-resolution phone photo from having to be held in memory
 * as an Android Bitmap, and the rotation ends up baked into the pixels, which is what the stitcher
 * reads through OpenCV. Returns false when the photo cannot be read at all.
 */
suspend fun writeReceiptPhotoFrame(
    context: Context,
    source: ReceiptPhotoSource,
    target: File,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val bitmap = decodeOrientedBitmap(context, source) ?: return@runCatching false
        try {
            target.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
        } finally {
            bitmap.recycle()
        }
    }.getOrDefault(false)
}

private fun decodeOrientedBitmap(context: Context, source: ReceiptPhotoSource): Bitmap? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        decodeWithImageDecoder(context, source)
    } else {
        decodeWithBitmapFactory(context, source)
    }

@RequiresApi(Build.VERSION_CODES.P)
private fun decodeWithImageDecoder(context: Context, source: ReceiptPhotoSource): Bitmap? {
    val imageSource = when (source) {
        is ReceiptPhotoSource.Captured -> ImageDecoder.createSource(source.file)
        is ReceiptPhotoSource.Picked ->
            ImageDecoder.createSource(context.contentResolver, source.uri)
    }
    return ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
        // Software, not hardware: the bitmap is compressed straight back out to a file.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val sample = sampleSizeFor(info.size.width, info.size.height, MAXIMUM_SIDE)
        if (sample > 1) decoder.setTargetSampleSize(sample)
    }
}

/** API 26 and 27 have no ImageDecoder, so orientation has to be applied by hand there. */
private fun decodeWithBitmapFactory(context: Context, source: ReceiptPhotoSource): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    source.openStream(context)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAXIMUM_SIDE)
    }
    val bitmap = source.openStream(context)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: return null
    val orientation = source.openStream(context)?.use { stream ->
        ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } ?: ExifInterface.ORIENTATION_NORMAL
    val oriented = bitmap.withExifOrientation(orientation)
    if (oriented !== bitmap) bitmap.recycle()
    return oriented
}

private fun ReceiptPhotoSource.openStream(context: Context): InputStream? = when (this) {
    is ReceiptPhotoSource.Captured -> file.takeIf(File::isFile)?.inputStream()
    is ReceiptPhotoSource.Picked -> context.contentResolver.openInputStream(uri)
}

/**
 * The power-of-two subsampling that keeps the longest edge at or below [maximumSide]. Powers of two
 * are what both decoders actually honour, so anything else would silently round down anyway.
 */
internal fun sampleSizeFor(width: Int, height: Int, maximumSide: Int): Int {
    require(width > 0 && height > 0) { "The photo has no pixels." }
    require(maximumSide > 0) { "The size limit must be positive." }
    var sampleSize = 1
    while (maxOf(width, height) / sampleSize > maximumSide) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun Bitmap.withExifOrientation(orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return this
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
