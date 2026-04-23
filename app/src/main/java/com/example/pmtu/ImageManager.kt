package com.example.pmtu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class ImageManager(private val context: Context) {

    suspend fun downloadImage(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val bitmap = BitmapFactory.decodeStream(URL(url).openStream())
            if (bitmap != null) {
                saveBitmapToCache(url, bitmap)
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    fun getPokemonBitmap(url: String): Bitmap? {
        val id = url.substringAfterLast("/").replace(".png", "")
        val isSprite = url.contains("icon")
        val folders = if (isSprite) listOf("sprites") else listOf("art")
        
        for (folder in folders) {
            try {
                context.assets.open("$folder/$id.png").use { inputStream ->
                    return BitmapFactory.decodeStream(inputStream)
                }
            } catch (e: Exception) {
                // Not in assets
            }
        }

        val file = getCacheFile(url)
        return if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)
        } else {
            null
        }
    }

    fun saveBitmapToCache(url: String, bitmap: Bitmap) {
        try {
            val file = getCacheFile(url)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getCacheFile(url: String): File {
        val dir = File(context.filesDir, "pokemon_images")
        if (!dir.exists()) dir.mkdirs()
        val fileName = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return File(dir, fileName)
    }

    fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
    }
}
