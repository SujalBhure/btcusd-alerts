package com.btcusd.alerts

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.btcusd.alerts.data.Alert
import com.btcusd.alerts.data.AlertDb
import com.btcusd.alerts.data.BybitApi
import com.btcusd.alerts.data.PriceMonitorService
import com.btcusd.alerts.ui.AppTheme
import com.btcusd.alerts.ui.CandleChart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var db: AlertDb

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Room.databaseBuilder(this, AlertDb::class.java, "alerts.db").build()
        PriceMonitorService.ensureAlertChannel(this)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val svc = Intent(this, PriceMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        setContent { AppTheme { Home(db) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Home(db: AlertDb) {
    val scope = rememberCoroutineScope()
    val alerts by db.dao().observe().collectAsState(initial = emptyList())
    var price by remember { mutableStateOf(0.0) }
    var pct by remember { mutableStateOf(0.0) }
    var candles by remember { mutableStateOf(listOf<BybitApi.Candle>()) }
    var showSheet by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        candles = BybitApi.fetchKlines("15", 96)
        BybitApi.fetchTicker()?.let { price = it.lastPrice; pct = it.price24hPcnt * 100 }
        while (true) {
            delay(15_000)
            BybitApi.fetchTicker()?.let { price = it.lastPrice; pct = it.price24hPcnt * 100 }
        }
    }

    Scaffold(
        containerColor = Color(0xFF0B0E11),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showSheet = true },
                containerColor = Color(0xFFF7A600), contentColor = Color.Black
            ) { Icon(Icons.Default.Add, contentDescription = "Add alert") }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            Text("BTCUSD PERP • BYBIT • LAST", color = Color(0xFF9E9E9E), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (price > 0) "$${"%,.1f".format(price)}" else "—",
                    color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Medium
                )
                Text(
                    "${if (pct >= 0) "+" else ""}${"%.2f".format(pct)}%",
                    color = if (pct >= 0) Color(0xFF0ECB81) else Color(0xFFF6465D),
                    fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            CandleChart(candles, price)
            Spacer(Modifier.height(8.dp))
            Text("Watches Bybit BTCUSD perp lastPrice. Rings full-screen like a call.", color = Color(0xFF9E9E9E), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Alerts (${alerts.size})", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Button(
                    onClick = {
                        ctx.startActivity(Intent(ctx, AlarmActivity::class.java).apply { putExtra("test", true); putExtra("price", price); putExtra("target", price) })
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF23282E)),
                    shape = RoundedCornerShape(20.dp)
                ) { Text("Test ring", fontSize = 13.sp) }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(alerts, key = { it.id }) { a ->
                    AlertRow(a,
                        distance = if (price > 0) price - a.targetPrice else 0.0,
                        onToggle = { scope.launch(Dispatchers.IO) { db.dao().setActive(a.id, !a.active) } },
                        onDelete = { scope.launch(Dispatchers.IO) { db.dao().delete(a) } }
                    )
                }
            }
        }
        if (showSheet) {
            ModalBottomSheet(onDismissRequest = { showSheet = false }, containerColor = Color(0xFF14181D)) {
                AddAlertSheet(
                    current = price,
                    onSave = { target, dir ->
                        scope.launch(Dispatchers.IO) {
                            db.dao().insert(Alert(targetPrice = target, direction = dir))
                            withContext(Dispatchers.Main) { showSheet = false }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AlertRow(a: Alert, distance: Double, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF14181D), RoundedCornerShape(12.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                "${if (a.direction == "above") "Above" else "Below"} $${"%,.1f".format(a.targetPrice)}",
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium
            )
            Text(
                if (!a.active) "off" else "$${"%,.1f".format(kotlin.math.abs(distance))} away",
                color = Color(0xFF9E9E9E), fontSize = 13.sp
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = a.active, onCheckedChange = { onToggle() })
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFF9E9E9E))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAlertSheet(current: Double, onSave: (Double, String) -> Unit) {
    var text by remember { mutableStateOf(if (current > 0) "${current.toInt()}" else "") }
    var dirIdx by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text("New BTCUSD alert", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Above", "Below").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = dirIdx == i, onClick = { dirIdx = i },
                    shape = SegmentedButtonDefaults.itemShape(i, 2)
                ) { Text(label) }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("Target price (USD)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text("Watches Bybit BTCUSD perp lastPrice.", color = Color(0xFF9E9E9E), fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { text.toDoubleOrNull()?.let { onSave(it, if (dirIdx == 0) "above" else "below") } },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF7A600), contentColor = Color.Black)
        ) { Text("Save alert", fontWeight = FontWeight.Medium) }
        Spacer(Modifier.height(24.dp))
    }
}
