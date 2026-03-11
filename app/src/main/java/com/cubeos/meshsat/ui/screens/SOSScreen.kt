package com.cubeos.meshsat.ui.screens

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cubeos.meshsat.ble.MeshtasticBle
import com.cubeos.meshsat.bt.IridiumSpp
import com.cubeos.meshsat.service.GatewayService
import com.cubeos.meshsat.ui.theme.MeshSatAmber
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatGreen
import com.cubeos.meshsat.ui.theme.MeshSatRed
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted

@Composable
fun SOSScreen() {
    val context = LocalContext.current
    val sosActive by GatewayService.sosActive.collectAsState()
    val sosSends by GatewayService.sosSends.collectAsState()

    val meshConnected = GatewayService.meshtasticBle?.state?.collectAsState()?.value == MeshtasticBle.State.Connected
    val iridiumConnected = GatewayService.iridiumSpp?.state?.collectAsState()?.value == IridiumSpp.State.Connected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Emergency SOS",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        // SOS button
        Spacer(modifier = Modifier.height(32.dp))

        if (sosActive) {
            // Pulsing animation when active
            val transition = rememberInfiniteTransition(label = "sos")
            val pulseAlpha by transition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                label = "pulse",
            )

            Box(
                modifier = Modifier
                    .size(200.dp)
                    .alpha(pulseAlpha)
                    .background(MeshSatRed, CircleShape)
                    .border(4.dp, MeshSatRed.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "SOS",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = "ACTIVE",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                    Text(
                        text = "$sosSends/3 sent",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    context.startService(
                        Intent(context, GatewayService::class.java)
                            .setAction(GatewayService.ACTION_SOS_CANCEL)
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatAmber),
                modifier = Modifier.fillMaxWidth(0.6f),
            ) {
                Text("CANCEL SOS", fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = {
                    context.startService(
                        Intent(context, GatewayService::class.java)
                            .setAction(GatewayService.ACTION_SOS_ACTIVATE)
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = MeshSatRed),
                modifier = Modifier.size(200.dp),
                shape = CircleShape,
            ) {
                Text(
                    text = "SOS",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Info card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MeshSatSurface, RoundedCornerShape(8.dp))
                .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "When activated, SOS will:",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "1. Get your GPS position\n2. Send emergency message 3 times (30s apart)\n3. Broadcast on ALL active channels:",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
            )

            // Channel status
            ChannelStatusRow("Meshtastic (BLE)", meshConnected)
            ChannelStatusRow("Iridium (SBD)", iridiumConnected)
            ChannelStatusRow("SMS to Pi", true) // SMS is always available

            Text(
                text = "Message format: SOS EMERGENCY - MeshSat - [GPS coords]",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ChannelStatusRow(label: String, connected: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (connected) "Ready" else "Offline",
            style = MaterialTheme.typography.bodySmall,
            color = if (connected) MeshSatGreen else MeshSatTextMuted,
        )
    }
}
