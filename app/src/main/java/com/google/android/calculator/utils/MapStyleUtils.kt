package com.google.android.calculator.utils

object MapStyleUtils {
    fun getBasicStyleJson(tileSourceUrl: String): String {
        val isLocalSource = tileSourceUrl.contains("localhost")
        
        val glyphsUrl = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"

        val sourceDefinition = if (isLocalSource) {
            """
            "protomaps": {
              "type": "vector",
              "tiles": ["$tileSourceUrl"],
              "minzoom": 0,
              "maxzoom": 15
            }
            """
        } else {
            """
            "protomaps": {
              "type": "vector",
              "url": "$tileSourceUrl"
            }
            """
        }

        return """
        {
          "version": 8,
          "name": "Vector Map Style",
          "sources": {
            $sourceDefinition
          },
          "glyphs": "$glyphsUrl",
          "layers": [
            {"id": "background", "type": "background", "paint": {"background-color": "#e8e0d8"}},
            
            {"id": "water", "type": "fill", "source": "protomaps", "source-layer": "water", "paint": {"fill-color": "#a0cfdf"}},

            {"id": "landcover-wood", "type": "fill", "source": "protomaps", "source-layer": "landcover", "filter": ["any", ["==", "class", "wood"], ["==", "kind", "wood"]], "paint": {"fill-color": "#a8d5a2", "fill-opacity": 0.9}},
            {"id": "landcover-grass", "type": "fill", "source": "protomaps", "source-layer": "landcover", "filter": ["any", ["in", "class", "grass", "farmland"], ["in", "kind", "grass", "farmland"]], "paint": {"fill-color": "#c8e6c8", "fill-opacity": 0.9}},
            {"id": "landcover-wetland", "type": "fill", "source": "protomaps", "source-layer": "landcover", "filter": ["any", ["==", "class", "wetland"], ["==", "kind", "wetland"]], "paint": {"fill-color": "#b8dcc0", "fill-opacity": 0.8}},
            
            {"id": "landuse-park", "type": "fill", "source": "protomaps", "source-layer": "landuse", "filter": ["any", ["in", "class", "park", "garden", "recreation_ground"], ["in", "kind", "park", "garden", "recreation_ground"]], "paint": {"fill-color": "#a8e6a8", "fill-opacity": 0.85}},
            {"id": "landuse-grass", "type": "fill", "source": "protomaps", "source-layer": "landuse", "filter": ["any", ["in", "class", "grass", "meadow", "farmland"], ["in", "kind", "grass", "meadow", "farmland"]], "paint": {"fill-color": "#d4ecd4", "fill-opacity": 0.7}},
            {"id": "landuse-residential", "type": "fill", "source": "protomaps", "source-layer": "landuse", "filter": ["any", ["==", "class", "residential"], ["==", "kind", "residential"]], "paint": {"fill-color": "#f2eae2"}},
            
            {"id": "roads-case", "type": "line", "source": "protomaps", "source-layer": "roads", "minzoom": 10, "paint": {"line-color": "#c8c8c8", "line-width": 3}},
            {"id": "roads-case-omt", "type": "line", "source": "protomaps", "source-layer": "transportation", "minzoom": 10, "filter": ["!in", "class", "path", "pedestrian", "footway", "steps"], "paint": {"line-color": "#c8c8c8", "line-width": 3}},
            {"id": "roads-case-sb", "type": "line", "source": "protomaps", "source-layer": "street", "minzoom": 10, "paint": {"line-color": "#c8c8c8", "line-width": 3}},
            
            {"id": "roads", "type": "line", "source": "protomaps", "source-layer": "roads", "minzoom": 10, "paint": {"line-color": "#ffffff", "line-width": 1.8}},
            {"id": "roads-omt", "type": "line", "source": "protomaps", "source-layer": "transportation", "minzoom": 10, "filter": ["!in", "class", "path", "pedestrian", "footway", "steps"], "paint": {"line-color": "#ffffff", "line-width": 1.8}},
            {"id": "roads-sb", "type": "line", "source": "protomaps", "source-layer": "street", "minzoom": 10, "paint": {"line-color": "#ffffff", "line-width": 1.8}},
            
            {"id": "roads-major", "type": "line", "source": "protomaps", "source-layer": "roads", "filter": ["in", "kind", "highway", "major_road", "motorway", "trunk", "primary"], "minzoom": 8, "paint": {"line-color": "#f8c46f", "line-width": 3.5}},
            {"id": "roads-major-omt", "type": "line", "source": "protomaps", "source-layer": "transportation", "filter": ["in", "class", "motorway", "trunk", "primary", "secondary"], "minzoom": 8, "paint": {"line-color": "#f8c46f", "line-width": 3.5}},
            {"id": "roads-major-sb", "type": "line", "source": "protomaps", "source-layer": "street", "filter": ["in", "kind", "motorway", "trunk", "primary", "secondary"], "minzoom": 8, "paint": {"line-color": "#f8c46f", "line-width": 3.5}},
            
            {"id": "trails-path", "type": "line", "source": "protomaps", "source-layer": "roads", "filter": ["in", "kind", "path", "pedestrian", "footway", "steps"], "minzoom": 13, "paint": {"line-color": "#8b5a2b", "line-width": 1.5, "line-dasharray": [2, 2]}},
            {"id": "trails-path-omt", "type": "line", "source": "protomaps", "source-layer": "transportation", "filter": ["in", "class", "path", "pedestrian", "footway", "steps"], "minzoom": 13, "paint": {"line-color": "#8b5a2b", "line-width": 1.5, "line-dasharray": [2, 2]}},
            {"id": "trails-path-sb", "type": "line", "source": "protomaps", "source-layer": "street", "filter": ["in", "kind", "path", "pedestrian", "footway", "steps"], "minzoom": 13, "paint": {"line-color": "#8b5a2b", "line-width": 1.5, "line-dasharray": [2, 2]}},
            
            {"id": "buildings", "type": "fill", "source": "protomaps", "source-layer": "buildings", "paint": {"fill-color": "#d5cdc3", "fill-outline-color": "#b8b0a6"}},
            {"id": "buildings-omt", "type": "fill", "source": "protomaps", "source-layer": "building", "paint": {"fill-color": "#d5cdc3", "fill-outline-color": "#b8b0a6"}},
            
            {"id": "boundaries", "type": "line", "source": "protomaps", "source-layer": "boundaries", "paint": {"line-color": "#a0a0a0", "line-width": 1, "line-dasharray": [4, 2]}},
            {"id": "boundaries-omt", "type": "line", "source": "protomaps", "source-layer": "boundary", "paint": {"line-color": "#a0a0a0", "line-width": 1, "line-dasharray": [4, 2]}},
            
            {"id": "road-labels", "type": "symbol", "source": "protomaps", "source-layer": "roads", "minzoom": 14, "layout": {"text-field": "{name}", "text-size": 11, "symbol-placement": "line", "text-font": ["Noto Sans Regular"]}, "paint": {"text-color": "#555555", "text-halo-color": "#ffffff", "text-halo-width": 1}},
            {"id": "road-labels-omt", "type": "symbol", "source": "protomaps", "source-layer": "transportation_name", "minzoom": 14, "layout": {"text-field": "{name}", "text-size": 11, "symbol-placement": "line", "text-font": ["Noto Sans Regular"]}, "paint": {"text-color": "#555555", "text-halo-color": "#ffffff", "text-halo-width": 1}},
            
            {"id": "address-labels", "type": "symbol", "source": "protomaps", "source-layer": "addresses", "minzoom": 15, "layout": {"text-field": "{housenumber}", "text-size": 10, "text-font": ["Noto Sans Regular"], "text-anchor": "center", "text-allow-overlap": true, "text-ignore-placement": true}, "paint": {"text-color": "#666666", "text-halo-color": "#ffffff", "text-halo-width": 0.5}},
            {"id": "address-labels-omt", "type": "symbol", "source": "protomaps", "source-layer": "housenumber", "minzoom": 15, "layout": {"text-field": "{housenumber}", "text-size": 10, "text-font": ["Noto Sans Regular"], "text-anchor": "center", "text-allow-overlap": true, "text-ignore-placement": true}, "paint": {"text-color": "#666666", "text-halo-color": "#ffffff", "text-halo-width": 0.5}},
            {"id": "address-labels-sb", "type": "symbol", "source": "protomaps", "source-layer": "address", "minzoom": 15, "layout": {"text-field": "{housenumber}", "text-size": 10, "text-font": ["Noto Sans Regular"], "text-anchor": "center", "text-allow-overlap": true, "text-ignore-placement": true}, "paint": {"text-color": "#666666", "text-halo-color": "#ffffff", "text-halo-width": 0.5}},
            
            {"id": "poi-labels", "type": "symbol", "source": "protomaps", "source-layer": "pois", "minzoom": 15, "layout": {"text-field": "{name}", "text-size": 12, "text-font": ["Noto Sans Regular"], "text-anchor": "center", "icon-allow-overlap": true}, "paint": {"text-color": "#336633", "text-halo-color": "#ffffff", "text-halo-width": 1}},
            {"id": "poi-labels-omt", "type": "symbol", "source": "protomaps", "source-layer": "poi", "minzoom": 15, "layout": {"text-field": "{name}", "text-size": 12, "text-font": ["Noto Sans Regular"], "text-anchor": "center", "icon-allow-overlap": true}, "paint": {"text-color": "#336633", "text-halo-color": "#ffffff", "text-halo-width": 1}},
            
            {"id": "place-labels", "type": "symbol", "source": "protomaps", "source-layer": "place", "layout": {"text-field": "{name}", "text-size": 14, "text-anchor": "center", "text-font": ["Noto Sans Regular"]}, "paint": {"text-color": "#333333", "text-halo-color": "#ffffff", "text-halo-width": 1.5}},
            
            {"id": "water-labels", "type": "symbol", "source": "protomaps", "source-layer": "water", "minzoom": 10, "layout": {"text-field": "{name}", "text-size": 12, "text-font": ["Noto Sans Italic"], "symbol-placement": "point"}, "paint": {"text-color": "#4477aa", "text-halo-color": "#ffffff", "text-halo-width": 1}},
            {"id": "water-labels-omt", "type": "symbol", "source": "protomaps", "source-layer": "water_name", "minzoom": 10, "layout": {"text-field": "{name}", "text-size": 12, "text-font": ["Noto Sans Italic"], "symbol-placement": "point"}, "paint": {"text-color": "#4477aa", "text-halo-color": "#ffffff", "text-halo-width": 1}}
          ]
        }
        """.trimIndent()
    }
}