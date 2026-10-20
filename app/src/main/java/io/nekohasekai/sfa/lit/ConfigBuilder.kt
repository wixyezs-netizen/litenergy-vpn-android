package io.nekohasekai.sfa.lit

import org.json.JSONArray
import org.json.JSONObject

/** Итоговая конфигурация ядра: выбранный сервер или автовыбор и настройки пользователя. */
object ConfigBuilder {
    const val SELECTOR_TAG = "proxy"
    const val AUTO_TAG = "auto"

    /** Полная конфигурация для подписки в виде списка ссылок. */
    fun template(outbounds: List<JSONObject>): JSONObject {
        val obs = JSONArray()
        outbounds.forEach { obs.put(it) }
        obs.put(JSONObject().put("type", "direct").put("tag", "direct"))
        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray()
                            .put(JSONObject().put("type", "tls").put("tag", "dns-remote").put("server", "1.1.1.1"))
                            .put(JSONObject().put("type", "local").put("tag", "dns-local")),
                    )
                    .put("final", "dns-remote"),
            )
            .put("inbounds", JSONArray().put(tunInbound()))
            .put("outbounds", obs)
            .put(
                "route",
                JSONObject()
                    .put(
                        "rules",
                        JSONArray()
                            .put(JSONObject().put("action", "sniff"))
                            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                            .put(JSONObject().put("ip_is_private", true).put("outbound", "direct")),
                    )
                    .put("auto_detect_interface", true)
                    .put("default_domain_resolver", "dns-local"),
            )
    }

    private fun tunInbound(): JSONObject = JSONObject()
        .put("type", "tun")
        .put("tag", "tun-in")
        .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
        .put("auto_route", true)
        .put("strict_route", true)
        .put("stack", "mixed")

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    fun build(source: JSONObject, selected: String, settings: ConnectionSettings): String {
        val config = JSONObject(source.toString()) // копия
        val all = (config.optJSONArray("outbounds") ?: JSONArray()).objects()
        val groupTypes = setOf("selector", "urltest")
        val groupTags = all.filter { it.optString("type") in groupTypes }.map { it.optString("tag") }.toSet()
        val plain = all.filter { it.optString("type") !in groupTypes }.toMutableList()
        val servers = SubscriptionLoader.servers(config).map { it.tag }
        if (servers.isEmpty()) throw LitException("В подписке нет серверов.")
        if (plain.none { it.optString("type") == "direct" }) plain += JSONObject().put("type", "direct").put("tag", "direct")
        val chosen = if (selected in servers) selected else AUTO_TAG

        val outbounds = JSONArray()
        if (servers.size > 1) {
            outbounds.put(
                JSONObject()
                    .put("type", "selector").put("tag", SELECTOR_TAG)
                    .put("outbounds", JSONArray(listOf(AUTO_TAG) + servers))
                    .put("default", chosen)
                    .put("interrupt_exist_connections", true),
            )
            outbounds.put(
                JSONObject()
                    .put("type", "urltest").put("tag", AUTO_TAG)
                    .put("outbounds", JSONArray(servers))
                    .put("url", "https://www.gstatic.com/generate_204")
                    .put("interval", "3m")
                    .put("tolerance", 50),
            )
        } else {
            outbounds.put(
                JSONObject().put("type", "selector").put("tag", SELECTOR_TAG)
                    .put("outbounds", JSONArray(servers)).put("default", servers[0]),
            )
        }
        plain.forEach { outbounds.put(it) }
        config.put("outbounds", outbounds)

        // маршруты: старые группы → наш селектор
        val route = config.optJSONObject("route") ?: JSONObject()
        val rules = (route.optJSONArray("rules") ?: JSONArray()).objects().toMutableList()
        rules.forEach { r -> if (r.optString("outbound") in groupTags) r.put("outbound", SELECTOR_TAG) }
        if (settings.routing == ConnectionSettings.ROUTING_RU) {
            val rule = JSONObject().put("domain_suffix", JSONArray(listOf("ru", "su", "xn--p1ai"))).put("outbound", "direct")
            val hijack = rules.indexOfFirst { it.optString("action") == "hijack-dns" }
            rules.add(if (hijack >= 0) hijack + 1 else rules.size, rule)
        }
        route.put("rules", JSONArray().also { a -> rules.forEach { a.put(it) } })
        route.put("final", SELECTOR_TAG)
        route.put("auto_detect_interface", true)
        config.put("route", route)

        // DNS
        val dns = config.optJSONObject("dns") ?: JSONObject()
        val dnsServers = (dns.optJSONArray("servers") ?: JSONArray()).objects().toMutableList()
        dnsServers.forEach { d -> if (d.optString("detour") in groupTags) d.put("detour", SELECTOR_TAG) }
        if (settings.dns != "auto") {
            dnsServers.removeAll { it.optString("tag") == "user-dns" }
            dnsServers += JSONObject().put("type", "udp").put("tag", "user-dns").put("server", settings.dns)
            dns.put("final", "user-dns")
        }
        if (!settings.ipv6) dns.put("strategy", "ipv4_only")
        dns.put("servers", JSONArray().also { a -> dnsServers.forEach { a.put(it) } })
        config.put("dns", dns)

        // только TUN; без системного прокси и своего имени интерфейса
        var inbounds = (config.optJSONArray("inbounds") ?: JSONArray()).objects().filter { it.optString("type") == "tun" }
        if (inbounds.isEmpty()) inbounds = listOf(tunInbound())
        inbounds.forEach { t ->
            t.remove("platform")
            t.remove("interface_name")
            if (!settings.ipv6) {
                val addrs = t.optJSONArray("address")
                if (addrs != null) {
                    val v4 = (0 until addrs.length()).map { addrs.optString(it) }.filter { !it.contains(':') }
                    t.put("address", JSONArray(v4))
                }
            }
        }
        config.put("inbounds", JSONArray().also { a -> inbounds.forEach { a.put(it) } })
        config.remove("experimental")
        config.put("log", JSONObject().put("level", "warn"))
        return config.toString()
    }

    /** Демо для скриншотов сборки: рабочая конфигурация без серверов (весь трафик напрямую). */
    fun demo(): String {
        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", JSONObject().put("servers", JSONArray().put(JSONObject().put("type", "local").put("tag", "dns-local"))))
            .put("inbounds", JSONArray().put(tunInbound()))
            .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put(
                "route",
                JSONObject()
                    .put("rules", JSONArray().put(JSONObject().put("action", "sniff")).put(JSONObject().put("protocol", "dns").put("action", "hijack-dns")))
                    .put("final", "direct")
                    .put("auto_detect_interface", true),
            )
            .toString()
    }
}
