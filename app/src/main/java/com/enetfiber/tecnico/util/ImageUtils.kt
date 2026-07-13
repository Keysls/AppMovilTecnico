package com.enetfiber.tecnico.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import java.io.FileOutputStream

object ImageUtils {

    private fun calcularInSampleSize(opts: BitmapFactory.Options, maxDim: Int): Int {
        val height = opts.outHeight
        val width = opts.outWidth
        var inSampleSize = 1
        if (height > maxDim || width > maxDim) {
            val mitadAltura = height / 2
            val mitadAncho = width / 2
            while ((mitadAltura / inSampleSize) >= maxDim && (mitadAncho / inSampleSize) >= maxDim) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun comprimirAWebP(original: File, maxDim: Int = 1600, calidad: Int = 80): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.absolutePath, bounds)

        val opts = BitmapFactory.Options().apply {
            inSampleSize = calcularInSampleSize(bounds, maxDim)
        }
        val bitmap = BitmapFactory.decodeFile(original.absolutePath, opts)
            ?: return original // si falla la decodificación, sube el original como fallback

        val salida = File(original.parent, original.nameWithoutExtension + ".webp")
        FileOutputStream(salida).use { out ->
            val formato = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                Bitmap.CompressFormat.WEBP_LOSSY
            else
                @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            bitmap.compress(formato, calidad, out)
        }
        bitmap.recycle()
        //original.delete()
        return salida
    }
}