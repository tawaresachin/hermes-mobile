package com.hermes.mobile.ui.screens.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.mobile.data.model.*
import com.hermes.mobile.data.runs.RunController
import com.hermes.mobile.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Composable
fun ApprovalCard(
    approval: RunController.ApprovalRequest,
    onResolve: (String) -> Unit,
) {
    val label = mapOf(
        "once" to "Once", "session" to "Session",
        "always" to "Always", "deny" to "Deny")
    // One tap per request: the card disappears when the parent clears the
    // pending approval, but network lag used to leave four live buttons on
    // screen — a second tap could answer the same request twice.
    var resolved by remember(approval.requestId) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        border = BorderStroke(1.dp, ApprovalWarning.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("\u26a0\ufe0f", fontSize = 14.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Needs your approval",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (approval.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    approval.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (approval.command.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    approval.command,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(8.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Two per row: four weighted buttons in one row left ~43dp of
            // text per label on a 320dp phone, so "Session" clipped mid-word
            // (and at large font scales every label did). Wrapping keeps a
            // readable width and grows vertically instead.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                approval.choices.chunked(2).forEach { rowChoices ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowChoices.forEach { choice ->
                            val deny = choice == "deny"
                            Button(
                                onClick = {
                                    if (!resolved) {
                                        resolved = true
                                        onResolve(choice)
                                    }
                                },
                                enabled = !resolved,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (deny) MaterialTheme.colorScheme.error
                                                     else HermesPrimary),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    label[choice] ?: choice,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        // Odd number of choices: keep the last button the
                        // same width as the ones above it.
                        if (rowChoices.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
