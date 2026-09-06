package com.hermes.mobile.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.theme.HermesPrimary

/**
 * Multi-agent visualization showing agents working in parallel.
 * 
 * Displays:
 * - Agent cards with status (idle/working/done)
 * - Real-time progress bars
 * - Agent responses as they complete
 */
@Composable
fun MultiAgentVisualization(
    agents: List<AgentState>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "Multi-Agent (${agents.count { it.status == AgentStatus.WORKING }} working)",
                style = MaterialTheme.typography.labelMedium,
                color = HermesPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(agents) { agent ->
                    AgentCard(agent = agent)
                }
            }
        }
    }
}

@Composable
private fun AgentCard(agent: AgentState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator with animation
        val infiniteTransition = rememberInfiniteTransition()
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(800)
            )
        )
        
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(
                    color = when (agent.status) {
                        AgentStatus.IDLE -> Color.Gray
                        AgentStatus.WORKING -> HermesPrimary.copy(alpha = alpha)
                        AgentStatus.DONE -> Color.Green
                        AgentStatus.ERROR -> Color.Red
                    },
                    shape = MaterialTheme.shapes.small
                )
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Agent name
        Text(
            text = agent.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        
        // Progress or status
        when (agent.status) {
            AgentStatus.WORKING -> {
                LinearProgressIndicator(
                    progress = agent.progress,
                    modifier = Modifier.width(60.dp)
                )
            }
            AgentStatus.DONE -> {
                Text(
                    text = "✓",
                    color = Color.Green,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            AgentStatus.ERROR -> {
                Text(
                    text = "✗",
                    color = Color.Red,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            else -> {}
        }
    }
    
    // Show agent response when done
    if (agent.status == AgentStatus.DONE && agent.response.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = agent.response,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

data class AgentState(
    val id: String,
    val name: String,
    val status: AgentStatus = AgentStatus.IDLE,
    val progress: Float = 0f,
    val response: String = ""
)

enum class AgentStatus {
    IDLE,
    WORKING,
    DONE,
    ERROR
}
