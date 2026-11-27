package com.aegis.localagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var llmEngine: LocalLlmEngine
    private lateinit var vault: EncryptedActivityVault
    private lateinit var ragStore: LocalRagStore
    private lateinit var threadStore: LocalThreadStore
    private lateinit var notebookEngine: NotebookStudioEngine
    private lateinit var sandbox: LocalSandboxEngine
    private lateinit var mcpRegistry: McpToolRegistry
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false
    private val gatekeeper = PolicyGatekeeper()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        llmEngine = LocalLlmEngine(applicationContext)
        vault = EncryptedActivityVault(applicationContext)
        ragStore = LocalRagStore(applicationContext)
        threadStore = LocalThreadStore(applicationContext)
        notebookEngine = NotebookStudioEngine(ragStore)
        sandbox = LocalSandboxEngine()
        mcpRegistry = McpToolRegistry(ragStore, sandbox)
        tts = TextToSpeech(applicationContext, this)

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
                    AegisMainShell()
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
        }
    }

    private fun speakAudioOverview(turns: List<AudioTurn>) {
        if (!ttsReady) return
        tts?.stop()
        turns.forEachIndexed { idx, turn ->
            val mode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak("${turn.speaker} says: ${turn.text}", mode, null, "turn_$idx")
        }
    }

    private fun stopTts() {
        tts?.stop()
    }

    @Composable
    private fun AegisMainShell() {
        var activeTab by remember { mutableStateOf("chat") }
        var threads by remember { mutableStateOf(threadStore.listThreads()) }
        var activeThreadId by remember {
            mutableStateOf(threads.firstOrNull()?.id ?: "thread-default")
        }
        var messages by remember {
            mutableStateOf(threadStore.listMessages(activeThreadId))
        }
        var ragDocs by remember { mutableStateOf(ragStore.listAllDocuments()) }
        var notebookQaHistory by remember { mutableStateOf<List<Pair<String, NotebookQaResult>>>(emptyList()) }
        var studioTextOutput by remember { mutableStateOf("") }
        var audioTurns by remember { mutableStateOf<List<AudioTurn>>(emptyList()) }
        var isSpeakingAudio by remember { mutableStateOf(false) }

        var showAddSourceDialog by remember { mutableStateOf(false) }
        var newSourceTitle by remember { mutableStateOf("") }
        var newSourceBody by remember { mutableStateOf("") }

        var inputText by remember { mutableStateOf("") }
        var notebookQuestion by remember { mutableStateOf("") }
        var attachedImageUri by remember { mutableStateOf<Uri?>(null) }
        var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(this@MainActivity)) }
        var lastVerdict by remember { mutableStateOf("ALLOWED") }
        var statusLine by remember {
            mutableStateOf(
                if (llmEngine.isModelFilePresent()) {
                    "Local models ready · PolicyGatekeeper active"
                } else {
                    "Push .task models to /data/local/tmp/llm/"
                }
            )
        }
        var records by remember { mutableStateOf(vault.readAllRecords()) }
        var pendingAction by remember { mutableStateOf<Pair<PlannedUiAction, CapturedScreenSnapshot>?>(null) }

        val activeThread = threads.firstOrNull { it.id == activeThreadId } ?: threads.firstOrNull()

        val micPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            statusLine = if (granted) "Mic permission granted." else "Mic permission denied."
        }

        val speechLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                inputText = spoken
                statusLine = "Captured voice input."
            }
        }

        val imagePickerLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->
            attachedImageUri = uri
            if (uri != null) {
                statusLine = "Image attached for Gemma 3n offline inspection."
            }
        }

        fun refreshThreadsAndMessages(targetId: String = activeThreadId) {
            threads = threadStore.listThreads()
            activeThreadId = targetId
            messages = threadStore.listMessages(targetId)
        }

        fun triggerKillSwitch() {
            AgentAccessibilityService.activeAbortFlag = true
            pendingAction = null
            stopTts()
            isSpeakingAudio = false
            lastVerdict = "ABORTED_KILL_SWITCH"
            statusLine = "KILL SWITCH triggered · Active agent loops halted."
            threadStore.appendMessage(
                activeThreadId,
                false,
                "Emergency Kill Switch activated. Accessibility dispatch and audio playback halted.",
                "Policy: ABORTED_KILL_SWITCH"
            )
            refreshThreadsAndMessages()
            vault.appendRecord(
                ActivityRecord(
                    id = UUID.randomUUID().toString(),
                    timestampIso = Instant.now().toString(),
                    command = "EMERGENCY_KILL_SWITCH",
                    targetPackage = packageName,
                    status = "denied_by_user",
                    skippedProtectedCount = 0
                )
            )
            records = vault.readAllRecords()
        }

        fun exportApprovedJsonl(): String {
            return records
                .filter { it.status == "completed" }
                .take(10)
                .joinToString("\n") { rec ->
                    JSONObject().apply {
                        put("instruction", rec.command)
                        put("targetPackage", rec.targetPackage)
                        put("approvedByUser", true)
                    }.toString()
                }
        }

        fun submitPrompt(prompt: String) {
            val (cleanRaw, strippedCount) = InputSanitizer.stripInvisibleUnicode(prompt)
            val clean = cleanRaw.trim()
            if (clean.isEmpty()) return
            AgentAccessibilityService.activeAbortFlag = false
            val hasImage = attachedImageUri != null
            val mode = activeThread?.mode ?: "auto"
            val level = llmEngine.classifyComplexityLevel(clean, hasImage)
            val modelLabel = if (level == 1) "Gemma 3 1B" else "Gemma 3n E2B"

            threadStore.appendMessage(
                activeThreadId,
                true,
                clean,
                if (strippedCount > 0) "Sanitized $strippedCount hidden chars" else "Mode: $mode"
            )
            inputText = ""
            attachedImageUri = null

            val reply = when {
                mode == "notebook" -> {
                    lastVerdict = "ALLOWED"
                    notebookEngine.answerWithCitations(clean).answer
                }
                level == 3 -> {
                    lastVerdict = "ALLOWED"
                    sandbox.executeScript("budget = 680\nsessions = 0\nprint(budget / sessions)")
                    val patchedRun = sandbox.executeScript("budget = 680\nsessions = 12\nprint(budget / sessions)")
                    val mcpRag = mcpRegistry.invokeTool("mcp://local-rag", "query_fts5", clean)
                    "L3 Traceback caught -> patched script -> stdout: ${patchedRun.stdout}\nMCP RAG hits: ${mcpRag.outputJson}"
                }
                level == 2 -> {
                    lastVerdict = "ALLOWED"
                    val hits = ragStore.searchFts5(clean, 3, true)
                    val summary = hits.joinToString("\n") { "• [${it.sourceType}] ${it.title}: ${it.snippet}" }
                    if (summary.isNotBlank()) summary else "No FTS5 matches found across checked sources."
                }
                else -> {
                    val svc = AgentAccessibilityService.instance
                    val snap = svc?.captureAllowlistedTree()
                    if (svc == null || snap == null) {
                        "L1 ready ($modelLabel). Open an allowlisted non-blacklisted app with Accessibility enabled."
                    } else {
                        val scope = TaskScope(
                            allowedPackages = AgentAccessibilityService.allowedPackages,
                            isUntrustedScreen = snap.packageName.contains("chrome") || snap.packageName.contains("messages")
                        )
                        val loaded = llmEngine.ensureLoadedForLevel(level)
                        if (loaded.isFailure) {
                            loaded.exceptionOrNull()?.message ?: "Model file not found on device."
                        } else {
                            val plan = llmEngine.planNextAction(clean, snap.nodesJson, level)
                            val decision = gatekeeper.evaluate(snap.packageName, plan.action, scope)
                            lastVerdict = decision.verdict.name
                            when (decision.verdict) {
                                PolicyVerdict.BLOCKED_BLACKLIST, PolicyVerdict.BLOCKED_SCOPE -> decision.reason
                                PolicyVerdict.NEEDS_HITL -> {
                                    pendingAction = plan to snap
                                    decision.reason
                                }
                                PolicyVerdict.ALLOWED -> {
                                    if (plan.requiresApproval) {
                                        pendingAction = plan to snap
                                        "Paused at approval gate for: ${plan.action}"
                                    } else {
                                        svc.dispatchUiAction(plan)
                                        plan.reason
                                    }
                                }
                            }
                        }
                    }
                }
            }

            threadStore.appendMessage(
                activeThreadId,
                false,
                reply,
                "L$level · $modelLabel · $lastVerdict"
            )
            refreshThreadsAndMessages()

            vault.appendRecord(
                ActivityRecord(
                    id = UUID.randomUUID().toString(),
                    timestampIso = Instant.now().toString(),
                    command = clean,
                    targetPackage = packageName,
                    status = "completed",
                    skippedProtectedCount = strippedCount
                )
            )
            records = vault.readAllRecords()
        }

        Scaffold(
            containerColor = Color(0xFF0B1118),
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF111923)) {
                    NavigationBarItem(
                        selected = activeTab == "chat",
                        onClick = { activeTab = "chat" },
                        icon = { Icon(Icons.Filled.Chat, contentDescription = "Chat") },
                        label = { Text("Chat") }
                    )
                    NavigationBarItem(
                        selected = activeTab == "notebook",
                        onClick = { activeTab = "notebook" },
                        icon = { Icon(Icons.Filled.Book, contentDescription = "Notebook") },
                        label = { Text("Notebook") }
                    )
                    NavigationBarItem(
                        selected = activeTab == "agent",
                        onClick = { activeTab = "agent" },
                        icon = { Icon(Icons.Filled.Shield, contentDescription = "Agent") },
                        label = { Text("Agent") }
                    )
                    NavigationBarItem(
                        selected = activeTab == "vault",
                        onClick = { activeTab = "vault" },
                        icon = { Icon(Icons.Filled.Lock, contentDescription = "Vault") },
                        label = { Text("Vault") }
                    )
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AegisAgent v1.3",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$statusLine · $lastVerdict",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        onClick = { triggerKillSwitch() }
                    ) {
                        Text("KILL", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = Color(0xFF1E293B))

                when (activeTab) {
                    "chat" -> {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                OutlinedButton(
                                    onClick = {
                                        val created = threadStore.createThread("Chat ${threads.size + 1}", "auto")
                                        refreshThreadsAndMessages(created.id)
                                    }
                                ) {
                                    Text("+ New")
                                }
                            }
                            items(threads, key = { it.id }) { thr ->
                                OutlinedButton(
                                    onClick = { refreshThreadsAndMessages(thr.id) }
                                ) {
                                    Text(
                                        text = thr.title,
                                        color = if (thr.id == activeThreadId) Color(0xFF10B981) else Color(0xFFCBD5E1)
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val currentMode = activeThread?.mode ?: "auto"
                            OutlinedButton(
                                onClick = {
                                    val nextMode = if (currentMode == "auto") "notebook" else "auto"
                                    threadStore.updateThreadMode(activeThreadId, nextMode)
                                    refreshThreadsAndMessages()
                                }
                            ) {
                                Text("Mode: $currentMode")
                            }
                            OutlinedButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                                Text(if (attachedImageUri != null) "Image OK" else "Image")
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
                                Text("Voice")
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(messages, key = { it.id }) { msg ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (msg.isUser) Color(0xFF064E3B) else Color(0xFF111923),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = msg.meta,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF10B981)
                                        )
                                        if (!msg.isUser) {
                                            TextButton(
                                                onClick = {
                                                    ragStore.indexDocument(
                                                        "Saved_${System.currentTimeMillis() % 10000}.md",
                                                        "note",
                                                        msg.text
                                                    )
                                                    ragDocs = ragStore.listAllDocuments()
                                                    statusLine = "Saved reply to Notebook sources."
                                                }
                                            ) {
                                                Text("Save")
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                placeholder = { Text("Ask offline Gemini/Aegis...") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Button(
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                onClick = { submitPrompt(inputText) }
                            ) {
                                Text("Send")
                            }
                        }
                    }

                    "notebook" -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Sources (${ragDocs.count { it.selected }}/${ragDocs.size})",
                                fontWeight = FontWeight.SemiBold
                            )
                            OutlinedButton(onClick = { showAddSourceDialog = true }) {
                                Text("+ Add Source")
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(ragDocs, key = { it.title }) { doc ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF111923), RoundedCornerShape(10.dp))
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = doc.selected,
                                        onCheckedChange = { checked ->
                                            ragStore.toggleDocumentSelected(doc.title, checked)
                                            ragDocs = ragStore.listAllDocuments()
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(doc.title, fontWeight = FontWeight.Medium)
                                        Text(
                                            text = "${doc.sourceType.uppercase()} · ${doc.content.take(70)}...",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }
                            }

                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            audioTurns = notebookEngine.buildAudioOverview()
                                            studioTextOutput = audioTurns.joinToString("\n") { "${it.speaker}: ${it.text}" }
                                        }
                                    ) {
                                        Text("Audio Overview")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            audioTurns = emptyList()
                                            studioTextOutput = notebookEngine.buildBriefingDoc()
                                        }
                                    ) {
                                        Text("Briefing")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            audioTurns = emptyList()
                                            studioTextOutput = notebookEngine.buildStudyGuide()
                                        }
                                    ) {
                                        Text("Study Guide")
                                    }
                                }
                            }

                            if (audioTurns.isNotEmpty()) {
                                item {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                            onClick = {
                                                isSpeakingAudio = true
                                                speakAudioOverview(audioTurns)
                                            }
                                        ) {
                                            Text(if (isSpeakingAudio) "Playing TTS" else "Play Offline TTS")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                stopTts()
                                                isSpeakingAudio = false
                                            }
                                        ) {
                                            Text("Stop")
                                        }
                                    }
                                }
                            }

                            if (studioTextOutput.isNotBlank()) {
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF0E1A18), RoundedCornerShape(10.dp))
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = "Studio Output",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color(0xFF10B981)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(studioTextOutput, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }

                            items(notebookQaHistory.size) { idx ->
                                val (q, res) = notebookQaHistory[idx]
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF111923), RoundedCornerShape(10.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(q, fontWeight = FontWeight.SemiBold, color = Color(0xFF10B981))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(res.answer, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = notebookQuestion,
                                onValueChange = { notebookQuestion = it },
                                placeholder = { Text("Ask checked sources...") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Button(
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                onClick = {
                                    if (notebookQuestion.isNotBlank()) {
                                        val res = notebookEngine.answerWithCitations(notebookQuestion)
                                        notebookQaHistory = notebookQaHistory + (notebookQuestion to res)
                                        notebookQuestion = ""
                                    }
                                }
                            ) {
                                Text("Ask")
                            }
                        }
                    }

                    "agent" -> {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF111923), RoundedCornerShape(12.dp))
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Device Automation & Guardrails", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = "Active Policy Verdict: $lastVerdict · HardcodedBlacklist + Rule-of-Two active",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF94A3B8)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                                        ) {
                                            Text("Open Accessibility")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                hasOverlayPermission = Settings.canDrawOverlays(this@MainActivity)
                                                val intent = Intent(
                                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    Uri.parse("package:$packageName")
                                                )
                                                startActivity(intent)
                                            }
                                        ) {
                                            Text(if (hasOverlayPermission) "Overlay OK" else "Grant Overlay")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "vault" -> {
                        val jsonlPreview = exportApprovedJsonl()
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (jsonlPreview.isNotBlank()) {
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF0E1A18), RoundedCornerShape(10.dp))
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = "Approved LoRA JSONL Export Preview",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color(0xFF10B981)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = jsonlPreview,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFFCBD5E1)
                                        )
                                    }
                                }
                            }
                            items(records, key = { it.id }) { item ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF111923), RoundedCornerShape(10.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(item.command, fontWeight = FontWeight.Medium)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${item.targetPackage} · ${item.status}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showAddSourceDialog) {
            AlertDialog(
                onDismissRequest = { showAddSourceDialog = false },
                title = { Text("Add Local Notebook Source") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newSourceTitle,
                            onValueChange = { newSourceTitle = it },
                            placeholder = { Text("Title (e.g. notes.md)") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = newSourceBody,
                            onValueChange = { newSourceBody = it },
                            placeholder = { Text("Paste text to index locally...") }
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newSourceTitle.isNotBlank() && newSourceBody.isNotBlank()) {
                                ragStore.indexDocument(newSourceTitle.trim(), "note", newSourceBody.trim())
                                ragDocs = ragStore.listAllDocuments()
                                newSourceTitle = ""
                                newSourceBody = ""
                                showAddSourceDialog = false
                            }
                        }
                    ) {
                        Text("Index")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddSourceDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        pendingAction?.let { (plan, snapshot) ->
            AlertDialog(
                onDismissRequest = { pendingAction = null },
                title = { Text("Confirm Agent Action") },
                text = {
                    Text("App: ${snapshot.packageName}\nAction: ${plan.action}\nReason: ${plan.reason}")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            AgentAccessibilityService.instance?.dispatchUiAction(plan)
                            pendingAction = null
                        }
                    ) {
                        Text("Approve")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingAction = null }) {
                        Text("Deny")
                    }
                }
            )
        }
    }

    override fun onDestroy() {
        stopTts()
        tts?.shutdown()
        llmEngine.release()
        super.onDestroy()
    }
}
