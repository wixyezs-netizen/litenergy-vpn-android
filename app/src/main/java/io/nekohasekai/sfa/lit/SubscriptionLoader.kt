package io.nekohasekai.sfa.lit

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/** Загрузка и разбор подписки. Адрес подписки не пишется в лог. */
object SubscriptionLoader {
    class Loaded(val info: SubscriptionInfo, val config: JSONObject, val servers: List<Server>)

    /** Ссылка из буфера, QR-кода или диплинка → адрес подписки (null — это не ссылка). */
    fun normalize(raw: String): String? {
        var s = raw.trim()
        if (s.lowercase().startsWith("litenergy://")) {
            s = runCatching { Uri.parse(s).getQueryParameter("url") }.getOrNull() ?: return null
        }
        if (!s.lowercase().startsWith("http")) {
            s = Regex("https://\\S+").find(s)?.value ?: return null
        }
        s = s.trim().trimEnd('.', ',', ')', '»', '"', '\'')
        val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "https" && scheme != "http") || uri.host.isNullOrEmpty()) return null
        return s
    }

    fun fetch(context: Context, url: String): Loaded {
        var last: Exception = LitException(NET_ERROR)
        for (attempt in 0 until 3) {
            if (attempt > 0) Thread.sleep(900L * attempt)
            try {
                return fetchOnce(context, url)
            } catch (e: LitException) {
                throw e
            } catch (e: Exception) {
                last = LitException(NET_ERROR)
            }
        }
        throw last
    }

    private fun fetchOnce(context: Context, url: String): Loaded {
        // новое соединение на каждый запрос: на части сетей долгие соединения через CDN «замирают»
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Connection", "close")
            conn.setRequestProperty("User-Agent", Device.userAgent)
            conn.setRequestProperty("x-hwid", Device.hwid(context))
            conn.setRequestProperty("x-device-os", "Android")
            conn.setRequestProperty("x-ver-os", Device.osVersion)
            conn.setRequestProperty("x-device-model", Device.model)
            val code = conn.responseCode
            when {
                code in 200..299 -> {}
                code == 404 -> throw LitException("Подписка не найдена. Проверьте ссылку или получите новую в боте.")
                code == 403 -> throw LitException("Подписка отключена. Обратитесь в поддержку.")
                code >= 500 -> throw IOException("server $code")
                else -> throw LitException("Сервер подписки ответил ошибкой ($code).")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val info = parseHeaders(conn)
            val config = parseBody(body)
            val servers = servers(config)
            if (servers.isEmpty()) {
                throw LitException("В подписке нет доступных серверов. Обновите подписку позже или напишите в поддержку.")
            }
            if (servers.size == 1 && servers[0].port <= 1) {
                // заглушка сервера подписок: устройство не принято (например, превышен лимит устройств)
                throw LitException("Сервер подписки не выдал серверы для этого устройства. Возможно, превышен лимит устройств — освободите место в боте в разделе «Мои устройства».")
            }
            return Loaded(info, config, servers)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseHeaders(conn: HttpURLConnection): SubscriptionInfo {
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L
        conn.getHeaderField("subscription-userinfo")?.split(';')?.forEach { part ->
            val kv = part.split('=', limit = 2).map { it.trim() }
            if (kv.size != 2) return@forEach
            val n = kv[1].toDoubleOrNull()?.toLong() ?: 0L
            when (kv[0].lowercase()) {
                "upload" -> upload = n
                "download" -> download = n
                "total" -> total = n
                "expire" -> expire = n
            }
        }
        val hours = conn.getHeaderField("profile-update-interval")?.trim()?.toDoubleOrNull()?.takeIf { it > 0 } ?: 12.0
        return SubscriptionInfo(
            upload = upload,
            download = download,
            total = total,
            expire = expire,
            title = decodeHeader(conn.getHeaderField("profile-title")),
            supportUrl = conn.getHeaderField("support-url")?.trim() ?: "",
            announce = decodeHeader(conn.getHeaderField("announce")),
            updateHours = hours,
        )
    }

    /** Значение может быть закодировано как «base64:…». */
    private fun decodeHeader(value: String?): String {
        val v = value?.trim() ?: return ""
        if (v.lowercase().startsWith("base64:")) return base64Decode(v.substring(7)) ?: v
        return v
    }

    fun base64Decode(s: String): String? {
        var t = s.trim().replace("\n", "").replace("\r", "").replace("-", "+").replace("_", "/")
        while (t.length % 4 != 0) t += "="
        return runCatching { String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
    }

    fun parseBody(body: String): JSONObject {
        val text = body.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) throw LitException("Сервер подписки вернул пустой ответ.")
        if (text.startsWith("{")) {
            return runCatching { JSONObject(text) }.getOrElse { throw LitException("Не удалось прочитать конфигурацию подписки.") }
        }
        val outbounds = mutableListOf<JSONObject>()
        if (text.contains("proxies:")) {
            outbounds += ClashParser.outbounds(text)
        }
        if (outbounds.isEmpty()) {
            val list = if (text.contains("://")) text else base64Decode(text) ?: ""
            list.lineSequence().mapNotNull { UriParser.outbound(it) }.forEach { outbounds += it }
        }
        if (outbounds.isEmpty()) throw LitException("Формат подписки не поддерживается.")
        // одинаковые имена серверов недопустимы
        val used = mutableSetOf<String>()
        for (o in outbounds) {
            var tag = o.optString("tag")
            var n = 2
            while (!used.add(tag)) tag = o.optString("tag") + " $n".also { n++ }
            o.put("tag", tag)
        }
        return ConfigBuilder.template(outbounds)
    }

    private val GROUP_TYPES = setOf("selector", "urltest", "direct", "block", "dns")

    fun servers(config: JSONObject): List<Server> {
        val result = mutableListOf<Server>()
        val outbounds = config.optJSONArray("outbounds") ?: JSONArray()
        for (i in 0 until outbounds.length()) {
            val o = outbounds.optJSONObject(i) ?: continue
            val type = o.optString("type")
            val tag = o.optString("tag")
            if (type in GROUP_TYPES || tag.isEmpty()) continue
            result += Server(tag, type, o.optString("server"), o.optInt("server_port", 0))
        }
        val endpoints = config.optJSONArray("endpoints") ?: JSONArray()
        for (i in 0 until endpoints.length()) {
            val e = endpoints.optJSONObject(i) ?: continue
            val tag = e.optString("tag")
            if (tag.isEmpty()) continue
            val peer = e.optJSONArray("peers")?.optJSONObject(0)
            result += Server(tag, e.optString("type", "wireguard"), peer?.optString("address") ?: "", peer?.optInt("port", 0) ?: 0)
        }
        return result
    }
}

/** Ссылки vless:// vmess:// trojan:// ss:// hysteria2:// tuic:// → outbound ядра. */
object UriParser {
    private class Parts(val user: String, val host: String, val port: Int, val query: Map<String, String>, val name: String)

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun parts(line: String, defaultPort: Int = -1): Parts? {
        val schemeEnd = line.indexOf("://")
        if (schemeEnd < 0) return null
        var rest = line.substring(schemeEnd + 3)
        var name = ""
        rest.indexOf('#').takeIf { it >= 0 }?.let {
            name = decode(rest.substring(it + 1))
            rest = rest.substring(0, it)
        }
        val query = mutableMapOf<String, String>()
        rest.indexOf('?').takeIf { it >= 0 }?.let {
            rest.substring(it + 1).split('&').forEach { kv ->
                val p = kv.split('=', limit = 2)
                if (p[0].isNotEmpty()) query[p[0]] = if (p.size > 1) decode(p[1]) else ""
            }
            rest = rest.substring(0, it)
        }
        rest = rest.trimEnd('/')
        val at = rest.lastIndexOf('@')
        val user = if (at >= 0) decode(rest.substring(0, at)) else ""
        val hostPort = if (at >= 0) rest.substring(at + 1) else rest
        val host: String
        var port = defaultPort
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            host = hostPort.substring(1, close)
            hostPort.substring(close + 1).removePrefix(":").toIntOrNull()?.let { port = it }
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon >= 0) {
                host = hostPort.substring(0, colon)
                hostPort.substring(colon + 1).toIntOrNull()?.let { port = it }
            } else {
                host = hostPort
            }
        }
        if (host.isEmpty() || port <= 0) return null
        return Parts(user, host, port, query, name.ifEmpty { "$host:$port" })
    }

    fun outbound(raw: String): JSONObject? {
        val line = raw.trim()
        val idx = line.indexOf("://")
        if (idx <= 0) return null
        return runCatching {
            when (line.substring(0, idx).lowercase()) {
                "vless" -> vlessTrojan(line, "vless")
                "trojan" -> vlessTrojan(line, "trojan")
                "ss" -> shadowsocks(line)
                "hysteria2", "hy2" -> hysteria2(line)
                "tuic" -> tuic(line)
                "vmess" -> vmess(line)
                else -> null
            }
        }.getOrNull()
    }

    fun tls(q: Map<String, String>, host: String, forced: Boolean = false): JSONObject? {
        val security = q["security"]?.ifEmpty { null } ?: if (forced) "tls" else "none"
        if (security != "tls" && security != "reality") return null
        val tls = JSONObject().put("enabled", true)
        val sni = q["sni"]?.ifEmpty { null } ?: q["peer"]?.ifEmpty { null } ?: host
        tls.put("server_name", sni)
        q["fp"]?.takeIf { it.isNotEmpty() }?.let { tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
        q["alpn"]?.takeIf { it.isNotEmpty() }?.let { tls.put("alpn", JSONArray(it.split(','))) }
        if (q["allowInsecure"] == "1" || q["insecure"] == "1" || q["allow_insecure"] == "1") tls.put("insecure", true)
        if (security == "reality") {
            tls.put("reality", JSONObject().put("enabled", true).put("public_key", q["pbk"] ?: "").put("short_id", q["sid"] ?: ""))
            if (!tls.has("utls")) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome"))
        }
        return tls
    }

    /** null — транспорт не поддерживается ядром (например, xhttp); JSONObject.NULL — обычный TCP. */
    fun transport(q: Map<String, String>): Any? {
        return when (q["type"] ?: "tcp") {
            "tcp", "raw", "" -> JSONObject.NULL
            "ws" -> JSONObject().put("type", "ws").put("path", q["path"]?.ifEmpty { null } ?: "/").also { t ->
                q["host"]?.takeIf { it.isNotEmpty() }?.let { t.put("headers", JSONObject().put("Host", it)) }
            }
            "grpc" -> JSONObject().put("type", "grpc").put("service_name", q["serviceName"] ?: "")
            "httpupgrade" -> JSONObject().put("type", "httpupgrade").put("path", q["path"]?.ifEmpty { null } ?: "/").also { t ->
                q["host"]?.takeIf { it.isNotEmpty() }?.let { t.put("host", it) }
            }
            "http", "h2" -> JSONObject().put("type", "http").put("path", q["path"]?.ifEmpty { null } ?: "/").also { t ->
                q["host"]?.takeIf { it.isNotEmpty() }?.let { t.put("host", JSONArray().put(it)) }
            }
            else -> null
        }
    }

    private fun vlessTrojan(line: String, type: String): JSONObject? {
        val p = parts(line) ?: return null
        if (p.user.isEmpty()) return null
        val transport = transport(p.query) ?: return null
        val o = JSONObject().put("type", type).put("tag", p.name).put("server", p.host).put("server_port", p.port)
        if (type == "vless") {
            o.put("uuid", p.user)
            p.query["flow"]?.takeIf { it.isNotEmpty() }?.let { o.put("flow", it) }
        } else {
            o.put("password", p.user)
        }
        tls(p.query, p.host, forced = type == "trojan")?.let { o.put("tls", it) }
        if (transport is JSONObject) o.put("transport", transport)
        return o
    }

    private fun shadowsocks(line: String): JSONObject? {
        var body = line.substring(5)
        var tag = ""
        body.indexOf('#').takeIf { it >= 0 }?.let {
            tag = decode(body.substring(it + 1))
            body = body.substring(0, it)
        }
        body.indexOf('?').takeIf { it >= 0 }?.let { body = body.substring(0, it) }
        body = body.trimEnd('/')
        var userinfo: String
        var hostPort: String
        val at = body.lastIndexOf('@')
        if (at >= 0) {
            userinfo = body.substring(0, at)
            hostPort = body.substring(at + 1)
            val decoded = SubscriptionLoader.base64Decode(userinfo)
            userinfo = if (decoded != null && decoded.contains(':') && decoded.all { it.code in 32..126 }) decoded else decode(userinfo)
        } else {
            val decoded = SubscriptionLoader.base64Decode(body) ?: return null
            val a = decoded.lastIndexOf('@')
            if (a < 0) return null
            userinfo = decoded.substring(0, a)
            hostPort = decoded.substring(a + 1)
        }
        val colon = userinfo.indexOf(':')
        val pc = hostPort.lastIndexOf(':')
        if (colon < 0 || pc < 0) return null
        val port = hostPort.substring(pc + 1).trim('/').toIntOrNull() ?: return null
        val host = hostPort.substring(0, pc).trim('[', ']')
        return JSONObject()
            .put("type", "shadowsocks")
            .put("tag", tag.ifEmpty { "$host:$port" })
            .put("server", host)
            .put("server_port", port)
            .put("method", userinfo.substring(0, colon))
            .put("password", userinfo.substring(colon + 1))
    }

    private fun hysteria2(line: String): JSONObject? {
        val p = parts(line, 443) ?: return null
        val tls = JSONObject().put("enabled", true).put("server_name", p.query["sni"]?.ifEmpty { null } ?: p.host)
        if (p.query["insecure"] == "1") tls.put("insecure", true)
        p.query["alpn"]?.takeIf { it.isNotEmpty() }?.let { tls.put("alpn", JSONArray(it.split(','))) }
        val o = JSONObject()
            .put("type", "hysteria2").put("tag", p.name)
            .put("server", p.host).put("server_port", p.port)
            .put("password", p.user)
            .put("tls", tls)
        p.query["obfs"]?.takeIf { it.isNotEmpty() }?.let {
            o.put("obfs", JSONObject().put("type", it).put("password", p.query["obfs-password"] ?: ""))
        }
        return o
    }

    private fun tuic(line: String): JSONObject? {
        val p = parts(line, 443) ?: return null
        val colon = p.user.indexOf(':')
        val uuid = if (colon >= 0) p.user.substring(0, colon) else p.user
        val password = if (colon >= 0) p.user.substring(colon + 1) else (p.query["password"] ?: "")
        if (uuid.isEmpty()) return null
        val tls = JSONObject().put("enabled", true).put("server_name", p.query["sni"]?.ifEmpty { null } ?: p.host)
        if (p.query["allow_insecure"] == "1" || p.query["insecure"] == "1") tls.put("insecure", true)
        tls.put("alpn", JSONArray((p.query["alpn"]?.ifEmpty { null } ?: "h3").split(',')))
        return JSONObject()
            .put("type", "tuic").put("tag", p.name)
            .put("server", p.host).put("server_port", p.port)
            .put("uuid", uuid).put("password", password)
            .put("congestion_control", p.query["congestion_control"]?.ifEmpty { null } ?: "bbr")
            .put("tls", tls)
    }

    private fun vmess(line: String): JSONObject? {
        val text = SubscriptionLoader.base64Decode(line.substring(8)) ?: return null
        val j = JSONObject(text)
        fun s(k: String) = j.opt(k)?.toString()?.takeIf { it != "null" } ?: ""
        val port = s("port").toIntOrNull() ?: return null
        val host = s("add").ifEmpty { return null }
        val q = mapOf(
            "type" to s("net"), "path" to s("path"), "host" to s("host"), "serviceName" to s("path"),
            "sni" to s("sni"), "fp" to s("fp"), "alpn" to s("alpn"),
            "security" to if (s("tls") == "tls") "tls" else "none",
        )
        val transport = transport(q) ?: return null
        val o = JSONObject()
            .put("type", "vmess").put("tag", s("ps").ifEmpty { "$host:$port" })
            .put("server", host).put("server_port", port)
            .put("uuid", s("id"))
            .put("security", s("scy").ifEmpty { "auto" })
            .put("alter_id", s("aid").toIntOrNull() ?: 0)
        tls(q, host)?.let { o.put("tls", it) }
        if (transport is JSONObject) o.put("transport", transport)
        return o
    }
}

/** Запасной разбор подписки в формате Clash (YAML, раздел proxies). */
object ClashParser {
    private class Line(val indent: Int, val text: String)

    fun outbounds(yaml: String): List<JSONObject> {
        val lines = yaml.lines().map { it.replace("\t", "  ") }
        val start = lines.indexOfFirst { it.trimEnd() == "proxies:" }
        if (start < 0) return emptyList()
        val body = mutableListOf<Line>()
        for (i in start + 1 until lines.size) {
            val l = lines[i]
            if (l.isBlank() || l.trimStart().startsWith("#")) continue
            val indent = l.length - l.trimStart().length
            if (indent == 0 && !l.startsWith("-")) break
            body += Line(indent, l.trim())
        }
        val items = mutableListOf<Map<String, Any?>>()
        var i = 0
        while (i < body.size) {
            val l = body[i]
            if (!l.text.startsWith("-")) {
                i++
                continue
            }
            val first = l.text.removePrefix("-").trim()
            if (first.startsWith("{")) {
                items += flowMap(first)
                i++
                continue
            }
            // блочная запись: «- name: x» и строки с большим отступом
            val itemIndent = l.indent
            val block = mutableListOf(Line(itemIndent + 2, first))
            i++
            while (i < body.size && body[i].indent > itemIndent) {
                block += body[i]
                i++
            }
            items += blockMap(block, 0).first
        }
        return items.mapNotNull { runCatching { convert(it) }.getOrNull() }
    }

    private fun scalar(raw: String): Any? {
        val s = raw.trim()
        if (s.startsWith("{")) return flowMap(s)
        if (s.startsWith("[")) return splitFlow(s.removePrefix("[").removeSuffix("]")).map { scalar(it) }
        if ((s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) || (s.startsWith("'") && s.endsWith("'") && s.length >= 2)) {
            return s.substring(1, s.length - 1)
        }
        return s
    }

    private fun splitFlow(s: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var quote: Char? = null
        val cur = StringBuilder()
        for (c in s) {
            when {
                quote != null -> {
                    if (c == quote) quote = null
                    cur.append(c)
                }
                c == '"' || c == '\'' -> {
                    quote = c
                    cur.append(c)
                }
                c == '{' || c == '[' -> {
                    depth++
                    cur.append(c)
                }
                c == '}' || c == ']' -> {
                    depth--
                    cur.append(c)
                }
                c == ',' && depth == 0 -> {
                    out += cur.toString().trim()
                    cur.clear()
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    private fun flowMap(s: String): Map<String, Any?> {
        val inner = s.trim().removePrefix("{").removeSuffix("}")
        val map = mutableMapOf<String, Any?>()
        for (entry in splitFlow(inner)) {
            val c = entry.indexOf(':')
            if (c <= 0) continue
            map[entry.substring(0, c).trim().trim('"', '\'')] = scalar(entry.substring(c + 1))
        }
        return map
    }

    /** Разбор блока «ключ: значение» с вложенностью по отступам. Возвращает карту и число прочитанных строк. */
    private fun blockMap(lines: List<Line>, from: Int): Pair<Map<String, Any?>, Int> {
        val map = mutableMapOf<String, Any?>()
        if (from >= lines.size) return map to 0
        val indent = lines[from].indent
        var i = from
        while (i < lines.size && lines[i].indent >= indent) {
            val l = lines[i]
            if (l.indent > indent) {
                i++
                continue
            }
            val c = l.text.indexOf(':')
            if (c <= 0 || l.text.startsWith("-")) {
                i++
                continue
            }
            val key = l.text.substring(0, c).trim().trim('"', '\'')
            val value = l.text.substring(c + 1).trim()
            i++
            if (value.isNotEmpty()) {
                map[key] = scalar(value)
            } else if (i < lines.size && lines[i].indent > indent) {
                if (lines[i].text.startsWith("-")) {
                    val list = mutableListOf<Any?>()
                    while (i < lines.size && lines[i].indent > indent && lines[i].text.startsWith("-")) {
                        list += scalar(lines[i].text.removePrefix("-"))
                        i++
                    }
                    map[key] = list
                } else {
                    val (child, used) = blockMap(lines, i)
                    map[key] = child
                    i += used
                }
            }
        }
        return map to (i - from)
    }

    private fun Map<String, Any?>.str(key: String): String = this[key]?.toString() ?: ""
    private fun Map<String, Any?>.bool(key: String): Boolean = this[key]?.toString() == "true"

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.sub(key: String): Map<String, Any?> = this[key] as? Map<String, Any?> ?: emptyMap()

    private fun convert(p: Map<String, Any?>): JSONObject? {
        val host = p.str("server")
        val port = p.str("port").toIntOrNull() ?: return null
        if (host.isEmpty()) return null
        val name = p.str("name").ifEmpty { "$host:$port" }
        val q = mutableMapOf<String, String>()
        val network = p.str("network")
        q["type"] = network.ifEmpty { "tcp" }
        val ws = p.sub("ws-opts")
        q["path"] = ws.str("path")
        q["host"] = ws.sub("headers").str("Host")
        q["serviceName"] = p.sub("grpc-opts").str("grpc-service-name")
        q["sni"] = p.str("servername").ifEmpty { p.str("sni") }
        q["fp"] = p.str("client-fingerprint")
        if (p.bool("skip-cert-verify")) q["insecure"] = "1"
        (p["alpn"] as? List<*>)?.let { q["alpn"] = it.joinToString(",") }
        val reality = p.sub("reality-opts")
        if (reality.isNotEmpty()) {
            q["security"] = "reality"
            q["pbk"] = reality.str("public-key")
            q["sid"] = reality.str("short-id")
        } else if (p.bool("tls")) {
            q["security"] = "tls"
        }
        val o = JSONObject().put("tag", name).put("server", host).put("server_port", port)
        when (p.str("type")) {
            "ss" -> o.put("type", "shadowsocks").put("method", p.str("cipher")).put("password", p.str("password"))
            "vmess", "vless", "trojan" -> {
                val type = p.str("type")
                val transport = UriParser.transport(q) ?: return null
                o.put("type", type)
                when (type) {
                    "vmess" -> o.put("uuid", p.str("uuid")).put("security", p.str("cipher").ifEmpty { "auto" }).put("alter_id", p.str("alterId").toIntOrNull() ?: 0)
                    "vless" -> {
                        o.put("uuid", p.str("uuid"))
                        p.str("flow").takeIf { it.isNotEmpty() }?.let { o.put("flow", it) }
                    }
                    else -> o.put("password", p.str("password"))
                }
                UriParser.tls(q, host, forced = type == "trojan")?.let { o.put("tls", it) }
                if (transport is JSONObject) o.put("transport", transport)
            }
            "hysteria2" -> {
                o.put("type", "hysteria2").put("password", p.str("password"))
                val tls = JSONObject().put("enabled", true).put("server_name", q["sni"]?.ifEmpty { null } ?: host)
                if (q["insecure"] == "1") tls.put("insecure", true)
                o.put("tls", tls)
                p.str("obfs").takeIf { it.isNotEmpty() }?.let { o.put("obfs", JSONObject().put("type", it).put("password", p.str("obfs-password"))) }
            }
            "tuic" -> {
                val tls = JSONObject().put("enabled", true).put("server_name", q["sni"]?.ifEmpty { null } ?: host)
                if (q["insecure"] == "1") tls.put("insecure", true)
                tls.put("alpn", JSONArray((q["alpn"]?.ifEmpty { null } ?: "h3").split(',')))
                o.put("type", "tuic").put("uuid", p.str("uuid")).put("password", p.str("password"))
                    .put("congestion_control", p.str("congestion-controller").ifEmpty { "bbr" }).put("tls", tls)
            }
            else -> return null
        }
        return o
    }
}
