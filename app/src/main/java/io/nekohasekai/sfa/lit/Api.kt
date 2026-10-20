package io.nekohasekai.sfa.lit

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Запросы к существующему API сайта LitEnergy: вход по почте или через бота, тарифы, ссылка на подписку.
 * Каждый запрос — новое соединение, таймаут 15 с, повтор при сетевой ошибке.
 */
object Api {
    class Account(val token: String, val subscriptionUrl: String?, val title: String)
    class Bootstrap(val subscriptionUrl: String?, val status: String, val tariffs: List<Tariff>)

    private fun json(method: String, path: String, body: JSONObject? = null, session: String? = null): JSONObject {
        var last: Exception = LitException(NET_ERROR)
        for (attempt in 0 until 3) {
            if (attempt > 0) Thread.sleep(800L * attempt)
            try {
                return once(method, path, body, session)
            } catch (e: LitException) {
                throw e
            } catch (e: Exception) {
                last = LitException(NET_ERROR)
                if (method == "POST") break // не повторяем отправку кода
            }
        }
        throw last
    }

    private fun once(method: String, path: String, body: JSONObject?, session: String?): JSONObject {
        val conn = URL(Links.SITE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.useCaches = false
            conn.setRequestProperty("Connection", "close")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", Device.apiUserAgent)
            if (session != null) conn.setRequestProperty("X-LitEnergy-Session", session)
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code !in 200..299) {
                if (code in setOf(502, 503, 504, 520, 521, 522, 523, 524) && json.length() == 0) throw IOException("gateway $code")
                if (code == 401) throw LitException(json.optString("message").ifEmpty { "Войдите заново." })
                throw LitException(json.optString("message").ifEmpty { "Ошибка сервера ($code)" })
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    // вход по почте

    fun requestEmailCode(context: Context, email: String) {
        json("POST", "/api/web/auth/email/request", JSONObject().put("email", email).put("device_id", Device.hwid(context)))
    }

    fun verifyEmailCode(context: Context, email: String, code: String): Account {
        val j = json("POST", "/api/web/auth/email/verify", JSONObject().put("email", email).put("code", code).put("device_id", Device.hwid(context)))
        val token = j.optString("token")
        if (token.isEmpty()) throw LitException("Не удалось войти. Попробуйте ещё раз.")
        return Account(token, j.optString("subscription_url").ifEmpty { null }, email)
    }

    // вход через бота

    fun startBotLogin(): Pair<String, String> {
        val j = json("GET", "/api/web/auth/request-bot?client=app")
        val code = j.optString("code")
        val url = j.optString("url")
        if (code.isEmpty() || url.isEmpty()) throw LitException("Не удалось получить ссылку на бота.")
        return code to url
    }

    /** null — пользователь ещё не подтвердил вход в боте. */
    fun pollBotLogin(code: String): Account? {
        val j = json("GET", "/api/web/auth/poll-bot?code=" + URLEncoder.encode(code, "UTF-8"))
        if (j.optString("status") != "authenticated") return null
        val token = j.optString("token").ifEmpty { return null }
        val user = j.optJSONObject("user") ?: JSONObject()
        val username = user.optString("username")
        val title = if (username.isNotEmpty()) "@$username" else user.optString("first_name").ifEmpty { "Telegram" }
        return Account(token, null, title)
    }

    // данные кабинета

    fun bootstrap(token: String): Bootstrap {
        val j = json("GET", "/api/miniapp/bootstrap", session = token)
        val sub = j.optJSONObject("subscription") ?: JSONObject()
        val rows = j.optJSONArray("tariffs")
        val tariffs = mutableListOf<Tariff>()
        if (rows != null) {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val code = row.optString("code")
                if (code.isEmpty()) continue
                val days = row.optInt("days", 0)
                tariffs += Tariff(code, days, row.optString("title").ifEmpty { "$days дней" }, row.opt("price")?.toString() ?: "0")
            }
        }
        return Bootstrap(sub.optString("url").ifEmpty { null }, sub.optString("status").ifEmpty { "NONE" }, tariffs)
    }

    fun refreshSession(token: String): String? {
        val j = json("POST", "/api/web/auth/refresh", JSONObject(), session = token)
        return j.optString("token").ifEmpty { null }
    }
}
