package com.sam.openclone.clone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads launcher icons off the main thread, rasterised to the size they will
 * actually be drawn at.
 *
 * Two things here keep scrolling smooth. Icons are fetched lazily per visible
 * row instead of up front, because `loadIcon` opens the target app's resources
 * and doing that for a few hundred apps at once would stall the first frame.
 * And each icon is rasterised once at list size rather than kept as a
 * `Drawable` that re-renders its vector on every frame.
 */
internal object IconLoader {

    // Icons are the only real memory this app holds; an eighth of the heap is
    // the conventional budget and covers far more rows than fit on screen.
    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(16L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.height * value.width * 4
    }

    // Decoding is CPU- and IO-bound; a handful of workers saturates it without
    // starving the rest of the app of IO threads.
    private val dispatcher = Dispatchers.IO.limitedParallelism(4)

    fun cached(packageName: String): ImageBitmap? = cache.get(packageName)

    suspend fun load(context: Context, packageName: String, sizePx: Int): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return withContext(dispatcher) {
            cache.get(packageName)?.let { return@withContext it }
            val drawable = runCatching {
                context.packageManager.getApplicationIcon(packageName)
            }.getOrNull() ?: return@withContext null

            val bitmap = createBitmap(drawable, sizePx)
            cache.put(packageName, bitmap)
            bitmap
        }
    }

    private fun createBitmap(
        drawable: android.graphics.drawable.Drawable,
        sizePx: Int,
    ): ImageBitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap.asImageBitmap()
    }
}
