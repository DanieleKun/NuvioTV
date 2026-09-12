package com.nuvio.tv.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.repository.CalendarReleaseRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only stress harness for the calendar: fills the local library with real Cinemeta titles
 * so the release resolver can be measured against a 400+ item library.
 *
 * ```
 * adb shell am broadcast -n <appId>/com.nuvio.tv.debug.CalendarSeedReceiver \
 *     -a com.nuvio.tv.debug.SEED_LIBRARY --ei count 400
 * adb shell am broadcast -n <appId>/com.nuvio.tv.debug.CalendarSeedReceiver \
 *     -a com.nuvio.tv.debug.CLEAR_SEED
 * adb shell am broadcast -n <appId>/com.nuvio.tv.debug.CalendarSeedReceiver \
 *     -a com.nuvio.tv.debug.CLEAR_CALENDAR_CACHE
 * ```
 */
class CalendarSeedReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SeedEntryPoint {
        fun libraryPreferences(): LibraryPreferences
        fun addonApi(): AddonApi
        fun calendarReleaseRepository(): CalendarReleaseRepository
        fun okHttpClient(): OkHttpClient
        @Named("addonPermissive") fun addonOkHttpClient(): OkHttpClient
        fun tmdbSettings(): TmdbSettingsDataStore
    }

    companion object {
        private const val TAG = "CalendarSeed"
        private const val ACTION_SEED = "com.nuvio.tv.debug.SEED_LIBRARY"
        private const val ACTION_CLEAR = "com.nuvio.tv.debug.CLEAR_SEED"
        private const val ACTION_CLEAR_CACHE = "com.nuvio.tv.debug.CLEAR_CALENDAR_CACHE"
        /** Evicts the OkHttp disk caches so the next pass measures real network traffic. */
        private const val ACTION_CLEAR_HTTP_CACHE = "com.nuvio.tv.debug.CLEAR_HTTP_CACHE"
        /** Flips the TMDB integration on/off (`--ez enabled true|false`) to test digital release dates. */
        private const val ACTION_SET_TMDB = "com.nuvio.tv.debug.SET_TMDB"
        private const val EXTRA_ENABLED = "enabled"
        private const val EXTRA_COUNT = "count"
        private const val DEFAULT_COUNT = 400
        private const val PREFS = "calendar_seed"
        private const val KEY_SEEDED = "seeded_keys"
        private const val CINEMETA = "https://v3-cinemeta.strem.io"
        private const val PAGE_SIZE = 100
        private const val SERIES_SHARE = 0.7
        /** One in five items is stored without its addon, exercising the all-addons lookup path. */
        private const val UNKNOWN_ADDON_EVERY = 5
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, SeedEntryPoint::class.java)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SEED -> seed(context, entryPoint, intent.getIntExtra(EXTRA_COUNT, DEFAULT_COUNT))
                    ACTION_CLEAR -> clear(context, entryPoint)
                    ACTION_CLEAR_CACHE -> {
                        entryPoint.calendarReleaseRepository().clearCache()
                        Log.i(TAG, "calendar cache cleared")
                    }
                    ACTION_CLEAR_HTTP_CACHE -> {
                        entryPoint.okHttpClient().cache?.evictAll()
                        entryPoint.addonOkHttpClient().cache?.evictAll()
                        Log.i(TAG, "http caches evicted")
                    }
                    ACTION_SET_TMDB -> {
                        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                        entryPoint.tmdbSettings().setEnabled(enabled)
                        Log.i(TAG, "tmdb enabled=$enabled")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "seed action failed: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun seed(context: Context, entryPoint: SeedEntryPoint, count: Int) {
        val seriesCount = (count * SERIES_SHARE).toInt()
        val items = fetchCatalog(entryPoint.addonApi(), "series", seriesCount) +
            fetchCatalog(entryPoint.addonApi(), "movie", count - seriesCount)
        val library = entryPoint.libraryPreferences()
        val seededKeys = HashSet<String>()
        items.forEachIndexed { index, item ->
            val stored = if (index % UNKNOWN_ADDON_EVERY == 0) item.copy(addonBaseUrl = null) else item
            library.addItem(stored)
            seededKeys += "${stored.type}:${stored.id}"
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_SEEDED, seededKeys + previouslySeeded(context))
            .apply()
        Log.i(TAG, "seeded ${items.size} items (${seriesCount} series) into the local library")
    }

    private suspend fun clear(context: Context, entryPoint: SeedEntryPoint) {
        val library = entryPoint.libraryPreferences()
        val keys = previouslySeeded(context)
        keys.forEach { key ->
            val type = key.substringBefore(':')
            val id = key.substringAfter(':')
            library.removeItem(itemId = id, itemType = type)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SEEDED).apply()
        Log.i(TAG, "removed ${keys.size} seeded items")
    }

    private fun previouslySeeded(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_SEEDED, emptySet()).orEmpty()

    private suspend fun fetchCatalog(api: AddonApi, type: String, wanted: Int): List<SavedLibraryItem> {
        val collected = ArrayList<SavedLibraryItem>()
        var skip = 0
        while (collected.size < wanted) {
            val url = if (skip == 0) "$CINEMETA/catalog/$type/top.json" else "$CINEMETA/catalog/$type/top/skip=$skip.json"
            val response = api.getCatalog(url)
            val metas = response.body()?.metas.orEmpty().filterNotNull()
            if (!response.isSuccessful || metas.isEmpty()) break
            metas.forEach { meta ->
                val id = meta.id ?: return@forEach
                if (collected.size >= wanted) return@forEach
                collected += SavedLibraryItem(
                    id = id,
                    type = meta.type ?: type,
                    name = meta.name ?: id,
                    poster = meta.poster,
                    posterShape = PosterShape.fromString(meta.posterShape),
                    background = meta.background,
                    description = meta.description,
                    releaseInfo = meta.releaseInfo,
                    imdbRating = meta.imdbRating?.toFloatOrNull(),
                    genres = meta.genres.orEmpty(),
                    addonBaseUrl = CINEMETA
                )
            }
            skip += PAGE_SIZE
        }
        return collected
    }
}
