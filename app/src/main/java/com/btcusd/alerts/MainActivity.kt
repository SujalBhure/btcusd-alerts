package com.btcusd.alerts

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.Room
import com.btcusd.alerts.alarm.AlarmActivity
import com.btcusd.alerts.alarm.RingtonePlayer
import com.btcusd.alerts.alarm.SoundSettings
import com.btcusd.alerts.data.Alert
import com.btcusd.alerts.data.AlertDb
import com.btcusd.alerts.data.Feed
import com.btcusd.alerts.data.MARKETS
import com.btcusd.alerts.data.MIGRATION_1_2
import com.btcusd.alerts.data.MIGRATION_2_3
import com.btcusd.alerts.data.Market
import com.btcusd.alerts.data.PriceMonitorService
import com.btcusd.alerts.data.UpdateChecker
import com.btcusd.alerts.data.fmtPrice
import com.btcusd.alerts.data.marketOf
import com.btcusd.alerts.ui.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var db: AlertDb
    var resumeTick by mutableIntStateOf(0)
        private set

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Room.databaseBuilder(this, AlertDb::class.java, "alerts.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()

        PriceMonitorService.ensureAlertChannel(this)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val svc = Intent(this, PriceMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        setContent { AppTheme { Home(db, resumeTick) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(db: AlertDb, resumeTick: Int) {
    val scope = rememberCoroutineScope()
    val alerts by db.dao().observe().collectAsState(initial = emptyList())
    val ctx = LocalContext.current
    var quotes by remember { mutableStateOf(mapOf<String, Feed.Quote>()) }
    var sheetMarket by remember { mutableStateOf<Market?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    var editingAlert by remember { mutableStateOf<Alert?>(null) }
    var showSound by remember { mutableStateOf(false) }
    var soundRefreshKey by remember { mutableIntStateOf(0) }
    var update by remember { mutableStateOf<UpdateChecker.Update?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var showAuto by remember { mutableStateOf(false) }
    var showPerms by remember { mutableStateOf(false) }
    var askedPerms by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var upToDate by remember { mutableStateOf(false) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    var soundKeyToPick by remember { mutableStateOf<String?>(null) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val key = soundKeyToPick ?: SoundSettings.KEY_UNIVERSAL
            SoundSettings.saveCustomAudio(ctx, uri, key)
            soundRefreshKey++
        }
        soundKeyToPick = null
    }

    LaunchedEffect(resumeTick) {
        if (!askedPerms) {
            askedPerms = true
            if (missingPerms(ctx).isNotEmpty()) showPerms = true
        }
        if (showPerms && missingPerms(ctx).isEmpty()) showPerms = false
        if (!UpdateChecker.snoozed(ctx)) update = UpdateChecker.check()
    }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) { runCatching { db.dao().cleanupFiredOnce() } }
        if (!UpdateChecker.snoozed(ctx)) update = UpdateChecker.check()
    }

    LaunchedEffect(Unit) {
        while (true) {
            val fresh = MARKETS.mapNotNull { m -> Feed.quote(m)?.let { m.id to it } }.toMap()
            if (fresh.isNotEmpty()) quotes = fresh
            delay(15_000)
        }
    }

    Scaffold(
        containerColor = Color(0xFF000000)
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Text("BTC Alerts", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showPerms = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Permissions", tint = Color.White)
                    }
                    Button(
                        onClick = { showSound = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3138), contentColor = Color.White),
                        shape = RoundedCornerShape(20.dp)
                    ) { Text("Sound", fontSize = 13.sp) }
                    Button(
                        onClick = {
                            val q = quotes["BTCUSD"]
                            ctx.startActivity(Intent(ctx, AlarmActivity::class.java).apply {
                                putExtra("test", true); putExtra("symbol", "BTCUSD")
                                putExtra("price", q?.last ?: 0.0); putExtra("target", q?.last ?: 0.0)
                            })
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3138), contentColor = Color.White),
                        shape = RoundedCornerShape(20.dp)
                    ) { Text("Test ring", fontSize = 13.sp) }
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(MARKETS, key = { it.id }) { m ->
                    WatchRow(m, quotes[m.id], alerts.count { it.symbol == m.id && it.active }) {
                        sheetMarket = m; showSheet = true
                    }
                }
                item {
                    Text(
                        "Alerts (${alerts.size}) — tap alert to edit",
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
                items(alerts, key = { "a" + it.id }) { a ->
                    AlertRow(
                        a = a,
                        onToggle = { scope.launch(Dispatchers.IO) { db.dao().setActive(a.id, !a.active) } },
                        onDelete = { scope.launch(Dispatchers.IO) { db.dao().delete(a) } },
                        onEdit = { editingAlert = a }
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "BTC Alerts v${BuildConfig.VERSION_NAME}",
                            color = Color(0xFF9E9E9E), fontSize = 12.sp
                        )
                        TextButton(onClick = {
                            if (!checking) {
                                checking = true
                                scope.launch {
                                    val u = UpdateChecker.check()
                                    checking = false
                                    if (u != null) update = u else upToDate = true
                                }
                            }
                        }) { Text(if (checking) "Checking…" else "Check for updates", fontSize = 12.sp) }
                    }
                }
            }
        }
        val sm = sheetMarket
        if (showSheet && sm != null) {
            ModalBottomSheet(onDismissRequest = { showSheet = false }, containerColor = Color(0xFF14181D)) {
                AlertSheet(
                    market = sm,
                    current = quotes[sm.id]?.last ?: 0.0,
                    soundRefreshKey = soundRefreshKey,
                    initialAlert = null,
                    onSave = { target, tone, onceMode, alertType ->
                        scope.launch(Dispatchers.IO) {
                            db.dao().insert(
                                Alert(
                                    symbol = sm.id,
                                    targetPrice = target,
                                    direction = "cross",
                                    oneShot = onceMode,
                                    ringtoneUri = tone,
                                    alertType = alertType
                                )
                            )
                            withContext(Dispatchers.Main) { showSheet = false }
                        }
                    }
                )
            }
        }
        val ea = editingAlert
        if (ea != null) {
            val em = marketOf(ea.symbol)
            ModalBottomSheet(onDismissRequest = { editingAlert = null }, containerColor = Color(0xFF14181D)) {
                AlertSheet(
                    market = em,
                    current = quotes[em.id]?.last ?: 0.0,
                    soundRefreshKey = soundRefreshKey,
                    initialAlert = ea,
                    onSave = { target, tone, onceMode, alertType ->
                        scope.launch(Dispatchers.IO) {
                            db.dao().update(
                                ea.copy(
                                    targetPrice = target,
                                    direction = "cross",
                                    oneShot = onceMode,
                                    ringtoneUri = tone,
                                    alertType = alertType,
                                    active = true
                                )
                            )
                            withContext(Dispatchers.Main) { editingAlert = null }
                        }
                    },
                    onDelete = {
                        scope.launch(Dispatchers.IO) {
                            db.dao().delete(ea)
                            withContext(Dispatchers.Main) { editingAlert = null }
                        }
                    }
                )
            }
        }
        if (showSound) {
            ModalBottomSheet(onDismissRequest = { showSound = false }, containerColor = Color(0xFF14181D)) {
                SoundSettingsSheet(
                    refreshKey = soundRefreshKey,
                    onPickAudioForKey = { key ->
                        soundKeyToPick = key
                        pickAudio.launch("audio/*")
                    },
                    onSoundChanged = { soundRefreshKey++ }
                )
            }
        }
        update?.let { u ->
            AlertDialog(
                onDismissRequest = { update = null; UpdateChecker.snooze24h(ctx) },
                title = { Text("Update available: ${u.tag}") },
                text = { Text("A new BTC Alerts build is ready. Download and install it now?") },
                confirmButton = {
                    TextButton(onClick = {
                        downloading = true
                        UpdateChecker.downloadAndInstall(ctx, u.apkUrl)
                        update = null
                    }) { Text(if (downloading) "Downloading…" else "Update now") }
                },
                dismissButton = {
                    TextButton(onClick = { update = null; UpdateChecker.snooze24h(ctx) }) { Text("Later") }
                }
            )
        }
        if (upToDate) {
            AlertDialog(
                onDismissRequest = { upToDate = false },
                title = { Text("You're up to date") },
                text = { Text("BTC Alerts v${BuildConfig.VERSION_NAME} is the latest version.") },
                confirmButton = {
                    TextButton(onClick = { upToDate = false }) { Text("OK") }
                }
            )
        }
        if (showPerms) {
            ModalBottomSheet(onDismissRequest = { showPerms = false }, containerColor = Color(0xFF14181D)) {
                PermSheet(
                    missing = missingPerms(ctx),
                    onGrantNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onGrantOverlay = {
                        runCatching {
                            ctx.startActivity(Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${ctx.packageName}")
                            ))
                        }
                    },
                    onGrantBattery = {
                        runCatching {
                            ctx.startActivity(Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${ctx.packageName}")
                            ))
                        }
                    },
                    onGrantFullscreen = {
                        runCatching {
                            ctx.startActivity(Intent(
                                android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                            ).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                        }
                    },
                    onAutostart = { showPerms = false; showAuto = true }
                )
            }
        }
        if (showAuto) {
            AlertDialog(
                onDismissRequest = { showAuto = false },
                title = { Text("Autostart help") },
                text = { Text("vivo / iQOO: Settings → Battery → Background power consumption → BTC Alerts → Allow. Then Settings → Apps → BTC Alerts → turn on Autostart. Also open Recents and lock BTC Alerts so swiping it away doesn't kill alerts.\n\nXiaomi: Settings → Apps → Manage apps → BTC Alerts → Autostart → on, Battery saver → No restrictions.\n\nSamsung: Settings → Battery → Background usage limits → Never sleeping apps → add BTC Alerts.") },
                confirmButton = {
                    TextButton(onClick = { showAuto = false }) { Text("Got it") }
                }
            )
        }
    }
}

@Composable
private fun WatchRow(m: Market, q: Feed.Quote?, activeCount: Int, onTap: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                Box(
                    Modifier.size(48.dp).background(Color(m.badgeBg), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(m.badge, color = Color(m.badgeFg), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                if (activeCount > 0) {
                    Box(
                        Modifier.size(20.dp).background(Color(0xFF14181D), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = "alert set", tint = Color(0xFFF7A600), modifier = Modifier.size(13.dp))
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text(m.desc, color = Color(0xFF9E9E9E), fontSize = 14.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (q != null) fmtPrice(m, q.last) else "—",
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium
                )
                if (q != null) {
                    val up = q.absChange >= 0
                    Text(
                        "${if (up) "+" else "−"}${fmtPrice(m, kotlin.math.abs(q.absChange))}  ${if (up) "+" else "−"}${"%.2f".format(kotlin.math.abs(q.chgPct))}%",
                        color = if (up) Color(0xFF0ECB81) else Color(0xFFF6465D), fontSize = 14.sp
                    )
                }
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)
    }
}

@Composable
private fun AlertRow(
    a: Alert,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    val m = marketOf(a.symbol)
    val ctx = LocalContext.current

    val (badgeBg, badgeBorder, badgeFg) = when (a.alertType.uppercase()) {
        "SL" -> Triple(Color(0x33F6465D), Color(0xFFF6465D), Color(0xFFF6465D))
        "TP" -> Triple(Color(0x330ECB81), Color(0xFF0ECB81), Color(0xFF0ECB81))
        "ENTRY" -> Triple(Color(0x332962FF), Color(0xFF2962FF), Color(0xFF82B1FF))
        "LEVEL" -> Triple(Color(0x33F7A600), Color(0xFFF7A600), Color(0xFFF7A600))
        else -> Triple(Color(0x33AB47BC), Color(0xFFAB47BC), Color(0xFFE1BEE7))
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .background(Color(0xFF14181D), RoundedCornerShape(12.dp))
            .clickable(onClick = onEdit)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .background(badgeBg, RoundedCornerShape(6.dp))
                        .border(1.dp, badgeBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(a.alertType, color = badgeFg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "${m.id} cross $${fmtPrice(m, a.targetPrice)}",
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(4.dp))
            val soundLabel = if (a.ringtoneUri != null) {
                "custom sound"
            } else {
                val typeKey = SoundSettings.keyForType(a.alertType)
                val typeUri = SoundSettings.getSoundUri(ctx, typeKey)
                if (typeUri != null) SoundSettings.getSoundLabel(ctx, typeKey) else "universal"
            }
            Text(
                if (!a.active) "off • tap to edit"
                else "${if (a.oneShot) "once" else "every time"} • ♪ $soundLabel • tap to edit",
                color = Color(0xFF9E9E9E), fontSize = 13.sp
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = a.active, onCheckedChange = { onToggle() })
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit alert", tint = Color(0xFFB0B0B0), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFF9E9E9E))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertSheet(
    market: Market,
    current: Double,
    soundRefreshKey: Int,
    initialAlert: Alert? = null,
    onSave: (Double, String?, Boolean, String) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    var text by remember {
        mutableStateOf(
            if (initialAlert != null) {
                if (initialAlert.targetPrice % 1.0 == 0.0) initialAlert.targetPrice.toLong().toString()
                else initialAlert.targetPrice.toString()
            } else if (current > 0) {
                "${current.toInt()}"
            } else ""
        )
    }
    var once by remember { mutableStateOf(initialAlert?.oneShot ?: true) }

    var selectedType by remember {
        mutableStateOf(
            if (initialAlert != null) {
                if (initialAlert.alertType in listOf("ENTRY", "SL", "TP", "LEVEL")) initialAlert.alertType
                else "CUSTOM"
            } else "LEVEL"
        )
    }
    var customTypeText by remember {
        mutableStateOf(
            if (initialAlert != null && initialAlert.alertType !in listOf("ENTRY", "SL", "TP", "LEVEL")) {
                initialAlert.alertType
            } else ""
        )
    }

    val effectiveType = if (selectedType == "CUSTOM") {
        customTypeText.trim().ifBlank { "CUSTOM" }
    } else {
        selectedType
    }

    var customToneUri by remember {
        mutableStateOf<Uri?>(
            initialAlert?.ringtoneUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        )
    }
    var customToneName by remember {
        mutableStateOf<String?>(
            if (initialAlert?.ringtoneUri != null) "Custom sound" else null
        )
    }

    val pickCustomTone = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val savedUri = SoundSettings.saveCustomAudio(ctx, uri, "alert_temp_${System.currentTimeMillis()}")
            if (savedUri != null) {
                customToneUri = savedUri
                customToneName = SoundSettings.getSoundLabel(ctx, "alert_temp_${System.currentTimeMillis()}")
            }
        }
    }

    val typeSoundLabel = remember(effectiveType, soundRefreshKey) {
        val typeKey = SoundSettings.keyForType(effectiveType)
        val typeUri = SoundSettings.getSoundUri(ctx, typeKey)
        if (typeUri != null) {
            "${SoundSettings.getSoundLabel(ctx, typeKey)} ($effectiveType default)"
        } else {
            "Universal (${SoundSettings.label(ctx)})"
        }
    }

    val displayToneName = customToneName?.let { "$it (override)" } ?: typeSoundLabel

    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (initialAlert != null) "Edit ${market.id} Alert" else "New ${market.id} Alert",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
            if (current > 0) {
                Text("Live: $${fmtPrice(market, current)}", color = Color(0xFFF7A600), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(14.dp))

        // Price Input
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("Target price (${market.id})") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(14.dp))

        // Alert Type Chips
        Text("Alert Type", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ENTRY", "SL", "TP", "LEVEL", "CUSTOM").forEach { type ->
                val isSelected = selectedType == type
                val chipColor = when (type) {
                    "SL" -> Color(0xFFF6465D)
                    "TP" -> Color(0xFF0ECB81)
                    "ENTRY" -> Color(0xFF2962FF)
                    "LEVEL" -> Color(0xFFF7A600)
                    else -> Color(0xFFAB47BC)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (isSelected) chipColor else Color(0xFF2A3138),
                            RoundedCornerShape(16.dp)
                        )
                        .clickable { selectedType = type }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        type,
                        color = if (isSelected) (if (type == "SL" || type == "ENTRY") Color.White else Color.Black) else Color.White,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        if (selectedType == "CUSTOM") {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = customTypeText,
                onValueChange = { customTypeText = it.take(20) },
                label = { Text("Custom alert type name (e.g. BREAKOUT, RETEST)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(14.dp))

        // Trigger Frequency
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Once" to true, "Every time" to false).forEach { (label, v) ->
                Button(
                    onClick = { once = v },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    colors = if (once == v) ButtonDefaults.buttonColors(containerColor = Color(0xFFF7A600), contentColor = Color.Black)
                    else ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3138), contentColor = Color.White)
                ) { Text(label, fontSize = 13.sp) }
            }
        }
        Text(
            if (once) "Rings once, auto-deletes on dismiss." else "Rings on every crossing until you delete it.",
            color = Color(0xFF9E9E9E), fontSize = 12.sp
        )

        Spacer(Modifier.height(14.dp))

        // Ringtone Info & Override
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Ringtone", color = Color(0xFF9E9E9E), fontSize = 12.sp)
                Text(displayToneName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { pickCustomTone.launch("audio/*") },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3138), contentColor = Color.White)
                ) { Text("Override", fontSize = 12.sp) }

                if (customToneUri != null) {
                    IconButton(onClick = { customToneUri = null; customToneName = null }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset to default", tint = Color(0xFFF7A600))
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                text.toDoubleOrNull()?.let {
                    onSave(it, customToneUri?.toString(), once, effectiveType)
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF7A600), contentColor = Color.Black)
        ) {
            Text(
                if (initialAlert != null) "Update Alert" else "Save Alert",
                fontWeight = FontWeight.Bold, fontSize = 15.sp
            )
        }

        if (onDelete != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Delete Alert", color = Color(0xFFF6465D), fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SoundSettingsSheet(
    refreshKey: Int,
    onPickAudioForKey: (String) -> Unit,
    onSoundChanged: () -> Unit
) {
    val ctx = LocalContext.current

    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text("Alert Sound Settings", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Configure default universal alarm or specific ringtones per alert type (e.g., sad tone for SL). Sounds are saved permanently.",
            color = Color(0xFF9E9E9E), fontSize = 13.sp
        )
        Spacer(Modifier.height(16.dp))

        LazyColumn(Modifier.weight(1f, fill = false)) {
            // 1. Universal Sound
            item {
                SoundItemRow(
                    title = "Universal Ringtone",
                    subtitle = "Used when no type-specific sound is configured",
                    currentLabel = SoundSettings.label(ctx),
                    badgeColor = Color(0xFFF7A600),
                    isCustom = SoundSettings.getSoundUri(ctx, SoundSettings.KEY_UNIVERSAL) != null,
                    onPick = { onPickAudioForKey(SoundSettings.KEY_UNIVERSAL) },
                    onReset = {
                        SoundSettings.clearSound(ctx, SoundSettings.KEY_UNIVERSAL)
                        RingtonePlayer.stop()
                        onSoundChanged()
                    },
                    onTest = {
                        val uri = SoundSettings.getSoundUri(ctx, SoundSettings.KEY_UNIVERSAL)
                        RingtonePlayer.testPlay(ctx, uri)
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp, modifier = Modifier.padding(vertical = 10.dp))
            }

            // 2. Alert Types
            item {
                Text("Sounds by Alert Type", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
            }

            val typeConfigs = listOf(
                Triple("SL", "Stop Loss (e.g. sad tone)", Color(0xFFF6465D)),
                Triple("TP", "Take Profit (e.g. cash tone)", Color(0xFF0ECB81)),
                Triple("ENTRY", "Entry (e.g. horn/alert)", Color(0xFF2962FF)),
                Triple("LEVEL", "Key Level crossed", Color(0xFFF7A600))
            )

            items(typeConfigs, key = { it.first }) { (type, desc, color) ->
                val key = SoundSettings.keyForType(type)
                val uri = SoundSettings.getSoundUri(ctx, key)
                val label = SoundSettings.getSoundLabel(ctx, key)

                SoundItemRow(
                    title = "$type Alert",
                    subtitle = desc,
                    currentLabel = label,
                    badgeColor = color,
                    isCustom = uri != null,
                    onPick = { onPickAudioForKey(key) },
                    onReset = {
                        SoundSettings.clearSound(ctx, key)
                        RingtonePlayer.stop()
                        onSoundChanged()
                    },
                    onTest = {
                        val testUri = uri ?: SoundSettings.getSoundUri(ctx, SoundSettings.KEY_UNIVERSAL)
                        RingtonePlayer.testPlay(ctx, testUri)
                    }
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { RingtonePlayer.stop() },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3138), contentColor = Color.White)
        ) { Text("Stop sound playback") }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SoundItemRow(
    title: String,
    subtitle: String,
    currentLabel: String,
    badgeColor: Color,
    isCustom: Boolean,
    onPick: () -> Unit,
    onReset: () -> Unit,
    onTest: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF14181D), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier.size(8.dp).background(badgeColor, CircleShape)
                )
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(currentLabel, color = if (isCustom) badgeColor else Color(0xFFB0B0B0), fontSize = 13.sp, maxLines = 1)
            Text(subtitle, color = Color(0xFF6E6E6E), fontSize = 11.sp, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onTest, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Test sound", tint = Color.White)
            }
            Button(
                onClick = onPick,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3138), contentColor = Color.White),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) { Text("Set", fontSize = 12.sp) }

            if (isCustom) {
                IconButton(onClick = onReset, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset to default", tint = Color(0xFF9E9E9E))
                }
            }
        }
    }
}

/** Keys of permissions currently missing. Empty = all good. */
private fun missingPerms(ctx: android.content.Context): List<String> {
    val out = mutableListOf<String>()
    val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
    if (nm != null && !nm.areNotificationsEnabled()) out += "notifications"
    if (!android.provider.Settings.canDrawOverlays(ctx)) out += "overlay"
    val pm = ctx.getSystemService(android.os.PowerManager::class.java)
    if (pm != null && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) out += "battery"
    if (Build.VERSION.SDK_INT >= 34 && nm != null && !nm.canUseFullScreenIntent()) out += "fullscreen"
    return out
}

@Composable
private fun PermSheet(
    missing: List<String>,
    onGrantNotifications: () -> Unit,
    onGrantOverlay: () -> Unit,
    onGrantBattery: () -> Unit,
    onGrantFullscreen: () -> Unit,
    onAutostart: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text("Permissions", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Alerts can only wake you if Android lets the app run. Grant everything below.",
            color = Color(0xFF9E9E9E), fontSize = 13.sp
        )
        Spacer(Modifier.height(12.dp))
        PermRow("Notifications", "Show alert banners on lock screen.", missing.contains("notifications"), onGrantNotifications)
        PermRow("Display over other apps", "Pop the call-style alarm over anything.", missing.contains("overlay"), onGrantOverlay)
        PermRow("Ignore battery optimization", "Keep watching price with screen off.", missing.contains("battery"), onGrantBattery)
        if (Build.VERSION.SDK_INT >= 34) {
            PermRow("Full-screen alerts", "Android 14+ toggle for lock-screen alarms.", missing.contains("fullscreen"), onGrantFullscreen)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onAutostart,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3138), contentColor = Color.White)
        ) { Text("Autostart help (vivo / Xiaomi / Samsung)", fontSize = 13.sp) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermRow(title: String, desc: String, needed: Boolean, onGrant: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (needed) "NEEDED" else "OK",
                    color = if (needed) Color(0xFFF7A600) else Color(0xFF0ECB81), fontSize = 12.sp
                )
            }
            Text(desc, color = Color(0xFF9E9E9E), fontSize = 13.sp)
        }
        if (needed) {
            Button(
                onClick = onGrant,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF7A600), contentColor = Color.Black)
            ) { Text("Grant", fontSize = 13.sp) }
        }
    }
}
