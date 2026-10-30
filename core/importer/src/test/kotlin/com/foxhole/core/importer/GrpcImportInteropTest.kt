package com.foxhole.core.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

internal class GrpcImportInteropTest {
    private val parser = ProfileImportParser(Json)
    private val uuid = "11111111-1111-4111-8111-111111111111"
    private val base = "vless://$uuid@1.1.1.1:443?security=tls&sni=tls.example&type=grpc"

    @Test
    fun `URI grpc authority and multi mode survive portable copy independently of TLS SNI`() {
        val root = parse("$base&serviceName=GunService&authority=rpc.example%3A8443&mode=multi#Fixture")
        val expected = basic("GunService", true, "rpc.example:8443")
        assertEquals(expected, transport(root))
        assertEquals(JsonPrimitive("tls.example"), node(root).getValue("tls").jsonObject["server_name"])
        assertEquals(expected, transport(roundTrip(root)))
    }

    @Test
    fun `URI empty none and gun modes use one stream while multi remains explicit`() {
        for (mode in listOf("", "none", "gun", "multi")) {
            val root = parse("$base&serviceName=svc&mode=${encode(mode)}#Fixture")
            assertEquals(JsonPrimitive(mode == "multi"), transport(root)["multi_mode"])
            assertEquals(transport(root), transport(roundTrip(root)))
        }
    }

    @Test
    fun `VMess grpc host authority and none mode are imported and copied`() {
        val link =
            vmess(
                buildJsonObject {
                    put("type", "none")
                    put("path", "GunService")
                    put("host", "rpc.example:8443")
                }
            )
        val root = parse(link)
        val value = transport(root)
        assertEquals(JsonPrimitive("GunService"), value["service_name"])
        assertEquals(JsonPrimitive("rpc.example:8443"), value["authority"])
        assertEquals(false, value["multi_mode"]?.jsonPrimitive?.content?.toBooleanStrict() ?: false)
        assertEquals(value, transport(roundTrip(root)))
    }

    @Test
    fun `empty default and full custom RPC paths retain their spelling`() {
        for (service in listOf("", "/package.Service/Custom", "svc|/SingleCall|/ManyCall")) {
            val root = parse("$base&serviceName=${encode(service)}#Fixture")
            assertEquals(JsonPrimitive(service), transport(root)["service_name"])
            assertEquals(transport(root), transport(roundTrip(root)))
        }
    }

    @Test
    fun `Xray grpc authority mode and effectful tuning survive portable copy`() {
        val root =
            parse(
                xray(
                    """{"serviceName":"GunService","authority":"rpc.example:8443","multiMode":true,"user_agent":"grpc-fixture/1","idle_timeout":23,"health_check_timeout":7,"permit_without_stream":true,"initial_windows_size":131072}"""
                )
            )
        val expected = tuned()
        assertEquals(expected, transport(root))
        assertEquals(expected, transport(roundTrip(root)))
    }

    @Test
    fun `Xray omitted service name retains the valid empty default`() {
        val root = parse(xray("{}"))
        assertEquals(JsonPrimitive(""), transport(root)["service_name"])
        assertEquals(transport(root), transport(roundTrip(root)))
    }

    @Test
    fun `sing box grpc options survive selected clipboard export without policy changes`() {
        val root = parse("$base&serviceName=GunService#Fixture")
        val nodes = root.getValue("outbounds").jsonArray
        val original =
            JsonObject(
                root + (
                    "outbounds" to JsonArray(
                        listOf(
                            JsonObject(
                                nodes.first().jsonObject + (
                                    "transport" to tuned()
                                    )
                            )
                        ) + nodes.drop(1)
                    )
                    )
            )
        val imported = parse(original.toString())
        assertEquals(tuned(), transport(imported))
        assertEquals(tuned(), transport(roundTrip(imported)))
        assertEquals(root.getValue("route").jsonObject.keys, imported.getValue("route").jsonObject.keys)
    }

    @Test
    fun `Clash grpc service name imports for every compatible protocol`() {
        for (
        (protocol, credentials) in listOf(
            "vless" to "uuid: $uuid",
            "vmess" to "uuid: $uuid, alterId: 0, cipher: auto",
            "trojan" to "password: synthetic"
        )
        ) {
            val root =
                parse(
                    "proxies:\n - {name: Fixture, type: $protocol, server: 1.1.1.1, port: 443, $credentials, tls: true, servername: tls.example, network: grpc, grpc-opts: {grpc-service-name: GunService}}"
                )
            assertEquals(JsonPrimitive("grpc"), transport(root)["type"])
            assertEquals(JsonPrimitive("GunService"), transport(root)["service_name"])
            assertEquals(transport(root), transport(roundTrip(root)))
        }
    }

    @Test
    fun `unknown grpc settings and invalid mode cannot silently disappear`() {
        for (
        settings in listOf(
            """{"serviceName":"svc","not_supported":true}""",
            """{"serviceName":"svc","multiMode":"maybe"}""",
            """{"serviceName":"svc","initial_windows_size":2147483648}"""
        )
        ) {
            assertThrows(IllegalArgumentException::class.java) { parse(xray(settings)) }
        }
        assertThrows(IllegalArgumentException::class.java) { parse("$base&serviceName=svc&mode=unrecognized") }
    }

    @Test
    fun `URI effectful grpc tuning survives copy rather than being ignored`() {
        val root =
            parse(
                "$base&serviceName=GunService&authority=rpc.example%3A8443&mode=multi&user_agent=grpc-fixture%2F1&idle_timeout=23&health_check_timeout=7&permit_without_stream=true&initial_windows_size=131072#Fixture"
            )
        assertEquals(tuned(), transport(root))
        assertEquals(tuned(), transport(roundTrip(root)))
    }

    @Test
    fun `sing box subsecond keepalive durations survive portable copy exactly`() {
        val root = parse("$base&serviceName=GunService#Fixture")
        val nodes = root.getValue("outbounds").jsonArray
        val options = buildJsonObject {
            put("type", "grpc")
            put("service_name", "GunService")
            put("idle_timeout", "1500ms")
            put("ping_timeout", "2.5s")
            put("permit_without_stream", false)
        }
        val original =
            JsonObject(
                root + (
                    "outbounds" to JsonArray(
                        listOf(
                            JsonObject(
                                nodes.first().jsonObject + (
                                    "transport" to options
                                    )
                            )
                        ) + nodes.drop(1)
                    )
                    )
            )
        val imported = parse(original.toString())
        val value = transport(imported)
        assertEquals(JsonPrimitive("1500ms"), value["idle_timeout"])
        assertEquals(JsonPrimitive("2.5s"), value["health_check_timeout"] ?: value["ping_timeout"])
        assertEquals(value, transport(roundTrip(imported)))
    }

    @Test
    fun `sing box literal service path gets an exact RPC override`() {
        val root = parse("$base&serviceName=GunService#Fixture")
        val nodes = root.getValue("outbounds").jsonArray
        val options = buildJsonObject {
            put("type", "grpc")
            put("service_name", "svc/name%2Ftail")
        }
        val original =
            JsonObject(
                root + (
                    "outbounds" to JsonArray(
                        listOf(
                            JsonObject(
                                nodes.first().jsonObject + (
                                    "transport" to options
                                    )
                            )
                        ) + nodes.drop(1)
                    )
                    )
            )
        val imported = parse(original.toString())
        assertEquals(JsonPrimitive("svc/name%2Ftail"), transport(imported)["service_name"])
        assertEquals(JsonPrimitive("/svc%2Fname%252Ftail/Tun"), transport(imported)["rpc_path"])
        assertEquals(transport(imported), transport(roundTrip(imported)))
    }

    @Test
    fun `Xray health only enables minimum keepalive but nonpositive idle stays disabled`() {
        assertEquals(
            JsonPrimitive("10s"),
            transport(parse(xray("""{"serviceName":"svc","health_check_timeout":7}""")))["idle_timeout"]
        )
        assertEquals(
            JsonPrimitive("10s"),
            transport(parse(xray("""{"serviceName":"svc","idle_timeout":1}""")))["idle_timeout"]
        )
        val disabled =
            transport(
                parse(
                    xray(
                        """{"serviceName":"svc","idle_timeout":-1,"health_check_timeout":0,"initial_windows_size":-1}"""
                    )
                )
            )
        assertEquals(JsonPrimitive("0s"), disabled["idle_timeout"])
        assertEquals(JsonPrimitive("20s"), disabled["health_check_timeout"])
        assertEquals(JsonPrimitive(0), disabled["initial_windows_size"])
    }

    @Test
    fun `Clash user agent and ping import while unsupported pooling is refused`() {
        for (
        (protocol, credentials) in listOf(
            "vless" to "uuid: $uuid",
            "vmess" to "uuid: $uuid, alterId: 0, cipher: auto",
            "trojan" to "password: synthetic"
        )
        ) {
            for (seconds in listOf(1, 15)) {
                val yaml = "proxies:\n - {name: Fixture, type: $protocol, server: 1.1.1.1, port: 443, $credentials, tls: true, network: grpc, grpc-opts: {grpc-service-name: svc, grpc-user-agent: fixture, ping-interval: $seconds}}"
                val root = parse(yaml)
                assertEquals(JsonPrimitive("fixture"), transport(root)["user_agent"])
                assertEquals(JsonPrimitive(seconds * 1000), transport(root)["idle_timeout_ms"])
                assertEquals(transport(root), transport(roundTrip(root)))
                assertThrows(
                    IllegalArgumentException::class.java
                ) { parse(yaml.replace("ping-interval: $seconds", "max-connections: 3")) }
            }
        }
    }

    @Test
    fun `precise URI milliseconds survive portable JSON without truncation`() {
        val root = parse("$base&serviceName=svc&idle_timeout_ms=90500&health_check_timeout_ms=1#Fixture")
        assertEquals(JsonPrimitive(90500), transport(root)["idle_timeout_ms"])
        assertEquals(JsonPrimitive(1), transport(root)["health_check_timeout_ms"])
        assertEquals(transport(root), transport(roundTrip(root)))
        for (raw in listOf("1m30.5s", "1.5", "1ns")) {
            assertThrows(IllegalArgumentException::class.java) { parse("$base&serviceName=svc&idle_timeout=$raw") }
        }
    }

    @Test
    fun `URI and VMess integer seconds match native Xray normalization`() {
        val value =
            transport(
                parse("$base&serviceName=svc&idle_timeout=1&health_check_timeout=-1&initial_windows_size=-1")
            )
        assertEquals(JsonPrimitive("10s"), value["idle_timeout"])
        assertEquals(JsonPrimitive("20s"), value["health_check_timeout"])
        assertEquals(JsonPrimitive(0), value["initial_windows_size"])
        val link =
            vmess(
                buildJsonObject {
                    put("type", "gun")
                    put("path", "svc")
                    put("idle_timeout", 1)
                    put("health_check_timeout", -1)
                    put("initial_windows_size", -1)
                }
            )
        assertEquals(value, transport(parse(link)))
        for (
        suffix in listOf(
            "&idle_timeout_ms=1500&health_check_timeout=1",
            "&health_check_timeout_ms=1&idle_timeout=2",
            "&ping_timeout=1s"
        )
        ) {
            assertThrows(IllegalArgumentException::class.java) { parse("$base&serviceName=svc$suffix") }
        }
    }

    @Test
    fun `Unicode and Go path special characters preserve their raw service metadata`() {
        for (service in listOf("服务", "svc+tag:one@two", "svc~*name", "svc/a%b")) {
            val root = parse("$base&serviceName=${encode(service)}&mode=gun#Fixture")
            assertEquals(JsonPrimitive(service), transport(root)["service_name"])
            assertEquals(transport(root), transport(roundTrip(root)))
        }
        for (control in listOf('\n', '\u007f', '\u0085', '\u009f')) {
            assertThrows(
                IllegalArgumentException::class.java
            ) { parse("$base&serviceName=${encode("bad${control}service")}") }
        }
    }

    @Test
    fun `sing box Lite service segments use primary Go escaping including Unicode`() {
        for (
        (service, wire) in listOf(
            "服务" to "/%E6%9C%8D%E5%8A%A1/Tun",
            " a+b:c@d~*" to "/%20a+b:c@d~%2A/Tun",
            "/svc/a%2Fb" to "/%2Fsvc%2Fa%252Fb/Tun"
        )
        ) {
            val source = buildJsonObject {
                put("type", "grpc")
                put("service_name", service)
            }
            val value = ProfileImportGrpcSupport.canonical(source, singBoxPath = true)
            assertEquals(JsonPrimitive(service), value["service_name"])
            assertEquals(JsonPrimitive(wire), value["rpc_path"])
        }
    }

    @Test
    fun `Mihomo defaults and raw paths remain distinct from Xray and Lite`() {
        for (
        (service, expected) in listOf(
            "" to "/GunService/Tun",
            "svc/a%raw" to "/svc/a%raw/Tun",
            "/名字%raw%2Fservice/Tun" to "/名字%raw%2Fservice/Tun"
        )
        ) {
            val yaml = "proxies:\n - {name: Fixture, type: vless, server: 1.1.1.1, port: 443, uuid: $uuid, tls: true, network: grpc, grpc-opts: {grpc-service-name: '$service'}}"
            val root = parse(yaml)
            assertEquals(JsonPrimitive(expected), transport(root)["rpc_path"])
            assertEquals(transport(root), transport(roundTrip(root)))
        }
    }

    @Test
    fun `Xray user agent aliases expand only at the Xray source boundary`() {
        val expanded = transport(parse(xray("""{"serviceName":"svc","user_agent":"firefox"}""")))["user_agent"]
        assertEquals(
            JsonPrimitive("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"),
            expanded
        )
        val raw = parse("$base&serviceName=svc&user_agent=firefox#Fixture")
        assertEquals(JsonPrimitive("firefox"), transport(raw)["user_agent"])
        assertEquals(transport(raw), transport(roundTrip(raw)))
        val yaml = "proxies:\n - {name: Fixture, type: vless, server: 1.1.1.1, port: 443, uuid: $uuid, tls: true, network: grpc, grpc-opts: {grpc-service-name: svc, grpc-user-agent: firefox}}"
        assertEquals(JsonPrimitive("firefox"), transport(parse(yaml))["user_agent"])
        assertEquals(
            JsonPrimitive("grpc-go/1.36.0"),
            transport(parse(yaml.replace(", grpc-user-agent: firefox", "")))["user_agent"]
        )
    }

    private fun basic(service: String, multi: Boolean, authority: String) = buildJsonObject {
        put("type", "grpc")
        put("service_name", service)
        put("multi_mode", multi)
        put("authority", authority)
    }

    private fun tuned() = JsonObject(
        basic("GunService", true, "rpc.example:8443") + buildJsonObject {
            put("user_agent", "grpc-fixture/1")
            put("idle_timeout", "23s")
            put("health_check_timeout", "7s")
            put("permit_without_stream", true)
            put("initial_windows_size", 131072)
        }
    )

    private fun parse(value: String) = Json.parseToJsonElement(
        requireNotNull(parser.parseUserInput(value).normalizedConfigJson)
    ).jsonObject

    private fun node(root: JsonObject): JsonObject = root.getValue("outbounds").jsonArray.first {
        it.jsonObject["type"]?.jsonPrimitive?.content in setOf("vless", "vmess", "trojan")
    }.jsonObject

    private fun transport(root: JsonObject) = node(root).getValue("transport").jsonObject

    private fun roundTrip(root: JsonObject) = parse(exportPortableConfigs("Fixture", listOf(root.toString())))

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun vmess(extra: JsonObject): String {
        val value =
            JsonObject(
                buildJsonObject {
                    put("v", "2")
                    put("ps", "Fixture")
                    put("add", "1.1.1.1")
                    put("port", "443")
                    put("id", uuid)
                    put("aid", "0")
                    put("scy", "auto")
                    put("tls", "tls")
                    put("sni", "tls.example")
                    put("net", "grpc")
                } + extra
            )
        return "vmess://" + Base64.getEncoder().encodeToString(value.toString().toByteArray())
    }

    private fun xray(
        settings: String
    ) = """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"1.1.1.1","port":443,"users":[{"id":"$uuid","encryption":"none"}]}]},"streamSettings":{"network":"grpc","security":"tls","tlsSettings":{"serverName":"tls.example"},"grpcSettings":$settings}}]}"""
}
