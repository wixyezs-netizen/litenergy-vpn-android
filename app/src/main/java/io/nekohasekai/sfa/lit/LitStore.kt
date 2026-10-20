package io.nekohasekai.sfa.lit

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.UUID

/** Состояние приложения: подписки, выбранный сервер, настройки. Ссылки подписок — только в SecureStore. */
object LitStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val PREFS = "lit_app"
    private const val K_PROFILE = "profile_id"
    private const val PROFILE_NAME = "LitEnergy VPN"

    private lateinit var app: Context
    private val prefs: SharedPreferences get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // демо-режим для скриншотов сборки (флаги из intent)
    var demo = false
        private set
    var demoSheet by mutableStateOf<String?>(null)

    /** Ссылка из диплинка litenergy://import?url=… или QR-кода, ждущая добавления. */
    var pendingImport by mutableStateOf<String?>(null)

    var subscriptions by mutableStateOf(listOf<Subscription>())
        private set
    var currentId by mutableStateOf<String?>(null)
        private set
    var settings by mutableStateOf(ConnectionSettings())
        private set
    var selected by mutableStateOf(mapOf<String, String>())
        private set
    var tariffs by mutableStateOf(Tariff.fallback)
        private set
    var accountTitle by mutableStateOf<String?>(null)
        private set
    var updating by mutableStateOf(false)
        private set

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        initialized = true
        val p = prefs
        subscriptions = p.getString("subscriptions", null)?.let { runCatching { json.decodeFromString<List<Subscription>>(it) }.getOrNull() } ?: emptyList()
        settings = p.getString("settings", null)?.let { runCatching { json.decodeFromString<ConnectionSettings>(it) }.getOrNull() } ?: ConnectionSettings()
        selected = p.getString("selected", null)?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap()
        tariffs = p.getString("tariffs", null)?.let { runCatching { json.decodeFromString<List<Tariff>>(it) }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: Tariff.fallback
        accountTitle = p.getString("account", null)
        currentId = p.getString("current", null)
        if (current == null) currentId = subscriptions.firstOrNull()?.id
    }

    fun enableDemo(context: Context, expired: Boolean, sheet: String?) {
        init(context)
        demo = true
        demoSheet = sheet
        val now = System.currentTimeMillis() / 1000
        val info = SubscriptionInfo(
            download = 13_314_398_617,
            upload = 1_020_000_000,
            total = if (expired) 0 else 107_374_182_400,
            expire = if (expired) now - 86_400 else now + 28 * 86_400,
            title = "LitEnergy VPN",
        )
        val servers = listOf(
            Server("🇩🇪 Германия · Игровой", "shadowsocks", "127.0.0.1", 1234),
            Server("🇫🇮 Финляндия · Игровой", "vless", "127.0.0.1", 8443),
            Server("🇫🇮 Финляндия · Видео", "shadowsocks", "127.0.0.1", 1234),
            Server("🇩🇪 Германия · Видео", "vless", "127.0.0.1", 8443),
        )
        subscriptions = listOf(Subscription("demo", "LitEnergy VPN", info, servers, System.currentTimeMillis()))
        currentId = "demo"
    }

    val current: Subscription? get() = subscriptions.firstOrNull { it.id == currentId }
    val session: String? get() = SecureStore.get(app, "session")
    val isLoggedIn: Boolean get() = session != null

    val selectedServer: String
        get() = currentId?.let { selected[it] } ?: ConfigBuilder.AUTO_TAG

    val selectedServerName: String
        get() {
            val tag = selectedServer
            return if (tag == ConfigBuilder.AUTO_TAG || current?.servers?.none { it.tag == tag } != false) "Автовыбор" else tag
        }

    fun selectServer(tag: String) {
        val id = currentId ?: return
        selected = selected + (id to tag)
        prefs.edit().putString("selected", json.encodeToString(selected)).apply()
    }

    fun switchTo(id: String) {
        currentId = id
        prefs.edit().putString("current", id).apply()
    }

    fun updateSettings(value: ConnectionSettings) {
        settings = value
        prefs.edit().putString("settings", json.encodeToString(value)).apply()
    }

    // ---------------------------------------------------------------- подписки

    /** Добавить по ссылке (буфер, QR, диплинк). Возвращает id. */
    suspend fun importLink(raw: String): String {
        val url = SubscriptionLoader.normalize(raw)
            ?: throw LitException("Это не ссылка на подписку. Скопируйте ссылку из бота или личного кабинета.")
        // та же ссылка уже добавлена — просто обновляем
        subscriptions.firstOrNull { SecureStore.get(app, "sub.${it.id}") == url }?.let {
            switchTo(it.id)
            refresh(it.id)
            return it.id
        }
        updating = true
        try {
            val loaded = withContext(Dispatchers.IO) { SubscriptionLoader.fetch(app, url) }
            val id = UUID.randomUUID().toString()
            withContext(Dispatchers.IO) {
                SecureStore.set(app, "sub.$id", url)
                configFile(id).writeText(loaded.config.toString())
            }
            val name = loaded.info.title.ifEmpty { "LitEnergy VPN" }
            subscriptions = subscriptions + Subscription(id, name, loaded.info, loaded.servers, System.currentTimeMillis())
            persist()
            switchTo(id)
            return id
        } finally {
            updating = false
        }
    }

    suspend fun refresh(id: String? = null) {
        val target = id ?: currentId ?: return
        if (demo) return
        val url = withContext(Dispatchers.IO) { SecureStore.get(app, "sub.$target") }
            ?: throw LitException("Ссылка подписки не найдена. Добавьте подписку заново.")
        updating = true
        try {
            val loaded = withContext(Dispatchers.IO) {
                SubscriptionLoader.fetch(app, url).also { configFile(target).writeText(it.config.toString()) }
            }
            subscriptions = subscriptions.map {
                if (it.id != target) {
                    it
                } else {
                    it.copy(
                        name = loaded.info.title.ifEmpty { it.name },
                        info = loaded.info,
                        servers = loaded.servers,
                        lastUpdated = System.currentTimeMillis(),
                    )
                }
            }
            persist()
        } finally {
            updating = false
        }
    }

    /** Пора обновить по интервалу из подписки. */
    val needsRefresh: Boolean
        get() {
            val sub = current ?: return false
            if (demo) return false
            if (sub.lastUpdated <= 0) return true
            return System.currentTimeMillis() - sub.lastUpdated > (sub.info.updateHours * 3_600_000).toLong()
        }

    suspend fun remove(id: String) {
        withContext(Dispatchers.IO) {
            SecureStore.set(app, "sub.$id", null)
            configFile(id).delete()
        }
        subscriptions = subscriptions.filter { it.id != id }
        selected = selected - id
        prefs.edit().putString("selected", json.encodeToString(selected)).apply()
        persist()
        if (currentId == id) {
            currentId = subscriptions.firstOrNull()?.id
            prefs.edit().putString("current", currentId).apply()
        }
        if (subscriptions.isEmpty()) deleteProfile()
    }

    /** «Сбросить и удалить подписку»: все подписки, вход и настройки. */
    suspend fun resetAll() {
        subscriptions.map { it.id }.forEach { remove(it) }
        signOut()
        updateSettings(ConnectionSettings())
        deleteProfile()
    }

    /** Ссылка подписки для показа: только адрес сервера и конец пути. */
    fun maskedLink(id: String): String {
        val raw = SecureStore.get(app, "sub.$id") ?: return ""
        val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull() ?: return ""
        val path = uri.path ?: ""
        val tail = if (path.length > 4) path.takeLast(4) else ""
        return "${uri.host ?: ""}/…$tail"
    }

    // ---------------------------------------------------------------- аккаунт

    suspend fun signIn(account: Api.Account) {
        withContext(Dispatchers.IO) { SecureStore.set(app, "session", account.token) }
        accountTitle = account.title
        prefs.edit().putString("account", account.title).apply()
        var link = account.subscriptionUrl
        val boot = withContext(Dispatchers.IO) { runCatching { Api.bootstrap(account.token) }.getOrNull() }
        if (boot != null) {
            if (boot.tariffs.isNotEmpty()) saveTariffs(boot.tariffs)
            if (link.isNullOrEmpty()) link = boot.subscriptionUrl
        }
        if (link.isNullOrEmpty()) throw LitException("Вход выполнен, но активной подписки нет. Оформите её в боте или на сайте.")
        importLink(link)
    }

    fun signOut() {
        SecureStore.set(app, "session", null)
        accountTitle = null
        prefs.edit().remove("account").apply()
    }

    /** Тарифы из кабинета (если вошли) и продление сессии. */
    suspend fun loadAccount() {
        if (demo) return
        val token = withContext(Dispatchers.IO) { session } ?: return
        withContext(Dispatchers.IO) {
            runCatching { Api.bootstrap(token) }.getOrNull()?.tariffs?.takeIf { it.isNotEmpty() }?.let {
                withContext(Dispatchers.Main) { saveTariffs(it) }
            }
            runCatching { Api.refreshSession(token) }.getOrNull()?.let { SecureStore.set(app, "session", it) }
        }
    }

    private fun saveTariffs(list: List<Tariff>) {
        tariffs = list
        prefs.edit().putString("tariffs", json.encodeToString(list)).apply()
    }

    // ---------------------------------------------------------------- профиль ядра

    /** Время подключения для таймера (сохраняется, чтобы не сбрасываться при повороте экрана). */
    fun connectedAt(connected: Boolean): Long {
        if (!connected) {
            prefs.edit().remove("connected_at").apply()
            return 0L
        }
        val t = prefs.getLong("connected_at", 0L)
        if (t > 0) return t
        val now = System.currentTimeMillis()
        prefs.edit().putLong("connected_at", now).apply()
        return now
    }

    val hasConfig: Boolean get() = demo || (currentId?.let { configFile(it).exists() } ?: false)

    /**
     * Собирает конфигурацию выбранной подписки, записывает профиль ядра и делает его активным.
     * reload — перезапустить уже работающий VPN с новой конфигурацией.
     */
    suspend fun applyProfile(reload: Boolean) = withContext(Dispatchers.IO) {
        val content = if (demo) {
            ConfigBuilder.demo()
        } else {
            val id = currentId ?: throw LitException("Сначала добавьте подписку.")
            val file = configFile(id)
            if (!file.exists()) throw LitException("Конфигурация не загружена. Обновите подписку.")
            val source = runCatching { JSONObject(file.readText()) }.getOrElse { throw LitException("Конфигурация повреждена. Обновите подписку.") }
            ConfigBuilder.build(source, selectedServer, settings)
        }
        try {
            Libbox.checkConfig(content)
        } catch (e: Exception) {
            throw LitException("Конфигурация подписки не подходит для этого устройства. Обновите подписку или напишите в поддержку.")
        }
        val profile = writeProfile(content)
        val changed = Settings.selectedProfile != profile.id
        if (changed) Settings.selectedProfile = profile.id
        if (reload) runCatching { Libbox.newStandaloneCommandClient().serviceReload() }
        Unit
    }

    private suspend fun writeProfile(content: String): Profile {
        val id = prefs.getLong(K_PROFILE, -1L)
        var profile = if (id > 0) ProfileManager.get(id) else null
        if (profile == null) {
            val typed = TypedProfile().apply {
                type = TypedProfile.Type.Local
                lastUpdated = Date()
            }
            val dir = File(app.filesDir, "configs").also { it.mkdirs() }
            val file = File(dir, "${ProfileManager.nextFileID()}.json")
            file.writeText(content)
            typed.path = file.path
            profile = ProfileManager.create(Profile(name = PROFILE_NAME, typed = typed).apply { userOrder = ProfileManager.nextOrder() })
        } else {
            val file = File(profile.typed.path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            profile.typed.lastUpdated = Date()
            ProfileManager.update(profile)
        }
        prefs.edit().putLong(K_PROFILE, profile.id).apply()
        return profile
    }

    private suspend fun deleteProfile() = withContext(Dispatchers.IO) {
        val id = prefs.getLong(K_PROFILE, -1L)
        if (id > 0) ProfileManager.get(id)?.let { ProfileManager.delete(it) }
        prefs.edit().remove(K_PROFILE).apply()
    }

    // ---------------------------------------------------------------- хранение

    private fun configFile(id: String): File {
        val dir = File(app.filesDir, "subscriptions").also { it.mkdirs() }
        return File(dir, "$id.json")
    }

    private fun persist() {
        if (!demo) prefs.edit().putString("subscriptions", json.encodeToString(subscriptions)).apply()
    }
}
