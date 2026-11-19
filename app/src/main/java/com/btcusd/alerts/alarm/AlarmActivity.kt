package com.btcusd.alerts.alarm

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.Room
import com.btcusd.alerts.R
import com.btcusd.alerts.data.AlertDb
import com.btcusd.alerts.data.MIGRATION_1_2
import com.btcusd.alerts.data.MIGRATION_2_3
import com.btcusd.alerts.data.MIGRATION_3_4
import com.btcusd.alerts.data.TriggerDetector
import com.btcusd.alerts.data.fmtPrice
import com.btcusd.alerts.data.marketOf
import com.btcusd.alerts.data.tradingViewUrl
import com.btcusd.alerts.ui.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Full-screen incoming-call style alarm. Shows over lock screen,
 * keeps ringing until Dismiss / Snooze. Premium black terminal look.
 * Includes Open Chart (TradingView) button, alert type indicator,
 * pattern/trigger confirmation badge, and snooze duration options.
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )
        val alertId = intent.getLongExtra("alertId", 0L)
        val oneShot = intent.getBooleanExtra("oneShot", true)
        val symbol = intent.getStringExtra("symbol") ?: "BTCUSD"
        val m = marketOf(symbol)
        val target = intent.getDoubleExtra("target", 0.0)
        val price = intent.getDoubleExtra("price", 0.0)
        val ringtoneStr = intent.getStringExtra("ringtone")
        val alertType = intent.getStringExtra("alertType") ?: "LEVEL"
        val triggerTitle = intent.getStringExtra("triggerTitle")
        val triggerDesc = intent.getStringExtra("triggerDesc")
        val isTest = intent.getBooleanExtra("test", false)
        val up = price >= target

        RingtonePlayer.start(
            this,
            if (isTest) null else ringtoneStr?.let { runCatching { Uri.parse(it) }.getOrNull() },
            if (isTest) null else alertType
        )

        setContent {
            AppTheme {
                var snoozeMins by remember { mutableIntStateOf(5) }

                val (typeBg, typeBorder, typeFg, typeLabel) = when (alertType.uppercase()) {
                    "SL" -> Quad(Color(0x26F6465D), Color(0xFFF6465D), Color(0xFFF6465D), "🛑 STOP LOSS HIT")
                    "TP" -> Quad(Color(0x260ECB81), Color(0xFF0ECB81), Color(0xFF0ECB81), "💰 TAKE PROFIT HIT")
                    "ENTRY" -> Quad(Color(0x262962FF), Color(0xFF2962FF), Color(0xFF82B1FF), "🎯 ENTRY TRIGGERED")
                    "LEVEL" -> Quad(Color(0x26F7A600), Color(0xFFF7A600), Color(0xFFF7A600), "📊 LEVEL CROSSED")
                    else -> Quad(Color(0x26AB47BC), Color(0xFFAB47BC), Color(0xFFE1BEE7), "🔔 $alertType CROSSED")
                }

                Column(
                    Modifier.fillMaxSize().background(Color(0xFF0B0E11)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(symbol + " • LIVE", color = Color(0xFF9E9E9E), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(14.dp))

                    if (isTest) {
                        Box(
                            modifier = Modifier
                                .background(Color(0x26F7A600), RoundedCornerShape(16.dp))
                                .border(1.dp, Color(0xFFF7A600), RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("TEST RING", color = Color(0xFFF7A600), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .background(typeBg, RoundedCornerShape(16.dp))
                                .border(1.dp, typeBorder, RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(typeLabel, color = typeFg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (triggerTitle != null) {
                        Spacer(Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0x332962FF), RoundedCornerShape(14.dp))
                                .border(1.dp, Color(0xFF2962FF), RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("⚡ $triggerTitle", color = Color(0xFF82B1FF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "$${fmtPrice(m, price)}",
                        color = Color.White,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))

                    if (triggerDesc != null) {
                        Text(
                            triggerDesc,
                            color = Color(0xFFE0E0E0),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Level target: $${fmtPrice(m, target)}",
                            color = Color(0xFF9E9E9E),
                            fontSize = 13.sp
                        )
                    } else {
                        Text(
                            "crossed $${fmtPrice(m, target)}  (${if (up) "▲ ABOVE" else "▼ BELOW"})",
                            color = if (up) Color(0xFF0ECB81) else Color(0xFFF6465D),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(Modifier.height(30.dp))

                    // Open Chart (TradingView) button
                    Button(
                        onClick = {
                            RingtonePlayer.stop(this@AlarmActivity)
                            val tvUrl = tradingViewUrl(m)
                            runCatching {
                                val chartIntent = Intent(Intent.ACTION_VIEW, Uri.parse(tvUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(chartIntent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2962FF),
                            contentColor = Color.White
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_tradingview),
                                contentDescription = "TradingView logo",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Open ${m.id} in TradingView", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // Snooze duration selector
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Snooze duration:", color = Color(0xFF9E9E9E), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5, 15, 30, 60).forEach { mins ->
                                val selected = snoozeMins == mins
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (selected) Color(0xFFF7A600) else Color(0xFF1E2329),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .clickable { snoozeMins = mins }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = if (mins == 60) "1h" else "${mins}m",
                                        color = if (selected) Color.Black else Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Action buttons: Dismiss / Snooze
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                RingtonePlayer.stop(this@AlarmActivity)
                                CoroutineScope(Dispatchers.IO).launch {
                                    runCatching {
                                        val db = Room.databaseBuilder(
                                            this@AlarmActivity, AlertDb::class.java, "alerts.db"
                                        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
                                        if (!isTest && oneShot && alertId != 0L) db.dao().deleteById(alertId)
                                        db.close()
                                    }
                                }
                                finish()
                            },
                            modifier = Modifier.weight(1.2f).height(54.dp),
                            shape = RoundedCornerShape(27.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF7A600),
                                contentColor = Color.Black
                            )
                        ) {
                            Text("DISMISS ALARM", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                RingtonePlayer.stop(this@AlarmActivity)
                                CoroutineScope(Dispatchers.IO).launch {
                                    runCatching {
                                        val db = Room.databaseBuilder(
                                            this@AlarmActivity, AlertDb::class.java, "alerts.db"
                                        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
                                        if (!isTest && alertId != 0L) {
                                             db.dao().snooze(alertId, System.currentTimeMillis() + snoozeMins * 60_000L)
                                        }
                                        db.close()
                                    }
                                }
                                finish()
                            },
                            modifier = Modifier.weight(0.8f).height(54.dp),
                            shape = RoundedCornerShape(27.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2A3138),
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                "Snooze ${if (snoozeMins == 60) "1h" else "${snoozeMins}m"}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        RingtonePlayer.stop(this)
        super.onDestroy()
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
