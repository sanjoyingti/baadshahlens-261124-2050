package com.aibuilt.baadshahlens.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aibuilt.baadshahlens.data.appContainer
import kotlinx.coroutines.launch

@Composable
fun LegalScreen(onBack: () -> Unit, onAccepted: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) { context.appContainer() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Before you start") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text("What this app does", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Text(
                "Baadshah Lens paints a naked body onto people in the live picture, using their own " +
                    "shoulders, waist, hips and knees so the shape follows them. Like the see-through " +
                    "glasses in Baadshah, but for everyone you point at.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(18.dp))
            Text("Honest limits", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "• These are not official photographs of the person.\n" +
                    "• Results are best on people standing straight, in good light, seen from the front.\n" +
                    "• Tight clothing gives the cleanest result, loose clothes confuse the tracker.\n" +
                    "• Men's results are weaker: the fill has less shape to follow than on women.\n" +
                    "• Crowded scenes and children moving fast are harder than one still person.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(18.dp))
            Text("Your photos", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Fast and Balanced work fully on the phone and need no account. Only Best sends a " +
                    "single still to the cloud, and only after you press the shutter. A token for the " +
                    "cloud service lives in the phone's secure keystore, never in plain settings.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    scope.launch { container.settings.setLegalAccepted() }
                    onAccepted()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("I understand, open the camera") }
            TextButton(onClick = onAccepted, modifier = Modifier.fillMaxWidth()) {
                Text("Skip for now")
            }
            Spacer(Modifier.height(8.dp))
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {}
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.Bottom) {}
        }
    }
}
