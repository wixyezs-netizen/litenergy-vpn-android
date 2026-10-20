package io.nekohasekai.sfa.lit.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.compose.component.qr.QRScanSheet
import io.nekohasekai.sfa.compose.screen.qrscan.QRScanResult
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.lit.Api
import io.nekohasekai.sfa.lit.Links
import io.nekohasekai.sfa.lit.LitStore
import io.nekohasekai.sfa.lit.Subscription
import io.nekohasekai.sfa.lit.SubscriptionLoader
import io.nekohasekai.sfa.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Подключение при запуске выполняется один раз за процесс. */
private var launchHandled = false

/** Корневой экран LitEnergy VPN: добавление подписки или главный экран. */
@Composable
fun LitApp(status: Status, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    remember { LitStore.init(context) }
    val currentStatus by rememberUpdatedState(status)

    // ссылка из диплинка litenergy://import?url=…
    val pending = LitStore.pendingImport
    LaunchedEffect(pending) {
        val link = pending ?: return@LaunchedEffect
        LitStore.pendingImport = null
        try {
            LitStore.importLink(link)
            Toast.makeText(context, "Подписка добавлена", Toast.LENGTH_SHORT).show()
            if (currentStatus == Status.Started) LitStore.applyProfile(reload = true)
        } catch (e: Exception) {
            Toast.makeText(context, e.message ?: "Не удалось добавить подписку", Toast.LENGTH_LONG).show()
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        FallingDots()
        if (LitStore.subscriptions.isEmpty()) {
            Onboarding()
        } else {
            Home(status = status, onConnect = onConnect, onDisconnect = onDisconnect)
        }
    }
}

// ---------------------------------------------------------------- добавление подписки

private enum class Step { Start, Link, Email, Telegram }

@Composable
private fun Onboarding() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var step by remember { mutableStateOf(Step.Start) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var botCode by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }

    fun add(raw: String) {
        if (busy) return
        error = null
        busy = true
        scope.launch {
            try {
                LitStore.importLink(raw)
            } catch (e: Exception) {
                error = e.message ?: "Не удалось добавить подписку"
            } finally {
                busy = false
            }
        }
    }

    // ссылка нашего сервиса в буфере — добавляем сразу
    LaunchedEffect(Unit) {
        val text = runCatching { clipboard.getText()?.text }.getOrNull() ?: return@LaunchedEffect
        val url = SubscriptionLoader.normalize(text) ?: return@LaunchedEffect
        if (url.contains("litenergyapp.store")) add(url)
    }

    // ожидание подтверждения входа в боте (до 10 минут)
    LaunchedEffect(botCode) {
        val c = botCode ?: return@LaunchedEffect
        repeat(300) {
            delay(2000)
            val account = withContext(Dispatchers.IO) { runCatching { Api.pollBotLogin(c) }.getOrNull() }
            if (account != null) {
                busy = true
                try {
                    LitStore.signIn(account)
                } catch (e: Exception) {
                    error = e.message
                } finally {
                    busy = false
                    botCode = null
                }
                return@LaunchedEffect
            }
        }
        botCode = null
        error = "Время ожидания истекло. Попробуйте ещё раз."
    }

    BackHandler(enabled = step != Step.Start) {
        error = null
        botCode = null
        step = Step.Start
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (step != Step.Start) {
            IconButton(onClick = {
                error = null
                botCode = null
                step = Step.Start
            }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад", tint = TextMain) }
        }
        when (step) {
            Step.Start -> {
                Spacer(Modifier.height(40.dp))
                BrandHeader()
                Spacer(Modifier.height(10.dp))
                Text("Добавьте подписку, чтобы подключиться", color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(28.dp))
                ChoiceCard(Icons.Rounded.ContentPaste, "Вставить ссылку", "Ссылка на подписку из бота или личного кабинета") {
                    val text = runCatching { clipboard.getText()?.text }.getOrNull()
                    val url = text?.let { SubscriptionLoader.normalize(it) }
                    if (url != null) add(url) else {
                        error = null
                        step = Step.Link
                    }
                }
                Spacer(Modifier.height(12.dp))
                ChoiceCard(Icons.Rounded.QrCodeScanner, "Сканировать QR-код", "QR-код подписки с экрана компьютера или другого телефона") { showQr = true }
                Spacer(Modifier.height(12.dp))
                ChoiceCard(Icons.Rounded.Mail, "Войти по почте", "Если покупали подписку на сайте") {
                    error = null
                    step = Step.Email
                }
                Spacer(Modifier.height(12.dp))
                ChoiceCard(Icons.AutoMirrored.Rounded.Send, "Войти через Telegram", "Подписка подтянется из бота @VPN_LitEnergy_bot") {
                    error = null
                    step = Step.Telegram
                }
                if (busy) {
                    Spacer(Modifier.height(18.dp))
                    CircularProgressIndicator(color = TextMain, strokeWidth = 2.dp, modifier = Modifier.size(28.dp).align(Alignment.CenterHorizontally))
                }
                ErrorText(error)
                Spacer(Modifier.height(18.dp))
                PillButton(Icons.Rounded.ShoppingCart, "Купить подписку") { open(context, Links.BOT) }
                Spacer(Modifier.height(18.dp))
                LegalLinks()
            }

            Step.Link -> {
                StepHeader("Ссылка на подписку", "Скопируйте ссылку в боте или личном кабинете и вставьте сюда.")
                Spacer(Modifier.height(22.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it.trim() },
                    placeholder = { Text("https://…", color = Dim) },
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
                Spacer(Modifier.height(20.dp))
                PrimaryButton("Добавить", busy, enabled = link.isNotBlank()) { add(link) }
            }

            Step.Email -> {
                StepHeader("Вход по почте", if (codeSent) "Мы отправили код на $email. Введите его ниже." else "Укажите почту, с которой оформляли подписку на сайте.")
                Spacer(Modifier.height(22.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim().take(120) },
                    placeholder = { Text("name@example.com", color = Dim) },
                    singleLine = true,
                    enabled = !busy && !codeSent,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (codeSent) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { c -> c.isDigit() }.take(8) },
                        placeholder = { Text("Код из письма", color = Dim) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        shape = RoundedCornerShape(16.dp),
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ErrorText(error)
                Spacer(Modifier.height(20.dp))
                if (!codeSent) {
                    PrimaryButton("Получить код", busy, enabled = email.contains('@') && email.contains('.')) {
                        error = null
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { Api.requestEmailCode(context, email) }
                                codeSent = true
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    }
                } else {
                    PrimaryButton("Войти", busy, enabled = code.length >= 4) {
                        error = null
                        busy = true
                        scope.launch {
                            try {
                                val account = withContext(Dispatchers.IO) { Api.verifyEmailCode(context, email, code) }
                                LitStore.signIn(account)
                            } catch (e: Exception) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    PillButton(null, "Изменить почту") {
                        codeSent = false
                        code = ""
                    }
                }
            }

            Step.Telegram -> {
                StepHeader("Вход через Telegram", "Откройте бота и подтвердите вход. Подписка добавится автоматически.")
                Spacer(Modifier.height(24.dp))
                PrimaryButton("Открыть бота", busy && botCode == null) {
                    error = null
                    busy = true
                    scope.launch {
                        try {
                            val (c, url) = withContext(Dispatchers.IO) { Api.startBotLogin() }
                            botCode = c
                            open(context, url)
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                }
                ErrorText(error)
                if (botCode != null) {
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Muted, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Ждём подтверждения в боте…", color = Muted, fontSize = 14.sp)
                    }
                }
            }
        }
    }

    if (showQr) {
        QRScanSheet(onDismiss = { showQr = false }, onScanResult = { result ->
            showQr = false
            if (result is QRScanResult.RemoteProfile) add(result.uri.toString())
        })
    }
}

@Composable
private fun LegalLinks() {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = { open(context, Links.TERMS) }) { Text("Соглашение", color = Dim, fontSize = 12.sp) }
        TextButton(onClick = { open(context, Links.PRIVACY) }) { Text("Конфиденциальность", color = Dim, fontSize = 12.sp) }
        TextButton(onClick = { open(context, Links.SUPPORT) }) { Text("Поддержка", color = Dim, fontSize = 12.sp) }
    }
}

// ---------------------------------------------------------------- главный экран

internal enum class Sheet { None, Servers, Plans, Menu, Settings, Subscriptions }

@Composable
private fun Home(status: Status, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf(Sheet.None) }
    var message by remember { mutableStateOf<String?>(null) }
    val hasUpdate by UpdateState.hasUpdate
    val updateInfo by UpdateState.updateInfo
    val sub = LitStore.current
    val currentStatus by rememberUpdatedState(status)

    fun connect() {
        message = null
        scope.launch {
            // сначала свежая подписка, если пора; при ошибке — последняя сохранённая
            if (LitStore.needsRefresh) runCatching { LitStore.refresh() }
            try {
                LitStore.applyProfile(reload = false)
                onConnect()
            } catch (e: Exception) {
                message = e.message ?: "Не удалось подключиться"
            }
        }
    }

    /** Настройки или сервер изменились — работающий VPN перезапускается с новой конфигурацией. */
    fun apply() {
        if (currentStatus != Status.Started && currentStatus != Status.Starting) return
        scope.launch {
            try {
                LitStore.applyProfile(reload = true)
            } catch (e: Exception) {
                message = e.message
            }
        }
    }

    LaunchedEffect(Unit) {
        LitStore.demoSheet?.let { name ->
            sheet = Sheet.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Sheet.None
            return@LaunchedEffect
        }
        if (!launchHandled) {
            launchHandled = true
            delay(1500) // состояние службы приходит не сразу
            if (LitStore.settings.connectOnLaunch && currentStatus == Status.Stopped && LitStore.hasConfig) connect()
        }
        launch { LitStore.loadAccount() }
        // автообновление подписки по интервалу из подписки (по умолчанию 12 ч)
        while (true) {
            if (LitStore.needsRefresh) {
                val ok = runCatching { LitStore.refresh() }.isSuccess
                if (ok) apply()
            }
            delay(15 * 60 * 1000L)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minH = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = minH).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundIconButton(Icons.Rounded.Menu, "Меню") { sheet = Sheet.Menu }
                        Spacer(Modifier.weight(1f))
                        RoundIconButton(Icons.Outlined.Settings, "Настройки") { sheet = Sheet.Settings }
                    }
                    if (hasUpdate && updateInfo != null) {
                        Spacer(Modifier.height(10.dp))
                        LinkCard(Icons.Rounded.SystemUpdate, "Доступна версия ${updateInfo?.versionName ?: ""}", "Нажмите, чтобы скачать") {
                            open(context, updateInfo?.downloadUrl?.ifEmpty { null } ?: Links.RELEASES)
                        }
                    }
                    Spacer(Modifier.height(if (hasUpdate) 14.dp else 4.dp))
                    BrandHeader(compact = true)
                    Spacer(Modifier.height(22.dp))
                    if (sub != null) {
                        StatusCard(status, sub, onServers = { sheet = Sheet.Servers }, onRenew = { open(context, Links.BOT) })
                    }
                    if (sub != null && sub.info.announce.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(sub.info.announce, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(24.dp))
                    ConnectButton(status) {
                        when (status) {
                            Status.Stopped -> connect()
                            Status.Started, Status.Starting -> onDisconnect()
                            else -> {}
                        }
                    }
                    message?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = Red, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    if (LitStore.updating) {
                        Spacer(Modifier.height(10.dp))
                        Text("Обновляем подписку…", color = Muted, fontSize = 13.sp)
                    }
                }
                Column(Modifier.padding(top = 26.dp, bottom = 8.dp)) {
                    val minPrice = LitStore.tariffs.minByOrNull { it.price.toDoubleOrNull() ?: Double.MAX_VALUE }
                    LinkCard(Icons.Rounded.Bolt, "Купить или продлить", if (minPrice != null) "Тарифы от ${minPrice.priceText}" else "Тарифы и оплата") { sheet = Sheet.Plans }
                    Spacer(Modifier.height(12.dp))
                    LinkCard(Icons.AutoMirrored.Rounded.Send, "Наш канал", "Новости и обновления") { open(context, Links.CHANNEL) }
                    Spacer(Modifier.height(12.dp))
                    LinkCard(Icons.Rounded.SupportAgent, "Поддержка", "Ответим в Telegram") { open(context, supportUrl(sub)) }
                }
            }
        }
    }

    when (sheet) {
        Sheet.None -> {}
        Sheet.Servers -> ServersSheet(onDismiss = { sheet = Sheet.None }, onSelect = { tag ->
            LitStore.selectServer(tag)
            apply()
        })
        Sheet.Plans -> PlansSheet(onDismiss = { sheet = Sheet.None })
        Sheet.Menu -> MenuSheet(onDismiss = { sheet = Sheet.None }, onOpen = { sheet = it })
        Sheet.Settings -> SettingsSheet(status = status, onDismiss = { sheet = Sheet.None }, onApply = { apply() }, onOpen = { sheet = it }, onDisconnect = onDisconnect)
        Sheet.Subscriptions -> SubscriptionsSheet(onDismiss = { sheet = Sheet.None }, onChanged = { apply() })
    }
}

internal fun supportUrl(sub: Subscription?): String {
    val url = sub?.info?.supportUrl ?: ""
    return if (url.startsWith("https://")) url else Links.SUPPORT
}

/** Карточка статуса: «Подписка активна | 28 дн.», трафик, «Подключено | 00:12:45». */
@Composable
private fun StatusCard(status: Status, sub: Subscription, onServers: () -> Unit, onRenew: () -> Unit) {
    val connected = status == Status.Started
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val since = remember(connected) { LitStore.connectedAt(connected) }
    LaunchedEffect(connected) {
        while (connected) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val info = sub.info
    val ended = info.isExpired || info.isTrafficOver
    val title = when {
        connected && since > 0 -> {
            val s = ((now - since) / 1000).coerceAtLeast(0)
            "Подключено | %02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
        }
        connected -> "Подключено"
        status == Status.Starting -> "Подключаемся…"
        status == Status.Stopping -> "Отключаемся…"
        info.isExpired -> "Подписка закончилась"
        info.isTrafficOver -> "Трафик закончился"
        info.daysLeft != null -> "Подписка активна | ${info.daysLeft} дн."
        else -> "Подписка активна"
    }
    val titleColor = if (!connected && status == Status.Stopped && ended) Red else TextMain
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Surface).border(1.dp, Border, RoundedCornerShape(26.dp)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(if (connected) Color.White else Pill).border(1.dp, BorderStrong, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        connected -> Icons.Rounded.Check
                        ended -> Icons.Rounded.PriorityHigh
                        else -> Icons.Rounded.PowerSettingsNew
                    },
                    contentDescription = null, tint = if (connected) Color.Black else TextMain, modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = titleColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text("${info.expireLine} · ${info.trafficLine}", color = Muted, fontSize = 14.sp, maxLines = 2)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (ended && !connected) {
            PrimaryButton("Продлить") { onRenew() }
        } else {
            PillButton(Icons.Rounded.Language, LitStore.selectedServerName, trailing = "Сменить", onClick = onServers)
        }
    }
}
