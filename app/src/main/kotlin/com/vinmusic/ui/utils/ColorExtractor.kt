package com.vinmusic.ui.utils

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ColorExtractor {
    data class MusicPalette(
        val gradTop: Color,
        val gradMid: Color,
        val gradBottom: Color,
        val accent: Color
    )

    suspend fun extractColorsFromUrl(context: Context, url: String): MusicPalette {
        return MusicPalette(
            gradTop = Color(0x33C5A880),     // Soft light brown
            gradMid = Color(0x1FC5A880),     // Deep light brown
            gradBottom = Color(0xFF0E0E11),  // Dark charcoal background
            accent = Color(0xFFC5A880)       // Vibrant light brown accent
        )
    }

    fun extractColorsFromBitmap(bitmap: Bitmap): MusicPalette {
        return MusicPalette(
            gradTop = Color(0x33C5A880),     // Soft light brown
            gradMid = Color(0x1FC5A880),     // Deep light brown
            gradBottom = Color(0xFF0E0E11),  // Dark charcoal background
            accent = Color(0xFFC5A880)       // Vibrant light brown accent
        )
    }
}
