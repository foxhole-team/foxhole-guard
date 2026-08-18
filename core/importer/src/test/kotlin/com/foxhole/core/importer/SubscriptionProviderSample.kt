package com.foxhole.core.importer

internal object SubscriptionProviderSample {
    val nodeLines: List<String> =
        listOf(
            "hysteria2://00000000-0000-4000-8000-000000000001@198.51.100.11:443?sni=magic.nodes.example.net#\ud83c\uddf3\ud83c\uddf1 \u041d\u0438\u0434\u0435\u0440\u043b\u0430\u043d\u0434\u044b",
            "hysteria2://00000000-0000-4000-8000-000000000002@198.51.100.12:443?sni=magic.nodes.example.net#\ud83d\udcab \u0418\u0433\u0440\u043e\u0432\u043e\u0439 \u0431\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442",
            "vless://00000000-0000-4000-8000-000000000003@198.51.100.13:15565?encryption=none&type=grpc&security=reality&sni=iv.example.net&fp=qq&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA01&sid=0000000000000001&serviceName=grpc#\ud83c\uddf7\ud83c\uddfa \u0411\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442 #1 | \u0412\u0441\u0435 \u043e\u043f\u0435\u0440\u0430\u0442\u043e\u0440\u044b",
            "vless://00000000-0000-4000-8000-000000000003@198.51.100.14:1111?encryption=none&flow=xtls-rprx-vision&type=tcp&security=reality&sni=api-maps.example.net&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA02#\ud83c\uddf7\ud83c\uddfa \u0411\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442 #2 | \u0412\u0441\u0435 \u043e\u043f\u0435\u0440\u0430\u0442\u043e\u0440\u044b",
            "vless://00000000-0000-4000-8000-000000000003@198.51.100.15:15565?encryption=none&type=grpc&security=reality&sni=iv.example.net&fp=qq&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA01&sid=0000000000000001&serviceName=grpc#\ud83c\uddf7\ud83c\uddfa \u0411\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442 #3 | \u0412\u0441\u0435 \u043e\u043f\u0435\u0440\u0430\u0442\u043e\u0440\u044b",
            "vless://00000000-0000-4000-8000-000000000003@198.51.100.16:15565?encryption=none&type=grpc&security=reality&sni=iv.example.net&fp=qq&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA01&sid=0000000000000001&serviceName=grpc#\ud83c\uddf7\ud83c\uddfa \u0411\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442 #4 | \u0411\u0438\u043b\u0430\u0439\u043d / \u0422\u0435\u043b\u04352 / \u041c\u0422\u0421",
            "vless://00000000-0000-4000-8000-000000000003@198.51.100.17:15565?encryption=none&type=grpc&security=reality&sni=iv.example.net&fp=qq&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA01&sid=0000000000000001&serviceName=grpc#\ud83c\uddf7\ud83c\uddfa \u0411\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442 #5 | \u0411\u0438\u043b\u0430\u0439\u043d / \u0422\u0435\u043b\u04352 / \u041c\u0422\u0421",
            "vless://00000000-0000-4000-8000-000000000003@198.51.100.18:15565?encryption=none&type=grpc&security=reality&sni=iv.example.net&fp=qq&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA01&sid=0000000000000001&serviceName=grpc#\ud83c\uddf7\ud83c\uddfa \u0411\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442 #6 | \u0412\u0441\u0435 \u043e\u043f\u0435\u0440\u0430\u0442\u043e\u0440\u044b",
            "hysteria2://00000000-0000-4000-8000-000000000003@198.51.100.19:25443?sni=serv1.example.net&fp=firefox&alpn=h3#\ud83c\uddf7\ud83c\uddfa \u0411\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442 #7 | \u0411\u0438\u043b\u0430\u0439\u043d / \u041c\u0435\u0433\u0430\u0444\u043e\u043d / \u041c\u0422\u0421",
            "hysteria2://00000000-0000-4000-8000-000000000003@198.51.100.20:27015?sni=mine.example.net&fp=firefox&alpn=h3#\ud83c\uddf7\ud83c\uddfa \u0411\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442 #8 | \u0412\u0441\u0435 \u043e\u043f\u0435\u0440\u0430\u0442\u043e\u0440\u044b",
            "vless://00000000-0000-4000-8000-000000000003@198.51.100.21:1111?encryption=none&flow=xtls-rprx-vision&type=tcp&security=reality&sni=api-maps.example.net&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA02#\ud83c\uddf7\ud83c\uddfa \u0411\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442 #9 | \u0412\u0441\u0435 \u043e\u043f\u0435\u0440\u0430\u0442\u043e\u0440\u044b",
            "vless://00000000-0000-4000-8000-000000000003@198.51.100.22:15565?encryption=none&type=grpc&security=reality&sni=iv.example.net&fp=qq&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA01&sid=0000000000000001&serviceName=grpc#\ud83c\uddf7\ud83c\uddfa \u0411\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442 #10 | \u0412\u0441\u0435 \u043e\u043f\u0435\u0440\u0430\u0442\u043e\u0440\u044b",
            "vless://00000000-0000-4000-8000-000000000003@198.51.100.23:15565?encryption=none&type=grpc&security=reality&sni=iv.example.net&fp=qq&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA01&sid=0000000000000001&serviceName=grpc#\ud83c\uddf7\ud83c\uddfa \u0411\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442 #11 | \u0411\u0438\u043b\u0430\u0439\u043d / \u0422\u0435\u043b\u04352",
            "vless://00000000-0000-4000-8000-000000000003@198.51.100.24:15565?encryption=none&type=grpc&security=reality&sni=iv.example.net&fp=qq&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA01&sid=0000000000000001&serviceName=grpc#\ud83c\uddf7\ud83c\uddfa \u0411\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442 #12 | \u0412\u0441\u0435 \u043e\u043f\u0435\u0440\u0430\u0442\u043e\u0440\u044b",
            "vless://00000000-0000-4000-8000-000000000003@198.51.100.25:15565?encryption=none&type=grpc&security=reality&sni=iv.example.net&fp=qq&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA01&sid=0000000000000001&serviceName=grpc#\ud83c\uddf7\ud83c\uddfa \u0411\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442 #13 | \u0412\u0441\u0435 \u043e\u043f\u0435\u0440\u0430\u0442\u043e\u0440\u044b",
            "vless://00000000-0000-4000-8000-000000000003@198.51.100.26:15565?encryption=none&type=grpc&security=reality&sni=iv.example.net&fp=qq&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA01&sid=0000000000000001&serviceName=grpc#\ud83c\uddf7\ud83c\uddfa \u0411\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442 #14 | \u0412\u0441\u0435 \u043e\u043f\u0435\u0440\u0430\u0442\u043e\u0440\u044b",
            "hysteria2://00000000-0000-4000-8000-000000000003@198.51.100.19:25444?sni=serv2.example.net&fp=firefox&alpn=h3#\ud83c\uddf7\ud83c\uddfa \u0411\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442 #15 | \u0411\u0438\u043b\u0430\u0439\u043d / \u041c\u0435\u0433\u0430\u0444\u043e\u043d / \u041c\u0422\u0421",
        )

    val payload: String = nodeLines.joinToString(separator = "\n")

    val responseHeaders: Map<String, String> =
        mapOf(
            "content-type" to "text/html; charset=utf-8",
            "profile-title" to "base64:RXhhbXBsZSBWUE4gU2FtcGxl",
            "profile-update-interval" to "12",
            "announce-url" to "https://console.example.net/",
            "support-url" to "https://support.example.net/bot",
            "profile-web-page-url" to "https://console.example.net/",
            "announce" to "base64:Tm90aWNlIOKEuSByb3RhdGUgbm9kZXMg4oCUIGNhZsOpIPCflIQ=",
            "subscription-userinfo" to "upload=0; download=12345678901; total=0; expire=1808330436",
            "etag" to "W/\"10e2-0000000000000000000000000\"",
        )

    fun header(name: String): String? = responseHeaders[name.lowercase()]

    const val EXPECTED_TITLE: String = "Example VPN Sample"
    const val EXPECTED_ANNOUNCEMENT: String = "Notice \u2139 rotate nodes \u2014 caf\u00e9 \ud83d\udd04"
    const val EXPECTED_EXPIRES_AT: Long = 1_808_330_436_000L
    const val EXPECTED_DOWNLOAD_BYTES: Long = 12_345_678_901L

    const val SECOND_NODE_NAME: String =
        "\ud83d\udcab \u0418\u0433\u0440\u043e\u0432\u043e\u0439 \u0431\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442"

    const val THIRD_NODE_NAME: String =
        "\ud83c\uddf7\ud83c\uddfa \u0411\u0435\u043b\u044b\u0439 \u0438\u043d\u0442\u0435\u0440\u043d\u0435\u0442 #1 | \u0412\u0441\u0435 \u043e\u043f\u0435\u0440\u0430\u0442\u043e\u0440\u044b"
}
