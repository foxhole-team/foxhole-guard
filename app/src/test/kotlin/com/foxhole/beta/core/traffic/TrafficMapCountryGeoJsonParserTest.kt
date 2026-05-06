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

    @Test
    fun `normalizes dateline rings without spanning the whole world`() {
        val ring =
            listOf(
                TrafficMapGeoPoint(lat = 51.0, lon = 179.0),
                TrafficMapGeoPoint(lat = 51.5, lon = -179.5),
                TrafficMapGeoPoint(lat = 52.0, lon = 179.5),
                TrafficMapGeoPoint(lat = 51.0, lon = 179.0),
            )

        val normalized = normalizeTrafficMapRingLongitudes(ring)
        val longitudeSpan = normalized.maxOf(TrafficMapGeoPoint::lon) - normalized.minOf(TrafficMapGeoPoint::lon)

        assertTrue(longitudeSpan < 3.0)
    }

    @Test
    fun `visual shape keeps major country objects and drops tiny secondary islands`() {
        val shape =
            TrafficMapCountryShape(
                countryCode = "US",
                rings =
                    listOf(
                        listOf(
                            TrafficMapGeoPoint(lat = 20.0, lon = -130.0),
                            TrafficMapGeoPoint(lat = 20.0, lon = -60.0),
                            TrafficMapGeoPoint(lat = 50.0, lon = -60.0),
                            TrafficMapGeoPoint(lat = 20.0, lon = -130.0),
                        ),
                        listOf(
                            TrafficMapGeoPoint(lat = 10.0, lon = 10.0),
                            TrafficMapGeoPoint(lat = 10.0, lon = 10.1),
                            TrafficMapGeoPoint(lat = 10.1, lon = 10.1),
                            TrafficMapGeoPoint(lat = 10.0, lon = 10.0),
                        ),
                    ),
            )

        val visualShape = shape.toTrafficMapVisualShape()

        assertEquals(1, visualShape?.rings?.size)
    }
}
