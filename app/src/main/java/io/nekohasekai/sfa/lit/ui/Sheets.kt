package io.nekohasekai.sfa.lit.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.compose.component.qr.QRScanSheet
import io.nekohasekai.sfa.compose.screen.qrscan.QRScanResult
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.lit.ConfigBuilder
import io.nekohasekai.sfa.lit.ConnectionSettings
import io.nekohasekai.sfa.lit.DNS_PRESETS
import io.nekohasekai.sfa.lit.Links
import io.nekohasekai.sfa.lit.LitStore
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

// ---------------------------------------------------------------- пинг

/** Время установки TCP-соединения с сервером, мс (-1 — нет ответа). Мимо VPN, через основную сеть. */
internal object Ping {
    @Suppress("DEPRECATION")
    private fun underlying(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    suspend fun tcp(context: Context, host: String, port: Int, timeout: Int = 3000): Int = withContext(Dispatchers.IO) {
        runCatching {
            val net = underlying(context)
            val address = net?.getByName(host) ?: InetAddress.getByName(host)
            val socket = net?.socketFactory?.createSocket() ?: Socket()
            socket.use {
                val start = SystemClock.elapsedRealtime()
                it.connect(InetSocketAddress(address, port), timeout)
                (SystemClock.elapsedRealtime() - start).toInt()
            }
        }.getOrDefault(-1)
    }
}

// ---------------------------------------------------------------- серверы

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServersSheet(onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers = LitStore.current?.servers ?: emptyList()
    var pings by remember { mutableStateOf(mapOf<String, Int>()) }
    var measuring by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val selected = LitStore.selectedServer
    val isAuto = selected == ConfigBuilder.AUTO_TAG || servers.none { it.tag == selected }

    fun measure() {
        if (measuring) return
        if (LitStore.demo) {
            val demo = listOf(38, 52, 61, 44)
            pings = servers.mapIndexed { i, s -> s.tag to demo[i % demo.size] }.toMap()
            return
        }
        measuring = true
        scope.launch {
            try {
                val result = coroutineScope {
                    servers.filter { !it.isUdp && it.host.isNotEmpty() && it.port > 0 }
                        .map { s -> async { s.tag to Ping.tcp(context, s.host, s.port) } }
                        .awaitAll()
                }
                pings = result.toMap()
            } finally {
                measuring = false
            }
        }
    }

    LaunchedEffect(Unit) { measure() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Raised) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetHeader("Серверы")
            if (servers.size > 6) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Поиск", color = Dim) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }
            RowGroup {
                SelectRow("Автовыбор", "Самый быстрый сервер подбирается автоматически", selected = isAuto) {
                    onSelect(ConfigBuilder.AUTO_TAG)
                    onDismiss()
                }
            }
            SectionTitle("Локации")
            val list = servers.filter { query.isBlank() || it.tag.contains(query.trim(), ignoreCase = true) }
            RowGroup {
                list.forEachIndexed { i, s ->
                    if (i > 0) RowDivider()
                    val ms = pings[s.tag]
                    val pingText = when {
                        s.isUdp -> ""
                        ms == null -> if (measuring) "…" else ""
                        ms < 0 -> "нет ответа"
                        else -> "$ms мс"
                    }
                    SelectRow(s.tag, s.protocolName, trailing = pingText, selected = !isAuto && s.tag == selected) {
                        onSelect(s.tag)
                        onDismiss()
                    }
                }
                if (list.isEmpty()) {
                    Text("Ничего не найдено", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            PillButton(Icons.Rounded.Speed, if (measuring) "Проверяем…" else "Проверить пинг") { measure() }
        }
    }
}

// ---------------------------------------------------------------- тарифы

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlansSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Raised) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetHeader("Тарифы")
            Text(
                "Оплата проходит в нашем боте или на сайте. После оплаты подписка продлится автоматически — обновите её в приложении.",
                color = Muted, fontSize = 14.sp,
            )
            Spacer(Modifier.height(14.dp))
            RowGroup {
                LitStore.tariffs.forEachIndexed { i, t ->
                    if (i > 0) RowDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            if (!t.title.contains(t.days.toString())) Text("${t.days} дн.", color = Muted, fontSize = 13.sp)
                        }
                        Text(t.priceText, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            PrimaryButton("Оплатить в Telegram") { open(context, Links.BOT) }
            Spacer(Modifier.height(10.dp))
            PillButton(null, "Оплатить на сайте") { open(context, Links.PLANS) }
        }
    }
}

// ---------------------------------------------------------------- меню

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MenuSheet(onDismiss: () -> Unit, onOpen: (Sheet) -> Unit) {
    val context = LocalContext.current
    val count = LitStore.subscriptions.size
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Raised) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetHeader("Меню")
            SheetItem("Купить или продлить", "Тарифы, оплата в боте или на сайте") { onOpen(Sheet.Plans) }
            SheetItem("Мои подписки", "$count · добавить или переключить") { onOpen(Sheet.Subscriptions) }
            SheetItem("Поддержка", "Ответим в Telegram") { open(context, supportUrl(LitStore.current)) }
            SheetItem("Наш канал", "Новости и обновления") { open(context, Links.CHANNEL) }
            SheetItem("Пользовательское соглашение", "Условия использования сервиса") { open(context, Links.TERMS) }
            SheetItem("Политика конфиденциальности", "Как мы обращаемся с данными") { open(context, Links.PRIVACY) }
            SheetItem("Скачать последнюю версию", "Страница выпусков приложения") { open(context, Links.RELEASES) }
            Spacer(Modifier.height(12.dp))
            Text("LitEnergy VPN · версия ${BuildConfig.VERSION_NAME}", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

// ---------------------------------------------------------------- настройки

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(
    status: Status,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onOpen: (Sheet) -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = LitStore.settings
    var customDns by remember { mutableStateOf(if (s.dns != "auto" && DNS_PRESETS.none { it.address == s.dns }) s.dns else "") }
    var customMode by remember { mutableStateOf(s.dns != "auto" && DNS_PRESETS.none { it.address == s.dns }) }
    var note by remember { mutableStateOf<String?>(null) }
    var dnsError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var perAppEnabled by remember { mutableStateOf(false) }
    var perAppMode by remember { mutableIntStateOf(Settings.PER_APP_PROXY_EXCLUDE) }
    var perAppCount by remember { mutableIntStateOf(0) }
    val loggedIn = remember { LitStore.isLoggedIn }

    LaunchedEffect(showApps) {
        if (showApps) return@LaunchedEffect
        val (e, m, c) = withContext(Dispatchers.IO) { Triple(Settings.perAppProxyEnabled, Settings.perAppProxyMode, Settings.perAppProxyList.size) }
        perAppEnabled = e
        perAppMode = m
        perAppCount = c
    }

    fun update(value: ConnectionSettings) {
        LitStore.updateSettings(value)
        onApply()
    }

    fun setPerApp(enabled: Boolean, mode: Int) {
        perAppEnabled = enabled
        perAppMode = mode
        scope.launch {
            withContext(Dispatchers.IO) {
                Settings.perAppProxyEnabled = enabled
                Settings.perAppProxyMode = mode
            }
            onApply()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Raised) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetHeader("Настройки")

            SectionTitle("Маршрутизация")
            RowGroup {
                SelectRow("Весь трафик через VPN", selected = s.routing == ConnectionSettings.ROUTING_ALL) {
                    update(s.copy(routing = ConnectionSettings.ROUTING_ALL))
                }
                RowDivider()
                SelectRow("Российские сайты напрямую", "Домены .ru, .su и .рф открываются без VPN", selected = s.routing == ConnectionSettings.ROUTING_RU) {
                    update(s.copy(routing = ConnectionSettings.ROUTING_RU))
                }
            }

            SectionTitle("Приложения")
            RowGroup {
                SelectRow("Все приложения", selected = !perAppEnabled) { setPerApp(false, perAppMode) }
                RowDivider()
                SelectRow("Только выбранные", "Через VPN работают только отмеченные приложения", selected = perAppEnabled && perAppMode == Settings.PER_APP_PROXY_INCLUDE) {
                    setPerApp(true, Settings.PER_APP_PROXY_INCLUDE)
                }
                RowDivider()
                SelectRow("Все, кроме выбранных", "Отмеченные приложения работают без VPN", selected = perAppEnabled && perAppMode == Settings.PER_APP_PROXY_EXCLUDE) {
                    setPerApp(true, Settings.PER_APP_PROXY_EXCLUDE)
                }
            }
            if (perAppEnabled) {
                Spacer(Modifier.height(8.dp))
                SheetItem("Выбрать приложения", if (perAppCount > 0) "Отмечено: $perAppCount" else "Пока ничего не отмечено") { showApps = true }
            }

            SectionTitle("DNS")
            RowGroup {
                SelectRow("Как в подписке", selected = s.dns == "auto" && !customMode) {
                    customMode = false
                    update(s.copy(dns = "auto"))
                }
                DNS_PRESETS.forEach { p ->
                    RowDivider()
                    SelectRow(p.name, trailing = p.address, selected = s.dns == p.address && !customMode) {
                        customMode = false
                        update(s.copy(dns = p.address))
                    }
                }
                RowDivider()
                SelectRow("Свой адрес", selected = customMode) { customMode = true }
                if (customMode) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customDns,
                            onValueChange = { customDns = it.trim().take(45) },
                            placeholder = { Text("Например, 9.9.9.9", color = Dim) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            shape = RoundedCornerShape(14.dp),
                            colors = fieldColors(),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            val ip = normalizeIp(customDns)
                            if (ip == null) {
                                dnsError = "Введите IP-адрес, например 9.9.9.9"
                            } else {
                                dnsError = null
                                update(s.copy(dns = ip))
                            }
                        }) { Text("Применить", color = TextMain, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            ErrorText(dnsError)

            SectionTitle("Подключение")
            RowGroup {
                ToggleRow("IPv6", "Выключите, если сайты открываются медленно", checked = s.ipv6) { update(s.copy(ipv6 = it)) }
                RowDivider()
                ToggleRow("Подключаться при запуске", "VPN включится при открытии приложения", checked = s.connectOnLaunch) {
                    LitStore.updateSettings(s.copy(connectOnLaunch = it))
                }
            }
            Spacer(Modifier.height(8.dp))
            SheetItem("Постоянный VPN", "Включите «Постоянная VPN» для LitEnergy VPN в настройках Android") {
                runCatching {
                    context.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            SectionTitle("Подписка")
            SheetItem("Обновить подписку", "Сроки, трафик и список серверов") {
                if (busy) return@SheetItem
                busy = true
                note = null
                scope.launch {
                    try {
                        LitStore.refresh()
                        note = "Подписка обновлена"
                        onApply()
                    } catch (e: Exception) {
                        note = e.message
                    } finally {
                        busy = false
                    }
                }
            }
            SheetItem("Мои подписки", "Добавить, переключить или удалить") { onOpen(Sheet.Subscriptions) }
            if (loggedIn) {
                SheetItem("Выйти из аккаунта", LitStore.accountTitle ?: "") {
                    LitStore.signOut()
                    note = "Вы вышли из аккаунта. Подписка осталась на устройстве."
                }
            }

            SectionTitle("Приложение")
            SheetItem("Проверить обновления", "Версия ${BuildConfig.VERSION_NAME}") {
                if (busy) return@SheetItem
                busy = true
                note = null
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { runCatching { Vendor.checkUpdateAsync() } }
                        val info = result.getOrNull()
                        when {
                            result.isFailure -> note = "Не удалось проверить обновления. Попробуйте позже."
                            info == null -> note = "У вас последняя версия"
                            else -> {
                                UpdateState.setUpdate(info)
                                note = "Доступна версия ${info.versionName}"
                                open(context, info.downloadUrl.ifEmpty { Links.RELEASES })
                            }
                        }
                    } finally {
                        busy = false
                    }
                }
            }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator(color = TextMain, strokeWidth = 2.dp, modifier = Modifier.size(22.dp).align(Alignment.CenterHorizontally))
            }
            note?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
            }
            Spacer(Modifier.height(18.dp))
            SheetItem("Сбросить и удалить подписку", danger = true) { confirmReset = true }
        }
    }

    if (showApps) {
        AppsSheet(onDismiss = { showApps = false }, onChanged = onApply)
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Surface,
            title = { Text("Удалить подписку?", color = TextMain) },
            text = { Text("Подписки, вход и настройки будут удалены с этого телефона. Саму подписку можно добавить снова.", color = Muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch {
                        if (status == Status.Started || status == Status.Starting) onDisconnect()
                        LitStore.resetAll()
                        onDismiss()
                    }
                }) { Text("Удалить", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена", color = TextMain) } },
        )
    }
}

private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")

/** Только IP-адрес (IPv4 или IPv6), без доменов. */
private fun normalizeIp(raw: String): String? {
    val s = raw.trim()
    if (IPV4.matches(s)) return s
    if (s.contains(':') && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
        return runCatching { InetAddress.getByName(s).hostAddress }.getOrNull()
    }
    return null
}

// ---------------------------------------------------------------- приложения (раздельное туннелирование)

private class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?)

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val packages: List<PackageInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
    }
    val size = (40 * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
    return packages.mapNotNull { pi ->
        if (pi.packageName == context.packageName) return@mapNotNull null
        if (pi.requestedPermissions?.contains(Manifest.permission.INTERNET) != true) return@mapNotNull null
        val ai: ApplicationInfo = pi.applicationInfo ?: return@mapNotNull null
        val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (system && pm.getLaunchIntentForPackage(pi.packageName) == null) return@mapNotNull null
        val icon = runCatching { ai.loadIcon(pm).toBitmap(size, size).asImageBitmap() }.getOrNull()
        AppEntry(pi.packageName, ai.loadLabel(pm).toString(), icon)
    }.sortedBy { it.label.lowercase() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsSheet(onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var initial by remember { mutableStateOf(setOf<String>()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val (list, sel) = withContext(Dispatchers.IO) { loadApps(context) to Settings.perAppProxyList }
        // отмеченные — наверху
        apps = list.sortedByDescending { it.pkg in sel }
        chosen = sel
        initial = sel
    }

    fun close() {
        if (chosen != initial) onChanged()
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = { close() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Raised) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            SheetHeader("Приложения")
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск", color = Dim) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TextMain, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
            } else {
                val filtered = list.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(22.dp)).background(Surface)) {
                    items(filtered, key = { it.pkg }) { app ->
                        val checked = app.pkg in chosen
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                chosen = if (checked) chosen - app.pkg else chosen + app.pkg
                                val snapshot = chosen
                                scope.launch(Dispatchers.IO) { Settings.perAppProxyList = snapshot }
                            }.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val icon = app.icon
                            if (icon != null) {
                                Image(icon, contentDescription = null, modifier = Modifier.size(36.dp))
                            } else {
                                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Pill))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(app.label, color = TextMain, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black, uncheckedColor = Dim),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- мои подписки

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubscriptionsSheet(onDismiss: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    fun add(raw: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                LitStore.importLink(raw)
                link = ""
                onChanged()
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Raised) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetHeader("Мои подписки")
            RowGroup {
                LitStore.subscriptions.forEachIndexed { i, sub ->
                    if (i > 0) RowDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            SelectRow(
                                sub.name,
                                listOf(LitStore.maskedLink(sub.id), sub.info.expireLine).filter { it.isNotEmpty() }.joinToString(" · "),
                                selected = sub.id == LitStore.currentId,
                            ) {
                                if (sub.id != LitStore.currentId) {
                                    LitStore.switchTo(sub.id)
                                    onChanged()
                                }
                            }
                        }
                        TextButton(onClick = { confirmDelete = sub.id }) { Text("Удалить", color = Red, fontSize = 14.sp) }
                    }
                }
            }
            SectionTitle("Добавить")
            OutlinedTextField(
                value = link,
                onValueChange = { link = it.trim() },
                placeholder = { Text("Ссылка на подписку", color = Dim) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(),
                trailingIcon = {
                    TextButton(onClick = { clipboard.getText()?.text?.let { link = it.trim() } }) { Text("Вставить", color = TextMain) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(error)
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Добавить", busy, enabled = link.isNotBlank()) { add(link) }
            Spacer(Modifier.height(10.dp))
            PillButton(null, "Сканировать QR-код") { showQr = true }
        }
    }

    if (showQr) {
        QRScanSheet(onDismiss = { showQr = false }, onScanResult = { result ->
            showQr = false
            if (result is QRScanResult.RemoteProfile) add(result.uri.toString())
        })
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Surface,
            title = { Text("Удалить подписку?", color = TextMain) },
            text = { Text("Подписка будет удалена только с этого телефона.", color = Muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        LitStore.remove(id)
                        if (LitStore.subscriptions.isEmpty()) onDismiss() else onChanged()
                    }
                }) { Text("Удалить", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена", color = TextMain) } },
        )
    }
}
