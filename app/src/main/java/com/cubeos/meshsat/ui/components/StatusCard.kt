package com.cubeos.meshsat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cubeos.meshsat.ui.theme.MeshSatBorder
import com.cubeos.meshsat.ui.theme.MeshSatGreen
import com.cubeos.meshsat.ui.theme.MeshSatRed
import com.cubeos.meshsat.ui.theme.MeshSatSurface
import com.cubeos.meshsat.ui.theme.MeshSatTextMuted

@Composable
fun StatusCard(
    title: String,
    status: String,
    isOnline: Boolean,
    color: Color,
    detail: String = "",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MeshSatSurface, RoundedCornerShape(8.dp))
            .border(1.dp, MeshSatBorder, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isOnline) MeshSatGreen else MeshSatRed)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = color,
            )
        }

        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MeshSatTextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
