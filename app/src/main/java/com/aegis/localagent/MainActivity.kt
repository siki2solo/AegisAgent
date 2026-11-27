package com.aegis.localagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.time.Instant
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var llmEngine: LocalLlmEngine
    private lateinit var vault: EncryptedActivityVault

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        llmEngine = LocalLlmEngine(applicationContext)
        vault = EncryptedActivityVault(applicationContext)

        val palette = darkColorScheme(
            primary = Color(0xFF10B981),
            background = Color(0xFF0B1118),
            surface = Color(0xFF111923),
            onPrimary = Color(0xFF06281E),
            onBackground = Color(0xFFE2E8F0),
            onSurface = Color(0xFFE2E8F0)
        )

        setContent {
            MaterialTheme(colorScheme = palette) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentControlScreen()
                }
            }
        }
    }

    @Composable
    private fun AgentControlScreen() {
        var commandText by remember { mutableStateOf("") }
        var statusLine by remember {
            mutableStateOf(
                if (llmEngine.isModelFilePresent()) {
                    "Local model ready: ${llmEngine.configuredModelName}"
                } else {
                    "Push local model to ${llmEngine.configuredModelPath}"
                }
            )
        }
        var records by remember { mutableStateOf(vault.readAllRecords()) }
        var pendingAction by remember { mutableStateOf<Pair<PlannedUiAction, CapturedScreenSnapshot>?>(null) }

        val micPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            statusLine = if (granted) "Microphone permission granted." else "Microphone permission denied."
        }

        val speechLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                commandText = spoken
                statusLine = "Captured voice command."
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1118))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "AegisAgent",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                ) {
                    Text("Accessibility Setup")
                }

                OutlinedButton(
                    onClick = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!hasMic) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                )
                            }
                            speechLauncher.launch(intent)
                        }
                    }
                ) {
                    Text("Voice Input")
                }
            }

            OutlinedTextField(
                value = commandText,
                onValueChange = { commandText = it },
                label = { Text("Task goal for allowlisted apps") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    onClick = {
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) {
                            statusLine = "Enable Accessibility Service first."
                            return@Button
                        }
                        val snapshot = svc.captureAllowlistedTree()
                        if (snapshot == null) {
                            statusLine = "Active window is outside your package allowlist."
                            return@Button
                        }
                        val loadResult = llmEngine.ensureLoaded()
                        if (loadResult.isFailure) {
                            statusLine = loadResult.exceptionOrNull()?.message ?: "Model unavailable."
                            return@Button
                        }
                        val plan = llmEngine.planNextAction(commandText, snapshot.nodesJson)
                        if (plan.requiresApproval) {
                            pendingAction = plan to snapshot
                        } else {
                            val ok = svc.dispatchUiAction(plan)
                            vault.appendRecord(
                                ActivityRecord(
                                    id = UUID.randomUUID().toString(),
                                    timestampIso = Instant.now().toString(),
                                    command = commandText,
                                    targetPackage = snapshot.packageName,
                                    status = if (ok) "completed" else "noop",
                                    skippedProtectedCount = snapshot.skippedProtectedCount
                                )
                            )
                            records = vault.readAllRecords()
                            statusLine = plan.reason
                        }
                    }
                ) {
                    Text("Analyze & Plan")
                }

                TextButton(
                    onClick = {
                        vault.wipeVault()
                        records = emptyList()
                        statusLine = "Encrypted activity vault cleared."
                    }
                ) {
                    Text("Clear Logs")
                }
            }

            HorizontalDivider(color = Color(0xFF1E293B))
            Text(
                text = "Encrypted Local Logs (${records.size})",
                style = MaterialTheme.typography.titleSmall
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(records, key = { it.id }) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF111923))
                            .padding(12.dp)
                    ) {
                        Text(item.command, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${item.targetPackage} · ${item.status} · skipped ${item.skippedProtectedCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }

        pendingAction?.let { (plan, snapshot) ->
            AlertDialog(
                onDismissRequest = { pendingAction = null },
                title = { Text("Confirm Agent Action") },
                text = {
                    Text(
                        "App: ${snapshot.packageName}\nAction: ${plan.action}\nReason: ${plan.reason}"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val ok = AgentAccessibilityService.instance?.dispatchUiAction(plan) ?: false
                            vault.appendRecord(
                                ActivityRecord(
                                    id = UUID.randomUUID().toString(),
                                    timestampIso = Instant.now().toString(),
                                    command = commandText,
                                    targetPackage = snapshot.packageName,
                                    status = if (ok) "completed" else "failed",
                                    skippedProtectedCount = snapshot.skippedProtectedCount
                                )
                            )
                            records = vault.readAllRecords()
                            statusLine = "Executed approved action: ${plan.action}"
                            pendingAction = null
                        }
                    ) {
                        Text("Approve")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            vault.appendRecord(
                                ActivityRecord(
                                    id = UUID.randomUUID().toString(),
                                    timestampIso = Instant.now().toString(),
                                    command = commandText,
                                    targetPackage = snapshot.packageName,
                                    status = "denied_by_user",
                                    skippedProtectedCount = snapshot.skippedProtectedCount
                                )
                            )
                            records = vault.readAllRecords()
                            statusLine = "Action canceled by user."
                            pendingAction = null
                        }
                    ) {
                        Text("Deny")
                    }
                }
            )
        }
    }

    override fun onDestroy() {
        llmEngine.release()
        super.onDestroy()
    }
}
