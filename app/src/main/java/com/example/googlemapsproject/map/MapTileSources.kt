package com.example.googlemapsproject.map

import com.example.googlemapsproject.model.MapLayerType
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

object MapTileSources {

    private val esriStreet = object : OnlineTileSourceBase(
        "EsriWorldStreetMap",
        0,
        19,
        256,
        ".png",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/")
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            return "${baseUrl}${MapTileIndex.getZoom(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}"
        }
    }

    private val esriSatellite = object : OnlineTileSourceBase(
        "EsriWorldImagery",
        0,
        19,
        256,
        ".png",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            return "${baseUrl}${MapTileIndex.getZoom(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}"
        }
    }

    private val cartoDark = object : OnlineTileSourceBase(
        "CartoDBDarkMatter",
        0,
        19,
        256,
        ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/dark_all/",
            "https://b.basemaps.cartocdn.com/dark_all/",
            "https://c.basemaps.cartocdn.com/dark_all/"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            return "${baseUrl}${MapTileIndex.getZoom(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}.png"
        }
    }

    private val openTopoMap = object : OnlineTileSourceBase(
        "OpenTopoMap",
        0,
        17,
        256,
        ".png",
        arrayOf(
            "https://a.tile.opentopomap.org/",
            "https://b.tile.opentopomap.org/",
            "https://c.tile.opentopomap.org/"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            return "${baseUrl}${MapTileIndex.getZoom(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}.png"
        }
    }

    fun tileSourceFor(layer: MapLayerType): OnlineTileSourceBase = when (layer) {
        MapLayerType.STREET -> esriStreet
        MapLayerType.SATELLITE -> esriSatellite
        MapLayerType.DARK -> cartoDark
        MapLayerType.TOPO -> openTopoMap
    }
}
