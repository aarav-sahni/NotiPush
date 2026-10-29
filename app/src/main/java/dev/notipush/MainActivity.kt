package dev.notipush

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val Forest = Color(0xFF176B57)
private val Ink = Color(0xFF183A31)
private val Muted = Color(0xFF687970)
private val Canvas = Color(0xFFF6F7F2)
private val Lime = Color(0xFFE3F5BB)
private val SoftGreen = Color(0xFFEDF3EB)
private val NotiColors = lightColorScheme(
    primary = Forest, onPrimary = Color.White, primaryContainer = Lime, onPrimaryContainer = Ink,
    background = Canvas, onBackground = Ink, surface = Color.White, onSurface = Ink,
    surfaceVariant = SoftGreen, onSurfaceVariant = Muted, outline = Color(0xFFC8D4C9),
    secondaryContainer = SoftGreen, onSecondaryContainer = Forest
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        // Credentials and setup screens must not appear in screenshots or recent-app previews.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MaterialTheme(colorScheme = NotiColors) { NotiPushApp(this) } }
    }
}

private data class UiSnapshot(
    val enabled: Boolean = false,
    val hasAccess: Boolean = false,
    val connected: Boolean = false,
    val destination: Destination = Destination(),
    val credentialError: Boolean = false,
    val rules: Map<String, AppRule> = emptyMap(),
    val stats: DeliveryStats = DeliveryStats(),
    val history: List<Delivery> = emptyList(),
    val ongoing: Boolean = false,
    val listenerError: String = "",
    val batteryExempt: Boolean = false,
    val lastCaptureAt: Long = 0,
    val lastCaptureLocked: Boolean = false,
    val lastDeliveryAt: Long = 0,
    val lastDeliveryLocked: Boolean = false
) { val configured get() = !credentialError && destination.validationError() == null }

private enum class Screen(val title: String, val icon: ImageVector) {
    Home("Overview", Icons.Outlined.Dashboard),
    Apps("Apps", Icons.Outlined.Apps),
    Activity("Activity", Icons.Outlined.History),
    Settings("Setup", Icons.Outlined.Tune)
}

@Composable private fun NotiPushApp(activity: MainActivity) {
    val repo = remember { activity.repository }
    var snapshot by remember { mutableStateOf(UiSnapshot()) }
    var screen by rememberSaveableScreen()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var accessDialog by remember { mutableStateOf(false) }
    var resetDialog by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<SourceApp>>(starterApps) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    suspend fun refresh() {
        snapshot = withContext(Dispatchers.IO) {
            val destination = runCatching { repo.settings.destination() }
            UiSnapshot(repo.settings.enabled, repo.hasAccess(), repo.listenerConnected,
                destination.getOrDefault(Destination()), destination.isFailure, repo.settings.rules(),
                repo.store.stats(), repo.store.history(), repo.settings.includeOngoing, repo.settings.lastListenerError,
                repo.batteryExempt(), repo.settings.lastCaptureAt, repo.settings.lastCaptureLocked,
                repo.settings.lastDeliveryAt, repo.settings.lastDeliveryLocked)
        }
    }
    fun task(successMessage: String? = null, action: () -> Unit) {
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { action() } }
            refresh()
            if (result.isFailure) snackbar.showSnackbar("Could not save. Please try again.")
            else if (successMessage != null) snackbar.showSnackbar(successMessage)
        }
    }
    fun openUrl(url: String) {
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { scope.launch { snackbar.showSnackbar("No browser is available on this phone.") } }
    }
    fun notificationSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(activity, NotificationBridge::class.java).flattenToString())
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            runCatching { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                .onFailure { scope.launch { snackbar.showSnackbar("Open Android Settings → Notification access → NotiPush.") } }
        }
    }
    LaunchedEffect(Unit) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { refresh(); delay(1_500) }
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = activity.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = pm.queryIntentActivities(launcher, 0).map {
                SourceApp(it.activityInfo.packageName, it.loadLabel(pm).toString())
            }.filter { it.packageName !in setOf(activity.packageName, "net.superblock.pushover") }
            val present = (found.map { it.packageName } + starterApps.mapNotNull {
                runCatching { pm.getApplicationInfo(it.packageName, 0); it.packageName }.getOrNull()
            }).toSet()
            withContext(Dispatchers.Main) {
                installed = present
                sources = (starterApps + found.sortedBy { it.label.lowercase() }).distinctBy { it.packageName }
            }
        }
    }
    Scaffold(
        containerColor = Canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                Screen.entries.forEach { item ->
                    NavigationBarItem(selected = screen == item, onClick = { screen = item },
                        icon = { Icon(item.icon, contentDescription = null) }, label = { Text(item.title) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Forest), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.SyncAlt, null, tint = Lime, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("NotiPush", fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
                    Spacer(Modifier.weight(1f))
                    StatusPill(if (snapshot.enabled && snapshot.hasAccess && snapshot.connected && snapshot.configured) "LIVE" else "STANDBY",
                        snapshot.enabled && snapshot.hasAccess && snapshot.connected && snapshot.configured)
                }
                when (screen) {
                    Screen.Home -> HomeScreen(snapshot,
                        onToggle = { value -> task {
                            if (value) { repo.settings.enabled = true; repo.requestReconnect() } else repo.pause()
                        } },
                        onSetup = { screen = Screen.Settings }, onApps = { screen = Screen.Apps },
                        onAccess = { accessDialog = true }, onActivity = { screen = Screen.Activity },
                        onReconnect = { repo.requestReconnect(); scope.launch { snackbar.showSnackbar("Reconnect requested. If it stays disconnected, toggle NotiPush in Android notification access.") } })
                    Screen.Apps -> AppsScreen(snapshot.rules, sources, installed, onRule = { pkg, rule -> task { repo.saveRule(pkg, rule) } })
                    Screen.Activity -> ActivityScreen(snapshot.history,
                        onClear = { task { repo.store.clearHistory() } })
                    Screen.Settings -> SettingsScreen(snapshot,
                        onSave = { destination ->
                            task("Destination saved. Changes cancel pending notifications.") { repo.saveDestination(destination) }
                        },
                        onTest = {
                            task { checkNotNull(repo.sendTest()) }
                            screen = Screen.Activity
                        },
                        onAccess = { accessDialog = true },
                        onOngoing = { value -> task { repo.settings.includeOngoing = value } },
                        onBattery = {
                            try {
                                // Core notification automation needs outbound access in Doze;
                                // Android documents task automation as an acceptable exemption use case.
                                @SuppressLint("BatteryLife")
                                val intent = if (repo.batteryExempt()) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
                                activity.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                try { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                                catch (_: ActivityNotFoundException) { scope.launch { snackbar.showSnackbar("Open Android Settings → Battery → Battery optimization → NotiPush → Don't optimize.") } }
                            }
                        },
                        onUrl = ::openUrl, onReset = { resetDialog = true })
                }
            }
        }
    }
    if (accessDialog) AlertDialog(
        onDismissRequest = { accessDialog = false },
        icon = { Icon(Icons.Outlined.NotificationsActive, null) },
        title = { Text("Allow notification access") },
        text = { Text("Android will let NotiPush read notifications. Only apps you select are forwarded to your Pushover account.\n\nQueued text and credentials are encrypted on this phone. Forwarded text is shared with Pushover over HTTPS.\n\nOn the next screen, enable NotiPush. For a sideloaded app, Android may first require App info → ⋮ → Allow restricted settings.") },
        confirmButton = { TextButton(onClick = { accessDialog = false; notificationSettings() }) { Text("Open Android settings") } },
        dismissButton = { TextButton(onClick = { accessDialog = false }) { Text("Later") } }
    )
    if (resetDialog) AlertDialog(
        onDismissRequest = { resetDialog = false },
        title = { Text("Reset NotiPush?") },
        text = { Text("This removes your Pushover keys, app rules, queued notifications, and local history. A delivery already in flight may still arrive. Notification access can be revoked separately in Android settings.") },
        confirmButton = { TextButton(onClick = { resetDialog = false; task { repo.reset() }; screen = Screen.Home }) { Text("Reset everything") } },
        dismissButton = { TextButton(onClick = { resetDialog = false }) { Text("Cancel") } }
    )
}

@Composable private fun rememberSaveableScreen(): MutableState<Screen> {
    val saved = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(Screen.Home.name) }
    return remember {
        object : MutableState<Screen> {
            override var value: Screen
                get() = Screen.valueOf(saved.value)
                set(value) { saved.value = value.name }
            override fun component1() = value
            override fun component2(): (Screen) -> Unit = { value = it }
        }
    }
}

@Composable private fun StatusPill(text: String, active: Boolean) {
    Row(Modifier.clip(CircleShape).background(if (active) Lime else SoftGreen).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (active) Forest else Muted))
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (active) Forest else Muted, letterSpacing = 1.sp)
    }
}

@Composable private fun HomeScreen(
    state: UiSnapshot, onToggle: (Boolean) -> Unit, onSetup: () -> Unit, onApps: () -> Unit,
    onAccess: () -> Unit, onActivity: () -> Unit, onReconnect: () -> Unit
) {
    val ready = state.configured && state.hasAccess && state.rules.values.any { it.enabled }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Forest).padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A LITTLE BRIDGE. A LOT LESS FRICTION.", color = Lime, fontSize = 9.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
            Text("Your notifications.\nEverywhere.", color = Color.White, fontSize = 32.sp, lineHeight = 36.sp,
                letterSpacing = (-1).sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text("From your Android to your iPhone,\nApple Watch, and beyond.", color = Color(0xFFD2E5D9), fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RouteNode(Icons.Outlined.Smartphone, "Android")
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = Lime, modifier = Modifier.size(18.dp))
                RouteNode(Icons.Outlined.CloudDone, "Pushover")
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = Lime, modifier = Modifier.size(18.dp))
                RouteNode(Icons.Outlined.Watch, "Your devices")
            }
        }
        Surface(shape = RoundedCornerShape(22.dp), color = Color.White) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (state.enabled) "Forwarding is on" else "Ready when you are", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(if (state.enabled) "New notifications are forwarded automatically" else if (ready) "Turn on your notification bridge" else "Complete setup to start forwarding",
                            style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 4.dp))
                    }
                    Switch(checked = state.enabled, onCheckedChange = onToggle, enabled = ready || state.enabled)
                }
                if (state.enabled) Text("Pausing cancels queued messages; a delivery in flight may still arrive.", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (!ready) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("MAKE THE CONNECTION")
                SetupRow("01", "Connect Pushover", "Add your API token and user key", state.configured, onSetup)
                SetupRow("02", "Allow notification access", "Give your bridge a way to listen", state.hasAccess, onAccess)
                SetupRow("03", "Choose your apps", "WhatsApp, Messages, Teams, and more", state.rules.values.any { it.enabled }, onApps)
            }
        }
        if (state.enabled && !state.connected && state.hasAccess) {
            InfoCard("Listener is reconnecting", "Android has granted access, but the listener isn't connected yet.", Icons.Outlined.Sync, onReconnect)
        }
        if (state.enabled && !state.batteryExempt) {
            InfoCard("Allow delivery while locked", "Open Setup and allow background delivery so Android can send while the phone sleeps.", Icons.Outlined.BatteryChargingFull, onSetup)
        }
        if (state.listenerError.isNotBlank()) InfoCard("Check forwarding", state.listenerError, Icons.Outlined.Info, onSetup)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("DELIVERY AT A GLANCE", Modifier.weight(1f))
                TextButton(onClick = onActivity, contentPadding = PaddingValues(0.dp)) { Text("View activity", fontSize = 12.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Accepted", state.stats.accepted, Modifier.weight(1f))
                StatCard("Queued", state.stats.queued, Modifier.weight(1f))
                StatCard("Failed", state.stats.failed, Modifier.weight(1f))
            }
            Text("Counts reflect the retained local history. Accepted means Pushover received it.", fontSize = 11.sp, color = Muted)
        }
        InfoCard("One direct connection", "No NotiPush subscription or server. Delivery uses your Pushover account and its message allowance.", Icons.Outlined.Bolt)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable private fun RouteNode(icon: ImageVector, text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Lime, modifier = Modifier.size(23.dp))
        }
        Text(text, fontSize = 10.sp, color = Color.White)
    }
}

@Composable private fun SetupRow(number: String, title: String, subtitle: String, done: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.White, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(if (done) Lime else SoftGreen), contentAlignment = Alignment.Center) {
                if (done) Icon(Icons.Outlined.Check, null, tint = Forest, modifier = Modifier.size(18.dp))
                else Text(number, fontSize = 11.sp, color = Forest, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(subtitle, color = Muted, fontSize = 11.sp)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
}

@Composable private fun StatCard(label: String, count: Int, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = Color.White) {
        Column(Modifier.padding(16.dp)) {
            Text(count.toString(), fontSize = 27.sp, fontWeight = FontWeight.SemiBold, color = Forest)
            Text(label, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable private fun InfoCard(title: String, text: String, icon: ImageVector, onClick: (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(18.dp), color = SoftGreen,
        modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = Forest, modifier = Modifier.size(22.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(text, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable private fun PageHeading(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 18.dp)) {
        Text(title, fontSize = 29.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp)
        Text(subtitle, fontSize = 13.sp, color = Muted, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable private fun AppsScreen(
    rules: Map<String, AppRule>, sources: List<SourceApp>, installed: Set<String>, onRule: (String, AppRule) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<SourceApp?>(null) }
    val visible = sources.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        PageHeading("Pick your sources.", "Forward only what matters. Tap an app to add keyword filters.")
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Find an app") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.height(16.dp))
        SectionLabel("${rules.values.count { it.enabled }} APPS SELECTED")
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(visible, key = { it.packageName }) { source ->
                val rule = rules[source.packageName] ?: AppRule(enabled = false)
                val present = source.packageName in installed
                Surface(onClick = { editing = source }, shape = RoundedCornerShape(18.dp), color = Color.White) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppBadge(source)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(source.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when {
                                !present -> "Not installed on this phone"
                                rule.include.isNotBlank() || rule.exclude.isNotBlank() -> "Keyword filters active"
                                rule.enabled -> "All new text notifications"
                                else -> "Not forwarding"
                            }, fontSize = 11.sp, color = Muted)
                        }
                        Switch(rule.enabled, onCheckedChange = { onRule(source.packageName, rule.copy(enabled = it, since = System.currentTimeMillis())) })
                    }
                }
            }
            if (visible.isEmpty()) item { EmptyState(Icons.Outlined.Search, "No matching apps", "Search by app name or package name.") }
            item { Text("Apps in a separate work profile may be unavailable. Teams forwarding depends on your organisation's notification policies.", fontSize = 11.sp, lineHeight = 17.sp, color = Muted) }
        }
    }
    editing?.let { source ->
        val current = rules[source.packageName] ?: AppRule(enabled = false)
        var include by remember(source.packageName) { mutableStateOf(current.include) }
        var exclude by remember(source.packageName) { mutableStateOf(current.exclude) }
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text(source.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(source.packageName, fontSize = 11.sp, color = Muted)
                    Text("Match the notification title or text. Separate keywords with commas; matching ignores case.", fontSize = 13.sp)
                    OutlinedTextField(include, { include = it }, label = { Text("Only containing (optional)") }, placeholder = { Text("family, project") })
                    OutlinedTextField(exclude, { exclude = it }, label = { Text("Skip containing (optional)") }, placeholder = { Text("verification, promo") })
                    Text("Any include keyword can match. Any skip keyword blocks the notification.", fontSize = 11.sp, color = Muted)
                }
            },
            confirmButton = { TextButton(onClick = { onRule(source.packageName, current.copy(include = include.trim(), exclude = exclude.trim())); editing = null }) { Text("Save filters") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }
}

@Composable private fun AppBadge(source: SourceApp) {
    val (color, icon) = when (source.packageName) {
        "com.whatsapp", "com.whatsapp.w4b" -> Color(0xFFDCF3DF) to Icons.Outlined.ChatBubbleOutline
        "com.google.android.apps.messaging" -> Color(0xFFE1ECFC) to Icons.Outlined.Sms
        "com.microsoft.teams" -> Color(0xFFE8E5FA) to Icons.Outlined.Groups
        else -> SoftGreen to Icons.Outlined.Apps
    }
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Ink, modifier = Modifier.size(23.dp))
    }
}

@Composable private fun ActivityScreen(history: List<Delivery>, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        PageHeading("Across the bridge.", "Delivery status, without storing your conversations.")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("RECENT ACTIVITY", Modifier.weight(1f))
            TextButton(onClick = onClear, enabled = history.any { it.status != "queued" }) { Text("Clear history") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (history.isEmpty()) item { EmptyState(Icons.Outlined.MarkChatRead, "A clean slate.", "Send a test from Setup, or turn on forwarding to see deliveries here.") }
            items(history, key = { it.id }) { row ->
                Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(when (row.status) {
                                "sent" -> Icons.Outlined.CheckCircle
                                "queued" -> Icons.Outlined.Schedule
                                "cancelled" -> Icons.Outlined.Block
                                else -> Icons.Outlined.ErrorOutline
                            }, null, tint = if (row.status == "failed") MaterialTheme.colorScheme.error else Forest, modifier = Modifier.size(20.dp))
                            Text(row.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                            Text(if (row.status == "sent") "Accepted" else row.status.replaceFirstChar { it.uppercase() }, fontSize = 11.sp, color = Muted)
                        }
                        Text(row.detail, fontSize = 12.sp, lineHeight = 18.sp, color = Muted)
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.created)), fontSize = 10.sp, color = Muted)
                    }
                }
            }
            if (history.isNotEmpty()) item { Text("Pushover acceptance confirms receipt by its API. Device delivery and Apple Watch alerts depend on your Pushover and iOS settings. The most recent 200 completed deliveries are retained.", fontSize = 11.sp, lineHeight = 17.sp, color = Muted) }
        }
    }
}

@Composable private fun EmptyState(icon: ImageVector, title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 50.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Forest, modifier = Modifier.size(42.dp))
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Text(description, fontSize = 13.sp, lineHeight = 20.sp, color = Muted)
    }
}

@Composable private fun SettingsScreen(
    state: UiSnapshot, onSave: (Destination) -> Unit, onTest: () -> Unit, onAccess: () -> Unit,
    onOngoing: (Boolean) -> Unit, onBattery: () -> Unit, onUrl: (String) -> Unit, onReset: () -> Unit
) {
    var token by remember(state.destination) { mutableStateOf(state.destination.token) }
    var user by remember(state.destination) { mutableStateOf(state.destination.user) }
    var device by remember(state.destination) { mutableStateOf(state.destination.device) }
    var priority by remember(state.destination) { mutableIntStateOf(state.destination.priority) }
    var showSecrets by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val draft = Destination(token.trim(), user.trim(), device.trim(), priority)
    val changed = draft != state.destination
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeading("Make it yours.", "A few keys. One connection. All your devices.")
        InfoCard("Start with Pushover", "Create an application named NotiPush to get an API token. Your user key is on your Pushover dashboard.", Icons.Outlined.CloudQueue)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onUrl("https://pushover.net/apps/build") }, modifier = Modifier.weight(1f)) { Text("Create app", fontSize = 12.sp) }
            OutlinedButton(onClick = { onUrl("https://pushover.net/") }, modifier = Modifier.weight(1f)) { Text("Get user key", fontSize = 12.sp) }
        }
        SectionLabel("PUSHOVER DESTINATION")
        if (state.credentialError) Text("Saved keys could not be decrypted. Please enter them again.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        SecretField("Application API token", token, { token = it; error = null }, showSecrets)
        SecretField("User or group key", user, { user = it; error = null }, showSecrets)
        Row(Modifier.fillMaxWidth().clickable { showSecrets = !showSecrets }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(showSecrets, { showSecrets = it })
            Text("Show keys", fontSize = 13.sp)
        }
        OutlinedTextField(device, { device = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Device names (optional)") },
            placeholder = { Text("Leave blank for all devices") }, supportingText = { Text("For example: iphone,ipad. Blank sends to all devices.") },
            singleLine = true, shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
        Text("Notification priority", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(-1 to "Low", 0 to "Normal", 1 to "High").forEach { (value, label) ->
                FilterChip(selected = priority == value, onClick = { priority = value }, label = { Text(label) })
            }
        }
        if (priority == 1) Text("High priority bypasses Pushover quiet hours.", fontSize = 12.sp, color = Muted)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        Button(onClick = { val validation = draft.validationError(); if (validation != null) error = validation else onSave(draft) },
            modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Save destination")
        }
        OutlinedButton(onClick = onTest, enabled = state.configured && !changed,
            modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.AutoMirrored.Outlined.Send, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Send a test notification")
        }
        Text(if (changed) "Save your changes before sending a test." else "Tests work before forwarding is enabled. Check Activity for the result.", fontSize = 11.sp, color = Muted)
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = SoftGreen)
        SectionLabel("ANDROID CONNECTION")
        SetupRow("01", "Notification access", if (state.hasAccess) "Granted by Android" else "Required to read selected notifications", state.hasAccess, onAccess)
        SetupRow("02", "Delivery while locked", if (state.batteryExempt) "Battery optimization exemption granted" else "Allow background delivery so messages can send during sleep", state.batteryExempt, onBattery)
        InfoCard("Keep your bridge running", "Tap below and allow NotiPush to ignore battery optimization. On OnePlus, also allow background activity in App info → Battery usage; enable auto launch if OxygenOS offers it. This may use more battery.", Icons.Outlined.BatteryChargingFull)
        OutlinedButton(onClick = onBattery, modifier = Modifier.fillMaxWidth()) { Text(if (state.batteryExempt) "Review battery settings" else "Allow background delivery") }
        fun eventTime(time: Long, locked: Boolean): String = if (time == 0L) "None yet" else
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time)) +
                if (locked) " · screen off / locked" else " · screen on"
        InfoCard("Connection check", "Listener: ${if (state.connected) "connected" else "disconnected"}\nLast captured: ${eventTime(state.lastCaptureAt, state.lastCaptureLocked)}\nLast accepted: ${eventTime(state.lastDeliveryAt, state.lastDeliveryLocked)}\nTimes only; no notification text is kept here.", Icons.Outlined.Sync)
        Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Include ongoing notifications", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Usually leave off to skip persistent service and call alerts.", fontSize = 11.sp, color = Muted)
                }
                Switch(state.ongoing, onOngoing)
            }
        }
        SectionLabel("GOOD TO KNOW")
        InfoCard("Finish on your iPhone", "Install Pushover, sign in to the same account, and enable notifications. In the Watch app, enable Pushover mirroring. Apple Watch alert routing follows your iPhone and Watch settings.", Icons.Outlined.Watch)
        InfoCard("Your phone sets the limits", "Only text exposed by Android can be forwarded. Hidden previews and sensitive content such as OTPs may be redacted. Work profile policies can block Teams notifications. Attachments and replies aren't forwarded.", Icons.Outlined.PrivacyTip)
        InfoCard("Offline? We'll wait.", "New notifications send immediately, including while locked when background delivery is allowed. Encrypted messages retry network or server errors for up to 24 hours. Pushover rejections and quota errors appear in Activity.", Icons.Outlined.WifiOff)
        Text("NotiPush has no analytics or hosted backend. Notification text goes directly to Pushover over HTTPS; Pushover can process the forwarded content. Local history contains status only. Changing the destination or pausing forwarding cancels pending deliveries.", fontSize = 11.sp, lineHeight = 18.sp, color = Muted)
        TextButton(onClick = { onUrl("https://pushover.net/api#limits") }) { Text("Pushover allowance & API details") }
        TextButton(onClick = onReset, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Reset app & remove local data") }
        Text("NOTIPUSH ${BuildConfig.VERSION_NAME}  ·  BUILT FOR YOUR OWN DEVICES", fontSize = 9.sp, letterSpacing = 1.sp, color = Muted, modifier = Modifier.padding(bottom = 24.dp))
    }
}

@Composable private fun SecretField(label: String, value: String, onValue: (String) -> Unit, visible: Boolean) {
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        shape = RoundedCornerShape(14.dp))
}
