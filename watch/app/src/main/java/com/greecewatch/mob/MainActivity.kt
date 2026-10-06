package com.greecewatch.mob

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText

class MainActivity : ComponentActivity() {

    private var pendingAction: String? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true && action != null) {
            OverboardService.send(this, action)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by OverboardService.state.collectAsState()
            // Keep the screen on during an alert so the "I'm safe" button is right there.
            if (state.alertActive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            MaterialTheme { Screen(state, ::run) }
        }
    }

    private fun run(action: String) {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) {
            OverboardService.send(this, action)
        } else {
            pendingAction = action
            permissions.launch(needed.toTypedArray())
        }
    }
}

private val Red = Color(0xFFD32F2F)
private val Green = Color(0xFF2E7D32)

@Composable
private fun Screen(state: WatchState, run: (String) -> Unit) {
    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    text = when {
                        state.alertActive -> "MAN OVERBOARD"
                        state.armed -> "ARMED"
                        else -> "Disarmed"
                    },
                    color = if (state.alertActive) Red else if (state.armed) Green else Color.LightGray,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.title2,
                )
            }
            if (state.status.isNotEmpty()) {
                item { Text(state.status, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2) }
            }
            if (state.armed) {
                item { Text("Water: %.0f cm (alert at 20)".format(state.depthCm), style = MaterialTheme.typography.caption3) }
            }

            if (state.alertActive) {
                item {
                    Chip(
                        onClick = { run(OverboardService.ACTION_SAFE) },
                        label = { Text("I'M SAFE", fontWeight = FontWeight.Bold) },
                        secondaryLabel = { Text("Cancel the alert") },
                        colors = ChipDefaults.primaryChipColors(backgroundColor = Green),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                item {
                    Chip(
                        onClick = { run(if (state.armed) OverboardService.ACTION_DISARM else OverboardService.ACTION_ARM) },
                        label = { Text(if (state.armed) "Disarm" else "Arm") },
                        secondaryLabel = { Text(if (state.armed) "Before a planned swim" else "Alert if I go in the water") },
                        colors = if (state.armed) ChipDefaults.secondaryChipColors() else ChipDefaults.primaryChipColors(backgroundColor = Green),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Chip(
                        onClick = { run(OverboardService.ACTION_TRIGGER) },
                        label = { Text("Send MOB now") },
                        secondaryLabel = { Text("Manual alert") },
                        colors = ChipDefaults.primaryChipColors(backgroundColor = Red),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Chip(
                        onClick = { run(OverboardService.ACTION_TEST) },
                        label = { Text("Send test") },
                        secondaryLabel = { Text("Marked TEST in WhatsApp") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
