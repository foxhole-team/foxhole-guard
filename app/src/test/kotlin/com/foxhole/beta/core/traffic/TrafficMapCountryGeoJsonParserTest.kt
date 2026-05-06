package com.foxhole.beta.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficMapCountryGeoJsonParserTest {
    @Test
    fun `parses polygon and multipolygon countries from geojson`() {
        val raw =
            """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": { "ISO_A2": "US" },
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [
                      [[-10.0, 40.0], [-9.0, 40.0], [-9.0, 41.0], [-10.0, 40.0]]
                    ]
                  }
                },
                {
                  "type": "Feature",
                  "properties": { "ISO_A2": "DE" },
                  "geometry": {
                    "type": "MultiPolygon",
                    "coordinates": [
                      [
                        [[10.0, 50.0], [11.0, 50.0], [11.0, 51.0], [10.0, 50.0]]
                      ],
                      [
                        [[12.0, 52.0], [13.0, 52.0], [13.0, 53.0], [12.0, 52.0]]
                      ]
                    ]
                  }
                }
              ]
            }
            """.trimIndent()

        val shapes = TrafficMapCountryGeoJsonParser().parse(raw)

        assertEquals(listOf("US", "DE"), shapes.map(TrafficMapCountryShape::countryCode))
        assertEquals(1, shapes.first { shape -> shape.countryCode == "US" }.rings.size)
        assertEquals(2, shapes.first { shape -> shape.countryCode == "DE" }.rings.size)
        assertTrue(shapes.all { shape -> shape.rings.all { ring -> ring.size >= 3 } })
    }
}
