package com.example.groceriesapp.feature.recipes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Decodes a picked/captured photo with its EXIF rotation applied; null when unreadable. */
internal fun decodeOrientedBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
    val resolver = context.contentResolver
    val bitmap = resolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) ?: return null
    val orientation = resolver.openInputStream(uri)?.use { stream ->
        ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } ?: ExifInterface.ORIENTATION_NORMAL
    bitmap.withExifOrientation(orientation)
}.getOrNull()

internal fun decodeOrientedBitmap(file: File): Bitmap? = runCatching {
    val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
    val orientation = ExifInterface(file.path).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    )
    bitmap.withExifOrientation(orientation)
}.getOrNull()

private fun Bitmap.withExifOrientation(orientation: Int): Bitmap {
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> return this
    }
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
