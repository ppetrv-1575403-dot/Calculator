package com.google.android.calculator.utils

object MapStyleUtils {
    /**
     * Генерирует стиль с векторными тайлами.
     * @param tileSourceUrl - источник тайлов. Может быть:
     *   - Онлайн: "https://tiles.openfreemap.org/planet"
     *   - Локальный: "http://localhost:8085/{z}/{x}/{y}.pbf"
     */
    fun getBasicStyleJson(tileSourceUrl: String): String {
        // Определяем, локальный это источник или онлайн
        val isLocalSource = tileSourceUrl.contains("localhost")
        
        return if (isLocalSource) {
            // Локальный источник — используем tiles-шаблон
            """
            {
              "version": 8,
              "name": "Vector Local Style",
              "sources": {
                "protomaps": {
                  "type": "vector",
                  "tiles": ["$tileSourceUrl"],
                  "minzoom": 0,
                  "maxzoom": 15
                }
              },
              "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
              "layers": [
                {"id": "background", "type": "background", "paint": {"background-color": "#e8e0d8"}},
                {"id": "water", "type": "fill", "source": "protomaps", "source-layer": "water", "paint": {"fill-color": "#a0cfdf"}},
                {"id": "landcover-wood", "type": "fill", "source": "protomaps", "source-layer": "landcover", "filter": ["==", "kind", "wood"], "paint": {"fill-color": "#cde0a5"}},
                {"id": "landcover-grass", "type": "fill", "source": "protomaps", "source-layer": "landcover", "filter": ["==", "kind", "grass"], "paint": {"fill-color": "#dce4c4"}},
                {"id": "landuse-residential", "type": "fill", "source": "protomaps", "source-layer": "landuse", "filter": ["==", "kind", "residential"], "paint": {"fill-color": "#f2eae2"}},
                {"id": "roads-case", "type": "line", "source": "protomaps", "source-layer": "roads", "minzoom": 10, "paint": {"line-color": "#c8c8c8", "line-width": 3}},
                {"id": "roads", "type": "line", "source": "protomaps", "source-layer": "roads", "minzoom": 10, "paint": {"line-color": "#ffffff", "line-width": 1.8}},
                {"id": "roads-major", "type": "line", "source": "protomaps", "source-layer": "roads", "filter": ["in", "kind", "highway", "major_road"], "minzoom": 8, "paint": {"line-color": "#f8c46f", "line-width": 3.5}},
                {"id": "trails-path", "type": "line", "source": "protomaps", "source-layer": "roads", "filter": ["==", "kind", "path"], "minzoom": 13, "paint": {"line-color": "#8b5a2b", "line-width": 1.5, "line-dasharray": [2, 2]}},
                {"id": "buildings", "type": "fill", "source": "protomaps", "source-layer": "buildings", "paint": {"fill-color": "#d5cdc3", "fill-outline-color": "#b8b0a6"}},
                {"id": "boundaries", "type": "line", "source": "protomaps", "source-layer": "boundaries", "paint": {"line-color": "#a0a0a0", "line-width": 1, "line-dasharray": [4, 2]}},
                {"id": "place-labels", "type": "symbol", "source": "protomaps", "source-layer": "place", "layout": {"text-field": "{name}", "text-size": 14, "text-anchor": "center"}, "paint": {"text-color": "#333333", "text-halo-color": "#ffffff", "text-halo-width": 1.5}}
              ]
            }
            """.trimIndent()
        } else {
            // Онлайн источник
            """
            {
              "version": 8,
              "name": "Vector Online Style",
              "sources": {
                "protomaps": {
                  "type": "vector",
                  "url": "$tileSourceUrl"
                }
              },
              "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
              "layers": [
                {"id": "background", "type": "background", "paint": {"background-color": "#e8e0d8"}},
                {"id": "water", "type": "fill", "source": "protomaps", "source-layer": "water", "paint": {"fill-color": "#a0cfdf"}},
                {"id": "landcover-wood", "type": "fill", "source": "protomaps", "source-layer": "landcover", "filter": ["==", "kind", "wood"], "paint": {"fill-color": "#cde0a5"}},
                {"id": "landcover-grass", "type": "fill", "source": "protomaps", "source-layer": "landcover", "filter": ["==", "kind", "grass"], "paint": {"fill-color": "#dce4c4"}},
                {"id": "landuse-residential", "type": "fill", "source": "protomaps", "source-layer": "landuse", "filter": ["==", "kind", "residential"], "paint": {"fill-color": "#f2eae2"}},
                {"id": "roads-case", "type": "line", "source": "protomaps", "source-layer": "roads", "minzoom": 10, "paint": {"line-color": "#c8c8c8", "line-width": 3}},
                {"id": "roads", "type": "line", "source": "protomaps", "source-layer": "roads", "minzoom": 10, "paint": {"line-color": "#ffffff", "line-width": 1.8}},
                {"id": "roads-major", "type": "line", "source": "protomaps", "source-layer": "roads", "filter": ["in", "kind", "highway", "major_road"], "minzoom": 8, "paint": {"line-color": "#f8c46f", "line-width": 3.5}},
                {"id": "trails-path", "type": "line", "source": "protomaps", "source-layer": "roads", "filter": ["==", "kind", "path"], "minzoom": 13, "paint": {"line-color": "#8b5a2b", "line-width": 1.5, "line-dasharray": [2, 2]}},
                {"id": "buildings", "type": "fill", "source": "protomaps", "source-layer": "buildings", "paint": {"fill-color": "#d5cdc3", "fill-outline-color": "#b8b0a6"}},
                {"id": "boundaries", "type": "line", "source": "protomaps", "source-layer": "boundaries", "paint": {"line-color": "#a0a0a0", "line-width": 1, "line-dasharray": [4, 2]}},
                {"id": "place-labels", "type": "symbol", "source": "protomaps", "source-layer": "place", "layout": {"text-field": "{name}", "text-size": 14, "text-anchor": "center"}, "paint": {"text-color": "#333333", "text-halo-color": "#ffffff", "text-halo-width": 1.5}}
              ]
            }
            """.trimIndent()
        }
    }
}