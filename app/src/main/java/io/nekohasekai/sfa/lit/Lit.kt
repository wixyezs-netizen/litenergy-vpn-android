package io.nekohasekai.sfa.lit

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.nekohasekai.sfa.BuildConfig
import kotlinx.serialization.Serializable
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.ceil

/** Ссылки сервиса в одном месте. */
object Links {
    const val SITE = "https://site.litenergyapp.store"
    const val PLANS = "https://site.litenergyapp.store/plans/"
    const val BOT = "https://t.me/VPN_LitEnergy_bot"
    const val SUPPORT = "https://t.me/wixyezz"
    const val CHANNEL = "https://t.me/+65OxDmkfjv04ZDhh"
    const val TERMS = "https://telegra.ph/Polzovatelskoe-soglasheniya-10-19"
    const val PRIVACY = "https://telegra.ph/Politika-konfidencialnosti-LitEnergy-VPN-10-19-2"
    const val RELEASES = "https://github.com/wixyezs-netizen/litenergy-vpn-android/releases/latest"
}

class LitException(message: String) : Exception(message)

const val NET_ERROR = "Сервер не отвечает. Проверьте интернет и попробуйте ещё раз."

@Serializable
data class Tariff(val code: String, val days: Int, val title: String, val price: String) {
    val priceText: String
        get() {
            val v = price.replace(',', '.').toDoubleOrNull() ?: 0.0
            return if (v == Math.rint(v)) "${v.toLong()} ₽" else String.format(Locale.US, "%.2f ₽", v).replace('.', ',')
        }

    companion object {
        /** Запасные тарифы, если сервер недоступен или пользователь не вошёл. */
        val fallback = listOf(
            Tariff("d7", 7, "7 дней", "50"),
            Tariff("d30", 30, "30 дней", "100"),
            Tariff("d90", 90, "90 дней", "300"),
            Tariff("d180", 180, "180 дней", "600"),
            Tariff("d365", 365, "365 дней", "1200"),
            Tariff("family3", 30, "Семейный · 30 дней", "150"),
        )
    }
}

@Serializable
data class SubscriptionInfo(
    val upload: Long = 0,
    val download: Long = 0,
    /** 0 — безлимит. */
    val total: Long = 0,
    /** Секунды Unix, 0 — бессрочно. */
    val expire: Long = 0,
    val title: String = "",
    val supportUrl: String = "",
    val announce: String = "",
    val updateHours: Double = 12.0,
) {
    val used: Long get() = upload + download
    val isExpired: Boolean get() = expire > 0 && expire * 1000 < System.currentTimeMillis()
    val isTrafficOver: Boolean get() = total > 0 && used >= total

    val daysLeft: Int?
        get() = if (expire <= 0) null else ceil((expire * 1000 - System.currentTimeMillis()) / 86_400_000.0).toInt().coerceAtLeast(0)

    val trafficLine: String
        get() = when {
            total > 0 -> "Трафик: ${ByteFormat.text(used)} из ${ByteFormat.text(total)}"
            used > 0 -> "Трафик: ${ByteFormat.text(used)} · безлимит"
            else -> "Безлимитный трафик"
        }

    val expireLine: String
        get() {
            if (expire <= 0) return "Бессрочная подписка"
            val date = SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date(expire * 1000))
            return (if (isExpired) "Закончилась " else "До ") + date
        }
}

@Serializable
data class Server(val tag: String, val type: String, val host: String, val port: Int) {
    val protocolName: String
        get() = when (type) {
            "vless" -> "VLESS"
            "vmess" -> "VMess"
            "trojan" -> "Trojan"
            "shadowsocks" -> "Shadowsocks"
            "hysteria2" -> "Hysteria 2"
            "tuic" -> "TUIC"
            "wireguard" -> "WireGuard"
            else -> type.uppercase()
        }

    /** UDP-протоколы: TCP-пинг до них не измерить. */
    val isUdp: Boolean get() = type in setOf("hysteria2", "hysteria", "tuic", "wireguard")
}

@Serializable
data class Subscription(
    val id: String,
    val name: String,
    val info: SubscriptionInfo,
    val servers: List<Server>,
    val lastUpdated: Long = 0,
)

@Serializable
data class ConnectionSettings(
    /** all — весь трафик, ru — российские сайты напрямую. */
    val routing: String = ROUTING_ALL,
    /** auto или IP-адрес. */
    val dns: String = "auto",
    val ipv6: Boolean = true,
    val connectOnLaunch: Boolean = false,
) {
    companion object {
        const val ROUTING_ALL = "all"
        const val ROUTING_RU = "ru"
    }
}

data class DnsPreset(val name: String, val address: String)

val DNS_PRESETS = listOf(
    DnsPreset("Google", "8.8.8.8"),
    DnsPreset("Cloudflare", "1.1.1.1"),
    DnsPreset("Яндекс", "77.88.8.8"),
    DnsPreset("AdGuard", "94.140.14.14"),
)

object ByteFormat {
    fun text(bytes: Long): String {
        val b = bytes.toDouble()
        if (b >= 1_073_741_824) {
            val gb = b / 1_073_741_824
            val s = if (gb >= 100 || Math.rint(gb) == gb) String.format(Locale.US, "%.0f", Math.rint(gb)) else String.format(Locale.US, "%.1f", gb)
            return s.replace('.', ',') + " ГБ"
        }
        if (b >= 1_048_576) return String.format(Locale.US, "%.0f МБ", b / 1_048_576)
        return String.format(Locale.US, "%.0f КБ", maxOf(0.0, b / 1024))
    }
}

/**
 * Зашифрованное хранилище (ключ AES-GCM в Android Keystore): ссылки подписок и токен входа.
 * Значения никогда не пишутся в лог.
 */
object SecureStore {
    private const val PREFS = "lit_secure"
    private const val ALIAS = "litenergy_secure_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun get(context: Context, name: String): String? {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    fun set(context: Context, name: String, value: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }
}

/** Данные устройства для сервера подписок (лимит устройств в панели). */
object Device {
    @Volatile
    private var cachedHwid: String? = null

    /** Постоянный идентификатор: ANDROID_ID не меняется при переустановке приложения. */
    @SuppressLint("HardwareIds")
    fun hwid(context: Context): String {
        cachedHwid?.let { return it }
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        }.getOrNull()
        val id = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            androidId.lowercase(Locale.ROOT)
        } else {
            val prefs = context.applicationContext.getSharedPreferences("lit_app", Context.MODE_PRIVATE)
            prefs.getString("hwid", null) ?: UUID.randomUUID().toString().replace("-", "").also {
                prefs.edit().putString("hwid", it).apply()
            }
        }
        cachedHwid = id
        return id
    }

    val model: String
        get() {
            val m = Build.MODEL ?: ""
            val b = (Build.MANUFACTURER ?: "").replaceFirstChar { it.uppercase() }
            return (if (m.startsWith(b, ignoreCase = true)) m else "$b $m").trim().take(64)
        }

    val osVersion: String get() = Build.VERSION.RELEASE ?: ""

    val appVersion: String get() = BuildConfig.VERSION_NAME

    /** Начинается с «SFA» — так сервер подписок отдаёт готовую конфигурацию для нашего ядра. */
    val userAgent: String get() = "SFA/1.14.3 (LitEnergyVPN $appVersion; Android $osVersion)"

    val apiUserAgent: String get() = "LitEnergyVPN/$appVersion (Android $osVersion)"
}
