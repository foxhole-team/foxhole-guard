package com.foxhole.guard.traffic

import java.util.Locale

/**
 * City-level anchors for the traffic map, deliberately small and deliberately partial.
 *
 * Country centroids are fine for compact countries (all of Europe reads clearly with one dot per
 * country), but on large countries a single centroid puts a New York exit in the middle of Kansas.
 * So only the geographically large countries carry a compact table of major cities (the ones geo
 * endpoints actually return for VPN exits and device locations); everything else — and any city
 * the table does not know — falls back to the country point, so unknown cities all share one
 * anchor by design.
 *
 * Lookup is a normalized in-memory map hit: lowercase, alphanumerics only (so "Rostov-on-Don",
 * "Xi'an" and "São Paulo" match their plain spellings), with Russian-script aliases for the
 * countries where localized geo endpoints return Cyrillic names.
 */
internal object TrafficMapCityAnchors {
    fun resolve(
        countryCode: String?,
        city: String?,
    ): TrafficMapCityAnchor? {
        val country = countryCode?.trim()?.uppercase(Locale.US) ?: return null
        val normalizedCity = normalizeCity(city) ?: return null
        return anchorsByCountry[country]?.get(normalizedCity)
    }

    private fun normalizeCity(city: String?): String? =
        city
            ?.lowercase(Locale.US)
            ?.replace('ё', 'е')
            ?.filter(Char::isLetterOrDigit)
            ?.takeIf(String::isNotBlank)

    private fun cities(vararg entries: Pair<List<String>, TrafficMapCityAnchor>): Map<String, TrafficMapCityAnchor> =
        buildMap {
            entries.forEach { (names, anchor) ->
                names.forEach { name -> put(name, anchor) }
            }
        }

    private fun anchor(
        lat: Double,
        lon: Double,
    ): TrafficMapCityAnchor = TrafficMapCityAnchor(lat = lat, lon = lon)

    @Suppress("LargeClass")
    private val anchorsByCountry: Map<String, Map<String, TrafficMapCityAnchor>> =
        mapOf(
            "US" to cities(
                listOf("newyork", "newyorkcity", "brooklyn", "queens") to anchor(40.7128, -74.0060),
                listOf("losangeles") to anchor(34.0522, -118.2437),
                listOf("chicago") to anchor(41.8781, -87.6298),
                listOf("dallas") to anchor(32.7767, -96.7970),
                listOf("houston") to anchor(29.7604, -95.3698),
                listOf("miami") to anchor(25.7617, -80.1918),
                listOf("atlanta") to anchor(33.7490, -84.3880),
                listOf("seattle") to anchor(47.6062, -122.3321),
                listOf("sanfrancisco") to anchor(37.7749, -122.4194),
                listOf("sanjose", "santaclara") to anchor(37.3382, -121.8863),
                listOf("ashburn", "sterling", "reston") to anchor(39.0438, -77.4874),
                listOf("washington", "washingtondc") to anchor(38.9072, -77.0369),
                listOf("denver") to anchor(39.7392, -104.9903),
                listOf("phoenix") to anchor(33.4484, -112.0740),
                listOf("lasvegas") to anchor(36.1699, -115.1398),
                listOf("boston") to anchor(42.3601, -71.0589),
                listOf("portland") to anchor(45.5152, -122.6784),
                listOf("saltlakecity") to anchor(40.7608, -111.8910),
                listOf("kansascity") to anchor(39.0997, -94.5786),
                listOf("columbus") to anchor(39.9612, -82.9988),
            ),
            "CA" to cities(
                listOf("toronto") to anchor(43.6532, -79.3832),
                listOf("montreal") to anchor(45.5019, -73.5674),
                listOf("vancouver") to anchor(49.2827, -123.1207),
                listOf("calgary") to anchor(51.0447, -114.0719),
                listOf("edmonton") to anchor(53.5461, -113.4937),
                listOf("ottawa") to anchor(45.4215, -75.6972),
                listOf("winnipeg") to anchor(49.8951, -97.1384),
                listOf("quebec", "quebeccity") to anchor(46.8139, -71.2080),
                listOf("halifax") to anchor(44.6488, -63.5752),
            ),
            "RU" to cities(
                listOf("moscow", "москва", "moskva") to anchor(55.7558, 37.6173),
                listOf(
                    "saintpetersburg",
                    "stpetersburg",
                    "санктпетербург",
                    "sanktpeterburg",
                    "петербург",
                ) to anchor(59.9311, 30.3609),
                listOf("novosibirsk", "новосибирск") to anchor(55.0084, 82.9357),
                listOf("yekaterinburg", "ekaterinburg", "екатеринбург") to anchor(56.8389, 60.6057),
                listOf("kazan", "казань") to anchor(55.7963, 49.1088),
                listOf("nizhnynovgorod", "nizhniynovgorod", "нижнийновгород") to anchor(56.2965, 43.9361),
                listOf("samara", "самара") to anchor(53.1959, 50.1002),
                listOf("rostovondon", "ростовнадону") to anchor(47.2357, 39.7015),
                listOf("krasnodar", "краснодар") to anchor(45.0355, 38.9753),
                listOf("chelyabinsk", "челябинск") to anchor(55.1644, 61.4368),
                listOf("ufa", "уфа") to anchor(54.7388, 55.9721),
                listOf("omsk", "омск") to anchor(54.9885, 73.3242),
                listOf("krasnoyarsk", "красноярск") to anchor(56.0153, 92.8932),
                listOf("perm", "пермь") to anchor(58.0105, 56.2502),
                listOf("voronezh", "воронеж") to anchor(51.6720, 39.1843),
                listOf("volgograd", "волгоград") to anchor(48.7080, 44.5133),
                listOf("irkutsk", "иркутск") to anchor(52.2870, 104.3050),
                listOf("khabarovsk", "хабаровск") to anchor(48.4802, 135.0719),
                listOf("vladivostok", "владивосток") to anchor(43.1155, 131.8855),
                listOf("kaliningrad", "калининград") to anchor(54.7104, 20.4522),
                listOf("murmansk", "мурманск") to anchor(68.9585, 33.0827),
            ),
            "BR" to cities(
                listOf("saopaulo") to anchor(-23.5505, -46.6333),
                listOf("riodejaneiro") to anchor(-22.9068, -43.1729),
                listOf("brasilia") to anchor(-15.8267, -47.9218),
                listOf("fortaleza") to anchor(-3.7319, -38.5267),
                listOf("salvador") to anchor(-12.9777, -38.5016),
                listOf("belohorizonte") to anchor(-19.9167, -43.9345),
                listOf("portoalegre") to anchor(-30.0346, -51.2177),
                listOf("recife") to anchor(-8.0476, -34.8770),
                listOf("curitiba") to anchor(-25.4284, -49.2733),
                listOf("manaus") to anchor(-3.1190, -60.0217),
            ),
            "CN" to cities(
                listOf("beijing") to anchor(39.9042, 116.4074),
                listOf("shanghai") to anchor(31.2304, 121.4737),
                listOf("guangzhou") to anchor(23.1291, 113.2644),
                listOf("shenzhen") to anchor(22.5431, 114.0579),
                listOf("chengdu") to anchor(30.5728, 104.0668),
                listOf("hangzhou") to anchor(30.2741, 120.1551),
                listOf("wuhan") to anchor(30.5928, 114.3055),
                listOf("xian") to anchor(34.3416, 108.9398),
                listOf("chongqing") to anchor(29.4316, 106.9123),
                listOf("nanjing") to anchor(32.0603, 118.7969),
                listOf("tianjin") to anchor(39.3434, 117.3616),
                listOf("harbin") to anchor(45.8038, 126.5350),
                listOf("urumqi") to anchor(43.8256, 87.6168),
            ),
            "AU" to cities(
                listOf("sydney") to anchor(-33.8688, 151.2093),
                listOf("melbourne") to anchor(-37.8136, 144.9631),
                listOf("brisbane") to anchor(-27.4698, 153.0251),
                listOf("perth") to anchor(-31.9505, 115.8605),
                listOf("adelaide") to anchor(-34.9285, 138.6007),
                listOf("canberra") to anchor(-35.2809, 149.1300),
                listOf("darwin") to anchor(-12.4634, 130.8456),
                listOf("hobart") to anchor(-42.8821, 147.3272),
            ),
            "IN" to cities(
                listOf("mumbai") to anchor(19.0760, 72.8777),
                listOf("delhi", "newdelhi") to anchor(28.6139, 77.2090),
                listOf("bangalore", "bengaluru") to anchor(12.9716, 77.5946),
                listOf("chennai") to anchor(13.0827, 80.2707),
                listOf("hyderabad") to anchor(17.3850, 78.4867),
                listOf("kolkata") to anchor(22.5726, 88.3639),
                listOf("pune") to anchor(18.5204, 73.8567),
                listOf("ahmedabad") to anchor(23.0225, 72.5714),
            ),
            "KZ" to cities(
                listOf("almaty", "алматы") to anchor(43.2220, 76.8512),
                listOf("astana", "nursultan", "астана") to anchor(51.1605, 71.4704),
                listOf("shymkent", "шымкент") to anchor(42.3417, 69.5901),
                listOf("karaganda", "караганда") to anchor(49.8047, 73.1094),
                listOf("aktobe", "актобе") to anchor(50.2839, 57.1670),
            ),
            "MX" to cities(
                listOf("mexicocity", "ciudaddemexico") to anchor(19.4326, -99.1332),
                listOf("guadalajara") to anchor(20.6597, -103.3496),
                listOf("monterrey") to anchor(25.6866, -100.3161),
                listOf("queretaro") to anchor(20.5888, -100.3899),
                listOf("tijuana") to anchor(32.5149, -117.0382),
                listOf("cancun") to anchor(21.1619, -86.8515),
            ),
            "AR" to cities(
                listOf("buenosaires") to anchor(-34.6037, -58.3816),
                listOf("cordoba") to anchor(-31.4201, -64.1888),
                listOf("rosario") to anchor(-32.9442, -60.6505),
                listOf("mendoza") to anchor(-32.8895, -68.8458),
            ),
            "ID" to cities(
                listOf("jakarta") to anchor(-6.2088, 106.8456),
                listOf("surabaya") to anchor(-7.2575, 112.7521),
                listOf("bandung") to anchor(-6.9175, 107.6191),
                listOf("medan") to anchor(3.5952, 98.6722),
                listOf("denpasar", "bali") to anchor(-8.6705, 115.2126),
            ),
        )
}

internal data class TrafficMapCityAnchor(
    val lat: Double,
    val lon: Double,
)
