package com.vinmusic.data

import android.content.Context
import android.util.LruCache
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CachedArtistData(
    val channelId: String = "",
    val name: String = "",
    val avatarUrl: String = "",
    val bannerUrl: String = "",
    val subscriberCount: String = "",
    val bio: String = "",
    val isVerified: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

object ArtistDataCache {
    private const val PREFS_NAME = "vin_artist_data_cache"
    private const val TTL_MS = 24 * 60 * 60 * 1000L // 24 Hours disk TTL
    private val memCache = LruCache<String, CachedArtistData>(150)
    private val gson = Gson()
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun normalizeKey(nameOrId: String): String =
        nameOrId.trim().lowercase().replace(Regex("[^a-z0-9]"), "")

    fun get(nameOrId: String): CachedArtistData? {
        val key = normalizeKey(nameOrId)
        if (key.isBlank()) return null

        // 1. In-memory check (0ms)
        val mem = memCache.get(key)
        if (mem != null && System.currentTimeMillis() - mem.timestamp < TTL_MS) {
            return mem
        }

        // 2. Disk check
        val ctx = appContext ?: return null
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(key, null) ?: return null
        return try {
            val data = gson.fromJson(json, CachedArtistData::class.java)
            if (data != null && System.currentTimeMillis() - data.timestamp < TTL_MS) {
                memCache.put(key, data)
                data
            } else null
        } catch (_: Exception) { null }
    }

    suspend fun put(
        nameOrId: String,
        data: CachedArtistData
    ) = withContext(Dispatchers.IO) {
        val key = normalizeKey(nameOrId)
        if (key.isBlank()) return@withContext

        val enriched = if (data.timestamp == 0L) data.copy(timestamp = System.currentTimeMillis()) else data
        memCache.put(key, enriched)

        if (data.name.isNotBlank() && normalizeKey(data.name) != key) {
            memCache.put(normalizeKey(data.name), enriched)
        }
        if (data.channelId.isNotBlank() && normalizeKey(data.channelId) != key) {
            memCache.put(normalizeKey(data.channelId), enriched)
        }

        val ctx = appContext ?: return@withContext
        try {
            val json = gson.toJson(enriched)
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit().putString(key, json)
            if (data.name.isNotBlank()) editor.putString(normalizeKey(data.name), json)
            if (data.channelId.isNotBlank()) editor.putString(normalizeKey(data.channelId), json)
            editor.apply()
        } catch (_: Exception) {}
    }
}
