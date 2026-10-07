package com.btcusd.alerts.alarm

import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.Room
import com.btcusd.alerts.data.AlertDb
import com.btcusd.alerts.ui.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Full-screen incoming-call style alarm. Shows over lock screen,
 * keeps ringing until Dismiss / Snooze. Premium black terminal look.
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
        RingtonePlayer.start(this)

        val target = intent.getDoubleExtra("target", 0.0)
        val price = intent.getDoubleExtra("price", 0.0)
        val isTest = intent.getBooleanExtra("test", false)
        val up = price >= target

        setContent {
            AppTheme {
                LaunchedEffect(Unit) { }
                Column(
                    Modifier.fillMaxSize().background(Color(0xFF0B0E11)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("BTCUSD PERP • BYBIT • LAST", color = Color(0xFF9E9E9E), fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (isTest) "TEST RING" else "PRICE CROSSED",
                        color = if (up) Color(0xFF0ECB81) else Color(0xFFF6465D),
                        fontSize = 15.sp, fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("$${"%,.1f".format(price)}", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text("crossed $${"%,.1f".format(target)}", color = Color(0xFFB0B0B0), fontSize = 14.sp)
                    Spacer(Modifier.height(32.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { RingtonePlayer.stop(); finish() },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(26.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3138), contentColor = Color.White)
                        ) { Text("Dismiss") }
                        Button(
                            onClick = {
                                RingtonePlayer.stop()
                                CoroutineScope(Dispatchers.IO).launch {
                                    // Snooze most recent matching alert 5m (best-effort).
                                    runCatching {
                                        val db = Room.databaseBuilder(
                                            this@AlarmActivity, AlertDb::class.java, "alerts.db"
                                        ).build()
                                        db.dao().active().firstOrNull { it.targetPrice == target }
                                            ?.let { db.dao().snooze(it.id, System.currentTimeMillis() + 5 * 60_000) }
                                        db.close()
                                    }
                                }
                                finish()
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(26.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF7A600))
                        ) { Text("Snooze 5m", color = Color.Black) }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        RingtonePlayer.stop()
        super.onDestroy()
    }
}
