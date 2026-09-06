package com.hermes.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.theme.HermesMobileTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onPaired: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Pair with Hermes Desktop",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Scan the QR code from your Hermes Desktop to connect",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(32.dp))
        
        // Placeholder for QR scanner button
        Button(
            onClick = { /* TODO: Implement QR scanner */ },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Scan QR Code")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Or enter details manually",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        // Manual entry fields would go here
        // For now, just show placeholders
        Text(
            text = "Desktop URL: [will be filled from QR]",
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        Text(
            text = "Pairing Token: [will be filled from QR]",
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = { onPaired("http://100.89.25.56:8642") }, // Placeholder
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Connect")
        }
    }
}