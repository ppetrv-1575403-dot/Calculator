package com.google.android.calculator.utils

/**
 * Стили для векторных карт.
 * Упрощённый стиль для оффлайн-карт в формате MapLibre/Mapbox Style Specification.
 */
object MapStyleUtils {

    /**
     * Базовый стиль для векторных тайлов.
     */
    fun getBasicStyleJson(tilesUri: String): String {
        return """
        {
          "version": 8,
          "name": "Basic Offline Style",
          "sources": {
            "openmaptiles": {
              "type": "vector",
              "url": "$tilesUri"
            }
          },
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": {
                "background-color": "#e8e0d8"
              }
            },
            {
              "id": "water",
              "type": "fill",
              "source": "openmaptiles",
              "source-layer": "water",
              "paint": {
                "fill-color": "#a0cfdf"
              }
            },
            {
              "id": "landuse-residential",
              "type": "fill",
              "source": "openmaptiles",
              "source-layer": "landuse",
              "filter": ["==", "class", "residential"],
              "paint": {
                "fill-color": "#f2eae2"
              }
            },
            {
              "id": "landuse-park",
              "type": "fill",
              "source": "openmaptiles",
              "source-layer": "landcover",
              "filter": ["==", "class", "grass"],
              "paint": {
                "fill-color": "#cdebb0"
              }
            },
            {
              "id": "roads",
              "type": "line",
              "source": "openmaptiles",
              "source-layer": "transportation",
              "paint": {
                "line-color": "#ffffff",
                "line-width": 1.5
              }
            },
            {
              "id": "roads-major",
              "type": "line",
              "source": "openmaptiles",
              "source-layer": "transportation",
              "filter": ["in", "class", "motorway", "trunk", "primary"],
              "paint": {
                "line-color": "#f8c46f",
                "line-width": 2.5
              }
            },
            {
              "id": "buildings",
              "type": "fill",
              "source": "openmaptiles",
              "source-layer": "building",
              "paint": {
                "fill-color": "#d5cdc3",
                "fill-outline-color": "#b8b0a6"
              }
            },
            {
              "id": "place-labels",
              "type": "symbol",
              "source": "openmaptiles",
              "source-layer": "place",
              "layout": {
                "text-field": "{name}",
                "text-font": ["Noto Sans Regular"],
                "text-size": 14
              },
              "paint": {
                "text-color": "#333333"
              }
            }
          ]
        }
        """.trimIndent()
    }
}