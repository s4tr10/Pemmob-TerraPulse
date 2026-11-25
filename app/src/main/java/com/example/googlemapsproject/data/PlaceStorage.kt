package com.example.googlemapsproject.data

import android.content.Context
import com.example.googlemapsproject.model.MapLayerType
import com.example.googlemapsproject.model.PlaceItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.abs

interface PlaceStore {
    fun getFavorites(): List<PlaceItem>
    fun toggleFavorite(item: PlaceItem): Pair<List<PlaceItem>, Boolean>
    fun isFavorite(item: PlaceItem): Boolean
    fun getRecents(): List<PlaceItem>
    fun addRecent(item: PlaceItem): List<PlaceItem>
    fun clearRecents()
    fun getSavedLayer(): MapLayerType
    fun saveLayer(layer: MapLayerType)
}

class InMemoryPlaceStore : PlaceStore {
    private var favorites: List<PlaceItem> = emptyList()
    private var recents: List<PlaceItem> = emptyList()
    private var layer: MapLayerType = MapLayerType.STREET

    override fun getFavorites(): List<PlaceItem> = favorites
    override fun toggleFavorite(item: PlaceItem): Pair<List<PlaceItem>, Boolean> {
        val (updated, added) = PlaceListSerializer.toggleFavorite(favorites, item)
        favorites = updated
        return updated to added
    }
    override fun isFavorite(item: PlaceItem): Boolean =
        favorites.any { PlaceListSerializer.isSamePlace(it, item) }
    override fun getRecents(): List<PlaceItem> = recents
    override fun addRecent(item: PlaceItem): List<PlaceItem> {
        recents = PlaceListSerializer.addRecent(recents, item)
        return recents
    }
    override fun clearRecents() { recents = emptyList() }
    override fun getSavedLayer(): MapLayerType = layer
    override fun saveLayer(layer: MapLayerType) { this.layer = layer }
}

object PlaceListSerializer {

    private val gson = Gson()
    private val listType = object : TypeToken<List<PlaceItem>>() {}.type

    fun toJson(items: List<PlaceItem>): String {
        return gson.toJson(items)
    }

    fun fromJson(json: String?): List<PlaceItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val parsed: List<PlaceItem>? = gson.fromJson(json, listType)
            parsed?.filter { it.name.isNotBlank() } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addRecent(
        existing: List<PlaceItem>,
        item: PlaceItem,
        maxSize: Int = 10
    ): List<PlaceItem> {
        val filtered = existing.filterNot { isSamePlace(it, item) }
        return (listOf(item) + filtered).take(maxSize)
    }

    fun toggleFavorite(
        existing: List<PlaceItem>,
        item: PlaceItem
    ): Pair<List<PlaceItem>, Boolean> {
        val alreadyFav = existing.any { isSamePlace(it, item) }
        return if (alreadyFav) {
            existing.filterNot { isSamePlace(it, item) } to false
        } else {
            (listOf(item) + existing) to true
        }
    }

    fun isSamePlace(a: PlaceItem, b: PlaceItem): Boolean {
        if (a.id == b.id && a.id.isNotBlank()) return true
        return abs(a.location.lat - b.location.lat) < 0.0001 &&
            abs(a.location.lon - b.location.lon) < 0.0001
    }
}

class PlaceStorage(context: Context) : PlaceStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getFavorites(): List<PlaceItem> {
        return PlaceListSerializer.fromJson(prefs.getString(KEY_FAVORITES, null))
    }

    override fun toggleFavorite(item: PlaceItem): Pair<List<PlaceItem>, Boolean> {
        val (updated, isFav) = PlaceListSerializer.toggleFavorite(getFavorites(), item)
        prefs.edit().putString(KEY_FAVORITES, PlaceListSerializer.toJson(updated)).apply()
        return updated to isFav
    }

    override fun isFavorite(item: PlaceItem): Boolean {
        return getFavorites().any { PlaceListSerializer.isSamePlace(it, item) }
    }

    override fun getRecents(): List<PlaceItem> {
        return PlaceListSerializer.fromJson(prefs.getString(KEY_RECENTS, null))
    }

    override fun addRecent(item: PlaceItem): List<PlaceItem> {
        val updated = PlaceListSerializer.addRecent(getRecents(), item)
        prefs.edit().putString(KEY_RECENTS, PlaceListSerializer.toJson(updated)).apply()
        return updated
    }

    override fun clearRecents() {
        prefs.edit().remove(KEY_RECENTS).apply()
    }

    override fun getSavedLayer(): MapLayerType {
        val raw = prefs.getString(KEY_LAYER, MapLayerType.STREET.name)
        return try {
            MapLayerType.valueOf(raw ?: MapLayerType.STREET.name)
        } catch (_: Exception) {
            MapLayerType.STREET
        }
    }

    override fun saveLayer(layer: MapLayerType) {
        prefs.edit().putString(KEY_LAYER, layer.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "terrapulse_places"
        private const val KEY_FAVORITES = "favorites_json"
        private const val KEY_RECENTS = "recents_json"
        private const val KEY_LAYER = "active_layer"
    }
}
