package com.hermes.mobile.ui.screens.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.hermes.mobile.data.local.AppDatabase
import com.hermes.mobile.data.model.*
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.regex.Pattern
import javax.inject.Inject

// ═══════════════════════════════════════════════════════════════
// Data classes for tool-call display
// ═══════════════════════════════════════════════════════════════

data class ToolCallInfo(
    val name: String,
    val arguments: String,
    val result: String? = null,
    val id: String = "",
    val status: ToolCallStatus = ToolCallStatus.RUNNING
)

enum class ToolCallStatus { PENDING, RUNNING, COMPLETED, FAILED }

// ═══════════════════════════════════════════════════════════════
// ViewModel  —  uses @HiltViewModel so hiltViewModel() works
// ═══════════════════════════════════════════════════════════════

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: HermesRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // ── Session state ──
    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    // ── Messages from DB (persisted) ──
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    // ── Streaming state ──
    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    private var streamingJob: kotlinx.coroutines.Job? = null
    private var toolClearJob: kotlinx.coroutines.Job? = null

    // ── Tool calls detected during streaming ──
    private val _toolCalls = MutableStateFlow<List<ToolCallInfo>>(emptyList())
    val toolCalls: StateFlow<List<ToolCallInfo>> = _toolCalls.asStateFlow()

    // ── Connection status ──
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    // ── Voice recording ──
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingAmplitude = MutableStateFlow(0f)
    val recordingAmplitude: StateFlow<Float> = _recordingAmplitude.asStateFlow()

    // ── Model selection ──
    private val _currentModel = MutableStateFlow("")
    val currentModel: StateFlow<String> = _currentModel.asStateFlow()

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    // ── Emoji picker ──
    val showEmojiPicker = MutableStateFlow(false)

    // ── Error state ──
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ── Initialisation ──
    private var messageJob: Job? = null
    private var _connectionJob: Job? = null
    private var initJob: Job? = null

    /**
     * Initialise the session. If a session ID is provided, resume it.
     * Otherwise, only create a new session if none exists yet —
     * re-entering the Chat tab must NOT orphan the active session.
     * Cancel any previous init to prevent the "StandaloneCoroutine was cancelled" race.
     */
    fun initSession(sessionId: String?) {
        initJob?.cancel()
        initJob = viewModelScope.launch {
            if (sessionId != null) {
                resumeSession(sessionId)
            } else if (_sessionId.value == null) {
                createNewSession()
            } else {
                // Re-entering the tab with an existing session — just re-observe
                observeMessages(_sessionId.value!!)
            }
        }
    }
    init {
        checkConnection()
        // Poll connection every 5s when not connected
        viewModelScope.launch {
            try {
                while (isActive) {
                    delay(5000)
                    if (_connectionStatus.value != ConnectionStatus.CONNECTED) {
                        checkConnection()
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private suspend fun createNewSession() {
        try {
            val session = repository.createSession()
            _sessionId.value = session.id
            observeMessages(session.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Legitimate cancellation when scope is torn down — suppress
        } catch (e: Exception) {
            _errorMessage.value = "Failed to create session: ${e.message}"
        }
    }

    private suspend fun resumeSession(sessionId: String) {
        try {
            _sessionId.value = sessionId
            observeMessages(sessionId)
            repository.resumeSession(sessionId) // warm cache
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Legitimate cancellation when scope is torn down — suppress
        } catch (e: Exception) {
            _errorMessage.value = "Failed to resume session: ${e.message}"
        }
    }

    private fun observeMessages(sessionId: String) {
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            try {
                repository.getMessages(sessionId).collect { msgList ->
                    _messages.value = msgList
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Legitimate cancellation when session changes — suppress error
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load messages: ${e.message}"
            }
        }
    }

    // ── Send message ──
    fun sendMessage(query: String, attachmentUrl: String? = null, attachType: String? = null) {
        val sid = _sessionId.value ?: return

        // Cancel previous stream and save pending content
        streamingJob?.cancel()
        val pendingContent = _streamingContent.value
        streamingJob = viewModelScope.launch {
            // Save any remaining pending content as a completed message
            if (pendingContent.isNotBlank()) {
                repository.finalizePendingMessage(sid, pendingContent)
            }

            _isStreaming.value = true
            _streamingContent.value = ""
            _toolCalls.value = emptyList()
            _errorMessage.value = null

            // StringBuilder avoids O(n²) re-concat per chunk; UI reads via a
            // throttled accumulator below.
            val streamBuilder = StringBuilder()

            try {
                repository.sendMessage(
                    sessionId = sid,
                    query = query,
                    attachmentUrl = attachmentUrl ?: "",
                    attachType = attachType ?: "",
                    onChunk = { chunk ->
                        streamBuilder.append(chunk)
                        _streamingContent.value = streamBuilder.toString()
                    },
                    onToolCall = { id, name, args ->
                        val tc = ToolCallInfo(
                            id = id,
                            name = name,
                            arguments = args,
                            status = ToolCallStatus.RUNNING
                        )
                        _toolCalls.value = _toolCalls.value + tc
                    },
                    onToolResult = { id, output ->
                        _toolCalls.value = _toolCalls.value.map {
                            if (it.id == id) it.copy(
                                result = output,
                                status = ToolCallStatus.COMPLETED
                            ) else it
                        }
                    }
                )
                _isStreaming.value = false
                _streamingContent.value = ""
                // Cancel any prior delayed clear so it can't wipe the NEXT message's tool calls
                toolClearJob?.cancel()
                toolClearJob = viewModelScope.launch {
                    delay(3000)
                    _toolCalls.value = emptyList()
                }
            } catch (e: Exception) {
                _isStreaming.value = false
                _errorMessage.value = e.message
            }
        }
    }

    private fun detectToolCalls(content: String) {
        // Parse tool call JSON blocks: ```tool_call { ... } ```
        val toolCallPattern = Regex(
            """```tool_call\s*\n?(\{.*?\})\n?```""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val matches = toolCallPattern.findAll(content)
        val calls = matches.mapNotNull { match ->
            try {
                val json = org.json.JSONObject(match.groupValues[1])
                ToolCallInfo(
                    name = json.optString("name", "unknown"),
                    arguments = json.optString("arguments", ""),
                    id = json.optString("id", ""),
                    status = ToolCallStatus.RUNNING
                )
            } catch (e: Exception) { null }
        }.toList()
        if (calls.isNotEmpty()) {
            _toolCalls.value = calls
        }
    }

    fun retryLastMessage() {
        val msgs = _messages.value
        val lastUserMsg = msgs.lastOrNull { it.role == MessageRole.USER } ?: return
        sendMessage(lastUserMsg.content)
    }

    fun getBaseUrl(): String = repository.getBaseUrl()

    // ── Voice dictation ──
    fun startRecording() {
        // Voice recording handled by startVoiceDictation() in ChatScreen directly
        // via SpeechRecognizer with permission launcher
    }

    fun stopRecording() {
        // No-op — voice recording managed by permission launcher
    }

    // ── Connection ──
    fun checkConnection() {
        viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.CONNECTING
            val savedConfig = repository.getSavedConfig()
            if (savedConfig != null) {
                val status = repository.checkConnection(savedConfig)
                _connectionStatus.value = status
            } else {
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
            }
        }
    }

    // ── Clear session ──
    fun clearSession() {
        val sid = _sessionId.value ?: return
        viewModelScope.launch {
            repository.clearSession(sid)
            _messages.value = emptyList()
            _streamingContent.value = ""
            _isStreaming.value = false
        }
    }

    // ── Model Management ──

    fun loadModels() {
        val sid = _sessionId.value ?: return
        viewModelScope.launch {
            _modelsLoading.value = true
            try {
                val response = repository.listModels(sid)
                if (response != null) {
                    _availableModels.value = response.models
                    _currentModel.value = response.current
                }
            } catch (_: Exception) { }
            _modelsLoading.value = false
        }
    }

    fun switchModel(modelId: String, global: Boolean = false) {
        val sid = _sessionId.value ?: return
        viewModelScope.launch {
            val success = repository.switchModel(sid, modelId, global)
            if (success) {
                _currentModel.value = modelId
                if (global) {
                    // Reload to show the new global default
                    loadModels()
                }
            }
        }
    }

    // ── Dismiss error ──
    fun dismissError() {
        _errorMessage.value = null
    }

    fun setError(msg: String) {
        _errorMessage.value = msg
    }

    // ── Upload file (called on send click) ──
        suspend fun uploadFile(context: android.content.Context, uri: android.net.Uri, fileName: String, mimeType: String, attachType: String): String? {
            val sid = _sessionId.value ?: return null
            return try {
                val tempFile = java.io.File(context.cacheDir, fileName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val result = repository.uploadFile(sid, tempFile, fileName, mimeType)
                tempFile.delete()
                result
            } catch (e: Exception) {
                _errorMessage.value = "Upload failed: ${e.message}"
                null
            }
        }

        // ── Send with attachment (ViewModel scope — survives recomposition cancellation) ──
        fun sendWithAttachment(
            text: String,
            attachment: PendingAttachment?,
            context: android.content.Context,
            onAttachComplete: () -> Unit
        ) {
            val sid = _sessionId.value ?: return
            viewModelScope.launch {
                var attachUrl: String? = null
                var attachType: String? = null
                if (attachment != null) {
                    try {
                        val tempFile = java.io.File(context.cacheDir, attachment.fileName)
                        context.contentResolver.openInputStream(attachment.uri)?.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        attachUrl = repository.uploadFile(sid, tempFile, attachment.fileName, attachment.mimeType)
                        tempFile.delete()
                        attachType = attachment.attachType
                    } catch (e: Exception) {
                        _errorMessage.value = "Upload failed: ${e.message}"
                    }
                }
                if (text.isNotBlank() || attachUrl != null) {
                    sendMessage(text, attachUrl, attachType)
                }
                onAttachComplete()
            }
        }
    }
// ═══════════════════════════════════════════════════════════════
// Screen composable
// ═══════════════════════════════════════════════════════════════

data class PendingAttachment(
    val uri: android.net.Uri,
    val fileName: String,
    val mimeType: String,
    val attachType: String  // "image", "video", "audio", "file"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun ChatScreen(
    paddingValues: PaddingValues
) {
    val context = LocalContext.current
    val vm: ChatViewModel = hiltViewModel()

    val messages by vm.messages.collectAsState()
    val streamingContent by vm.streamingContent.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val connectionStatus by vm.connectionStatus.collectAsState()
    val isRecording by vm.isRecording.collectAsState()
    val recordingAmplitude by vm.recordingAmplitude.collectAsState()
    val toolCalls by vm.toolCalls.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()
    val sessionIdState by vm.sessionId.collectAsState()
    val showEmojiPicker by vm.showEmojiPicker.collectAsState()
    val currentModel by vm.currentModel.collectAsState()
    val availableModels by vm.availableModels.collectAsState()
    val modelsLoading by vm.modelsLoading.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var pendingAttachment by remember { mutableStateOf<PendingAttachment?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // ── Model picker state ──
    var showModelPicker by remember { mutableStateOf(false) }
    var modelSearchQuery by remember { mutableStateOf("") }
    var modelPickerGlobal by remember { mutableStateOf(false) }

    // ── File picker (stores selection, doesn't upload until send clicked) ──
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.showEmojiPicker.value = false
            scope.launch(Dispatchers.IO) {
                val cr = context.contentResolver
                val mimeType = cr.getType(uri) ?: "application/octet-stream"
                val attachType = when {
                    mimeType.startsWith("image/") -> "image"
                    mimeType.startsWith("video/") -> "video"
                    mimeType.startsWith("audio/") -> "audio"
                    else -> "file"
                }
                val displayName = android.provider.OpenableColumns.DISPLAY_NAME
                val fileName = cr.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(displayName)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                } ?: "${attachType}_${System.currentTimeMillis()}"
                pendingAttachment = PendingAttachment(
                    uri = uri,
                    fileName = fileName,
                    mimeType = mimeType,
                    attachType = attachType
                )
            }
        }
    }

    // Permission launcher for microphone voice dictation
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Permission granted — use Android SpeechRecognizer inline
            startVoiceDictation(
                context = context,
                onFinalText = { text ->
                    if (text.isNotBlank()) {
                        inputText = text
                        vm.sendMessage(text.trim())
                        inputText = ""
                    }
                },
                onError = { msg -> vm.setError("Voice: $msg") }
            )
        } else {
            vm.setError("Microphone permission denied")
        }
    }

    // Initialise session — read pending session once on creation
    LaunchedEffect(Unit) {
        val pending = com.hermes.mobile.ChatNav.pendingSessionId
        com.hermes.mobile.ChatNav.pendingSessionId = null // consume
        vm.initSession(pending)
    }

    // Auto-scroll to bottom — only when already near the bottom (don't yank users reading history)
    LaunchedEffect(messages.size, streamingContent) {
        if (messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val nearBottom = lastVisible >= messages.size - 3
            if (nearBottom || streamingContent.isNotBlank()) {
                listState.scrollToItem(messages.size - 1)
            }
        }
    }

    // Load models when session ID is available
    LaunchedEffect(sessionIdState) {
        if (sessionIdState != null) {
            vm.loadModels()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── Telegram-style top bar ──
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(4.dp))
                // App icon
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = HermesPrimary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Model chip (tappable — opens model picker)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            vm.loadModels()
                            modelSearchQuery = ""
                            showModelPicker = true
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Hermes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (currentModel.isNotBlank())
                                currentModel.substringAfterLast("/").take(20)
                            else "AI Assistant",
                            style = MaterialTheme.typography.bodySmall,
                            color = HermesPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = "Select model",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                // Search
                IconButton(onClick = { }) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // New chat
                IconButton(onClick = { vm.clearSession() }) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "New chat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        ConnectionStatusBar(connectionStatus = connectionStatus)

        errorMessage?.let { err ->
            Snackbar(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                action = { TextButton(onClick = { vm.dismissError() }) { Text("Dismiss") } },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) { Text(err) }
        }

        if (isStreaming && toolCalls.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                toolCalls.forEach { toolCall -> ToolCallCard(toolCall = toolCall) }
            }
        }

        // ── Telegram-style chat area (full width, no border) ──
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty() && !isStreaming) {
                EmptyChatState()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        top = 2.dp,
                        bottom = 2.dp
                    )
                ) {
                    items(items = messages, key = { it.id }) { message ->
                        val isStreamingThis = isStreaming && message.isStreaming
                        val displayContent = if (isStreamingThis) streamingContent else message.content
                        MessageBubble(
                            message = message,
                            displayContent = displayContent,
                            isStreaming = isStreamingThis,
                            baseUrl = vm.getBaseUrl()
                        )
                    }
                    if (isStreaming && streamingContent.isBlank()) {
                        item(key = "typing_indicator") { TypingIndicator() }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isRecording,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it }
        ) {
            VoiceRecordingBar(
                amplitude = recordingAmplitude,
                onStop = { vm.stopRecording() }
            )
        }

        InputBar(
            inputText = inputText,
            onInputChange = { inputText = it },
            onSend = {
                // Use ViewModel scope so cancellation doesn't lose messages
                vm.sendWithAttachment(inputText.trim(), pendingAttachment, context, onAttachComplete = {
                    pendingAttachment = null
                    inputText = ""
                })
            },
            onVoice = {
                // Request mic permission, then start inline voice dictation
                if (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED) {
                    startVoiceDictation(
                        context = context,
                        onFinalText = { text ->
                            if (text.isNotBlank()) {
                                inputText = text
                                vm.sendMessage(text.trim())
                                inputText = ""
                            }
                        },
                        onError = { msg -> vm.setError("Voice: $msg") }
                    )
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onEmoji = { emoji ->
                inputText += emoji
                vm.showEmojiPicker.value = false
            },
            onAttach = { filePickerLauncher.launch(arrayOf("*/*")) },
            pendingAttachment = pendingAttachment,
            onRemoveAttachment = { pendingAttachment = null },
            isRecording = isRecording,
            isStreaming = isStreaming,
            showEmojiPicker = showEmojiPicker,
            onToggleEmojiPicker = { vm.showEmojiPicker.value = !vm.showEmojiPicker.value },
            enabled = sessionIdState != null
        )

        // ── Model Picker Bottom Sheet ──
        if (showModelPicker) {
            ModalBottomSheet(
                onDismissRequest = { showModelPicker = false },
                modifier = Modifier.fillMaxHeight(0.85f)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    // Title + global toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Select Model",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "Global",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(
                            checked = modelPickerGlobal,
                            onCheckedChange = { modelPickerGlobal = it },
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Search bar
                    OutlinedTextField(
                        value = modelSearchQuery,
                        onValueChange = { modelSearchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search models…") },
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null)
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            focusedBorderColor = HermesPrimary.copy(alpha = 0.5f),
                            unfocusedBorderColor = Color.Transparent,
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Loading state
                    if (modelsLoading) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    } else {
                        // Filter models by search query
                        val filtered = remember(availableModels, modelSearchQuery) {
                            val list = if (modelSearchQuery.isBlank()) availableModels
                            else availableModels.filter { m ->
                                m.id.contains(modelSearchQuery, ignoreCase = true) ||
                                m.name.contains(modelSearchQuery, ignoreCase = true)
                            }
                            // Group by provider, preserve order
                            list.groupBy { m -> m.provider.ifBlank { "other" } }
                                .toSortedMap()
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            filtered.forEach { (provider, models) ->
                                // Provider section header
                                item(key = "provider_$provider") {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                        color = Color.Transparent
                                    ) {
                                        Text(
                                            text = provider.uppercase(),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = HermesPrimary,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                // Models in this provider group
                                items(models, key = { it.id }) { model ->
                                    val isCurrent = model.id == currentModel
                                    Surface(
                                        onClick = {
                                            vm.switchModel(model.id, global = modelPickerGlobal)
                                            showModelPicker = false
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isCurrent)
                                            HermesPrimary.copy(alpha = 0.1f)
                                        else Color.Transparent
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Icon
                                            if (model.isVision) {
                                                Icon(
                                                    Icons.Filled.Visibility,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            } else if (model.isFree) {
                                                Icon(
                                                    Icons.Filled.LockOpen,
                                                    contentDescription = "Free",
                                                    tint = SuccessGreen,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            } else {
                                                Box(modifier = Modifier.size(18.dp))
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = model.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            if (isCurrent) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(
                                                    Icons.Filled.CheckCircle,
                                                    contentDescription = "Current",
                                                    tint = HermesPrimary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Inline voice dictation — uses SpeechRecognizer directly
// ═══════════════════════════════════════════════════════════════

private fun startVoiceDictation(
    context: android.content.Context,
    onFinalText: (String) -> Unit,
    onError: (String) -> Unit
) {
    if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
        onError("Speech recognition not available on this device")
        return
    }
    try {
        val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(context)
        if (recognizer == null) {
            onError("Speech recognition service unavailable")
            return
        }
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                val msg = when (error) {
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech"
                    android.speech.SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    android.speech.SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    else -> "Voice error ($error)"
                }
                onError(msg)
                recognizer.destroy()
            }
            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) onFinalText(text)
                else onError("No speech detected")
                recognizer.destroy()
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        recognizer.startListening(intent)
    } catch (e: Exception) {
        onError("Voice error: ${e.message}")
    }
}

// ═══════════════════════════════════════════════════════════════
// Connection status bar
// ═══════════════════════════════════════════════════════════════

@Composable
fun ConnectionStatusBar(connectionStatus: ConnectionStatus) {
    val (text, color, icon) = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> Triple("Connected", SuccessGreen, Icons.Filled.CheckCircle)
        ConnectionStatus.CONNECTING -> Triple("Connecting…", WarningAmber, Icons.Filled.Sync)
        ConnectionStatus.DISCONNECTED -> Triple("Disconnected", ErrorRed, Icons.Filled.CloudOff)
        ConnectionStatus.ERROR -> Triple("Connection Error", ErrorRed, Icons.Filled.Error)
    }

    AnimatedVisibility(
        visible = true,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = color.copy(alpha = 0.1f),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = text,
                    color = color,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Empty state
// ═══════════════════════════════════════════════════════════════

@Composable
fun EmptyChatState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // ── Telegram-style bird illustration ──
        Canvas(
            modifier = Modifier.size(160.dp)
        ) {
            val cx = size.width / 2
            val cy = size.height / 2
            val birdColor = Color(0xFF6AB5E8)
            val beakTop = Color(0xFFFF8A80)
            val beakBottom = Color(0xFFFF5252)
            val wingColor = Color(0xFFFFAB40)
            val bodyColor = Color(0xFF89CFF0)
            val headColor = Color(0xFFB388FF)

            // Body (ellipse)
            drawOval(
                color = bodyColor,
                topLeft = androidx.compose.ui.geometry.Offset(cx - 35f, cy - 10f),
                size = androidx.compose.ui.geometry.Size(70f, 50f)
            )
            // Head (circle)
            drawCircle(
                color = headColor,
                radius = 28f,
                center = androidx.compose.ui.geometry.Offset(cx - 15f, cy - 35f)
            )
            // Eye (white)
            drawCircle(
                color = Color.White,
                radius = 10f,
                center = androidx.compose.ui.geometry.Offset(cx - 10f, cy - 40f)
            )
            // Pupil
            drawCircle(
                color = Color.Black,
                radius = 5f,
                center = androidx.compose.ui.geometry.Offset(cx - 8f, cy - 40f)
            )
            // Beak (top)
            drawPath(
                path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx + 5f, cy - 35f)
                    cubicTo(cx + 30f, cy - 30f, cx + 35f, cy - 20f, cx + 10f, cy - 25f)
                    close()
                },
                color = beakTop
            )
            // Beak (bottom)
            drawPath(
                path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx + 5f, cy - 25f)
                    cubicTo(cx + 30f, cy - 20f, cx + 32f, cy - 12f, cx + 8f, cy - 20f)
                    close()
                },
                color = beakBottom
            )
            // Wing
            drawOval(
                color = wingColor,
                topLeft = androidx.compose.ui.geometry.Offset(cx - 30f, cy - 5f),
                size = androidx.compose.ui.geometry.Size(45f, 30f)
            )
            // Tail
            drawPath(
                path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx + 30f, cy + 10f)
                    cubicTo(cx + 55f, cy + 15f, cx + 60f, cy - 5f, cx + 45f, cy + 0f)
                    close()
                },
                color = bodyColor
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "No messages here yet...",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Send a message or tap the greeting below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// Message bubble
// ═══════════════════════════════════════════════════════════════

@Composable
fun MessageBubble(
    message: Message,
    displayContent: String,
    isStreaming: Boolean,
    baseUrl: String = ""
) {
    val isUser = message.role == MessageRole.USER
    val isDark = MaterialTheme.colorScheme.background == DarkBg
    val bubbleColor = if (isUser) {
        if (isDark) UserBubbleDark else UserBubbleLight
    } else {
        if (isDark) OtherBubbleDark else OtherBubbleLight
    }
    val alignment = if (isUser) Arrangement.End else Arrangement.Start
    val bubbleShape = RoundedCornerShape(
        topStart = if (isUser) 16.dp else 4.dp,
        topEnd = if (isUser) 4.dp else 16.dp,
        bottomStart = 16.dp,
        bottomEnd = 16.dp
    )
    val textColor = if (isUser) {
        if (isDark) Color.White else Color(0xFF000000)
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    // Build absolute URL for images served from the bridge server
    val absoluteImageUrl = remember(message.attachmentUrl, baseUrl) {
        val rel = message.attachmentUrl ?: return@remember null
        if (rel.startsWith("http")) rel
        else baseUrl.trimEnd('/') + rel
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = alignment
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .animateContentSize(
                    animationSpec = tween(durationMillis = 200)
                )
        ) {
            Surface(
                shape = bubbleShape,
                color = bubbleColor,
                shadowElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    // ── Image attachment ──
                    if (absoluteImageUrl != null && message.attachmentType?.startsWith("image") == true) {
                        AsyncImage(
                            model = absoluteImageUrl,
                            contentDescription = message.attachmentName ?: "Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = if (displayContent.isNotBlank()) 8.dp else 0.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.FillWidth
                        )
                    }
                    // ── File attachment (non-image) ──
                    if (message.attachmentUrl != null && (message.attachmentType == null || !message.attachmentType!!.startsWith("image"))) {
                        FileAttachmentRow(
                            name = message.attachmentName ?: message.attachmentUrl ?: "File",
                            modifier = Modifier.padding(bottom = if (displayContent.isNotBlank()) 8.dp else 0.dp)
                        )
                    }
                    // ── Text content ──
                    if (displayContent.isNotBlank()) {
                        if (isStreaming) {
                            StreamingText(
                                text = displayContent,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            MarkdownText(
                                text = displayContent,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    if (isStreaming) {
                        Spacer(modifier = Modifier.height(4.dp))
                        StreamingIndicator(color = textColor.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

// ── File attachment row ──
@Composable
fun FileAttachmentRow(name: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.AttachFile,
            contentDescription = "File",
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// Streaming text
// ═══════════════════════════════════════════════════════════════

@Composable
fun StreamingText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "streaming")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "streamingAlpha"
    )
    Text(
        text = text + " ▊",
        style = style,
        color = color.copy(alpha = alpha),
        modifier = modifier
    )
}

// ═══════════════════════════════════════════════════════════════
// Typing indicator
// ═══════════════════════════════════════════════════════════════

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val delays = listOf(0, 200, 400)
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                delays.forEach { delayMs ->
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = delayMs),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dot$delayMs"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(HermesSecondary.copy(alpha = alpha))
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Tool call card — polished with expandable results
// ═══════════════════════════════════════════════════════════════

@Composable
fun ToolCallCard(toolCall: ToolCallInfo) {
    var expanded by remember { mutableStateOf(false) }
    val hasResult = !toolCall.result.isNullOrBlank()
    val hasArgs = toolCall.arguments.isNotBlank()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(enabled = hasResult) { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (toolCall.status) {
                ToolCallStatus.RUNNING -> WarningAmber.copy(alpha = 0.12f)
                ToolCallStatus.COMPLETED -> SuccessGreen.copy(alpha = 0.08f)
                ToolCallStatus.FAILED -> ErrorRed.copy(alpha = 0.08f)
                ToolCallStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Header row: icon + name + status
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status icon
                when (toolCall.status) {
                    ToolCallStatus.RUNNING -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = WarningAmber
                    )
                    ToolCallStatus.COMPLETED -> Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    ToolCallStatus.FAILED -> Icon(
                        Icons.Filled.Error,
                        contentDescription = null,
                        tint = ErrorRed,
                        modifier = Modifier.size(16.dp)
                    )
                    ToolCallStatus.PENDING -> Icon(
                        Icons.Filled.HourglassEmpty,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                // Tool icon
                Icon(
                    imageVector = when (toolCall.name) {
                        "terminal" -> Icons.Filled.Terminal
                        "web_search", "web_extract" -> Icons.Filled.Language
                        "read_file" -> Icons.Filled.Description
                        "write_file" -> Icons.Filled.Edit
                        "search_files" -> Icons.Filled.Search
                        "patch" -> Icons.Filled.Build
                        "execute_code" -> Icons.Filled.Code
                        else -> Icons.Filled.Build
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = toolCall.name.replace("_", " "),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                // Status label
                Text(
                    text = when (toolCall.status) {
                        ToolCallStatus.RUNNING -> "Running..."
                        ToolCallStatus.COMPLETED -> "Done"
                        ToolCallStatus.FAILED -> "Failed"
                        ToolCallStatus.PENDING -> "Pending"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (toolCall.status) {
                        ToolCallStatus.RUNNING -> WarningAmber
                        ToolCallStatus.COMPLETED -> SuccessGreen
                        ToolCallStatus.FAILED -> ErrorRed
                        ToolCallStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            // Arguments (truncated preview)
            if (hasArgs && !expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = toolCall.arguments,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Result (expandable)
            if (hasResult) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (expanded) toolCall.result!!
                    else toolCall.result!!.take(80) + if (toolCall.result!!.length > 80) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 20 else 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (toolCall.result!!.length > 80) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (expanded) "▲ Show less" else "▼ Show more",
                        style = MaterialTheme.typography.labelSmall,
                        color = HermesPrimary,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Voice recording bar
// ═══════════════════════════════════════════════════════════════

@Composable
fun VoiceRecordingBar(
    amplitude: Float,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = ErrorRed,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Recording…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            TextButton(onClick = onStop) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = "Stop",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Stop")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Attachment preview (shown above text field before send)
// ═══════════════════════════════════════════════════════════════

@Composable
fun AttachmentPreview(
    attachment: PendingAttachment,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail for images, icon for files
            if (attachment.attachType == "image") {
                AsyncImage(
                    model = attachment.uri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(HermesPrimary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = HermesPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = attachment.mimeType,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Slash commands — matched from the server's /help output
// ═══════════════════════════════════════════════════════════════

data class SlashCommand(
    val command: String,
    val description: String
)

private val SLASH_COMMANDS = listOf(
    SlashCommand("/help", "Show available commands and tips"),
    SlashCommand("/reset", "Start a fresh conversation (clears history)"),
    SlashCommand("/new", "Same as /reset"),
    SlashCommand("/retry", "Regenerate the last response"),
    SlashCommand("/model", "Show the current AI model"),
    SlashCommand("/clear", "Clear the current session"),
    SlashCommand("/skills", "List available Hermes skills"),
    SlashCommand("/version", "Show version info"),
    SlashCommand("/info", "Show session info"),
)

@Composable
fun SlashCommandList(
    query: String,
    onCommandSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Only show when text starts with "/"
    if (!query.startsWith("/") || query.length > 30) {
        onDismiss()
        return
    }

    val filter = query.substring(1).lowercase()
    val matched = SLASH_COMMANDS.filter {
        it.command.removePrefix("/").contains(filter)
    }

    if (matched.isEmpty()) {
        onDismiss()
        return
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    ) {
        LazyColumn(
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            itemsIndexed(matched) { _, cmd ->
                Surface(
                    onClick = {
                        onCommandSelected(cmd.command + " ")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = cmd.command,
                            fontWeight = FontWeight.SemiBold,
                            color = HermesPrimary,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Text(
                            text = cmd.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Input bar
// ═══════════════════════════════════════════════════════════════

@Composable
fun InputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    onEmoji: (String) -> Unit,
    onAttach: () -> Unit,
    pendingAttachment: PendingAttachment?,
    onRemoveAttachment: () -> Unit,
    isRecording: Boolean,
    isStreaming: Boolean,
    showEmojiPicker: Boolean,
    onToggleEmojiPicker: () -> Unit,
    enabled: Boolean
) {
    // ── Slash command state ──
    val showSlashCommands = inputText.startsWith("/") && inputText.length <= 30

    val onCommandSelected: (String) -> Unit = { cmd ->
        onInputChange(cmd)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),  // Push bar above keyboard when it opens
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 2.dp
    ) {
        Column {
            // Slash command list (above the input, like Telegram)
            if (showSlashCommands) {
                SlashCommandList(
                    query = inputText,
                    onCommandSelected = onCommandSelected,
                    onDismiss = {}
                )
            }

            // Attachment preview (like Telegram: thumbnail + name above text field)
            if (pendingAttachment != null) {
                AttachmentPreview(
                    attachment = pendingAttachment,
                    onRemove = onRemoveAttachment
                )
            }

            // Emoji picker popup (above the bar, like Telegram)
            if (showEmojiPicker) {
                EmojiPickerGrid(onEmojiSelected = onEmoji)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                    // ── 1. Emoji button (leftmost, like Telegram) ──
                    IconButton(
                        onClick = onToggleEmojiPicker,
                        modifier = Modifier.size(40.dp),
                        enabled = enabled
                    ) {
                        Text(
                            text = if (showEmojiPicker) "⌨️" else "😀",
                            fontSize = 20.sp
                        )
                    }

                    // ── 2. Text field (no keyboard send — only explicit send button) ──
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 36.dp, max = 120.dp),
                        placeholder = {
                            Text(
                                text = "Message",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedBorderColor = HermesPrimary.copy(alpha = 0.5f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            cursorColor = HermesPrimary
                        ),
                        shape = RoundedCornerShape(24.dp),
                        singleLine = false,
                        maxLines = 4,
                        enabled = enabled
                    )

                    // ── 3. Attach button ──
                    IconButton(
                        onClick = onAttach,
                        modifier = Modifier.size(40.dp),
                        enabled = enabled
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AttachFile,
                            contentDescription = "Attach",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // ── 4. Mic button (always visible, blue circle — like Telegram) ──
                    FilledIconButton(
                        onClick = onVoice,
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = HermesPrimary,
                            contentColor = Color.White
                        ),
                        enabled = enabled && !isRecording
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = "Voice input",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // ── 5. Send button (appears when text or attachment present — like Telegram) ──
                    val hasContent = inputText.isNotBlank() || pendingAttachment != null
                    if (hasContent) {
                        Spacer(modifier = Modifier.width(4.dp))
                        FilledIconButton(
                            onClick = onSend,
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = HermesPrimary,
                                contentColor = Color.White
                            ),
                            enabled = enabled
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Markdown text rendering (simple)

@Composable
fun MarkdownText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    val annotated = remember(text, style, color) {
        parseMarkdown(text, style, color)
    }
    Text(
        text = annotated,
        style = style,
        color = color,
        modifier = modifier
    )
}

/**
 * Parse basic markdown into AnnotatedString.
 * Supports: **bold**, *italic*
 */
private fun parseMarkdown(
    text: String,
    style: TextStyle,
    baseColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            // Check for **bold** (prefer bold over italic)
            if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end != -1 && end > i + 2) {
                    val start = length
                    append(text.substring(i + 2, end))
                    addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold),
                        start, length
                    )
                    i = end + 2
                    continue
                }
            }
            // Check for *italic*
            if (text[i] == '*') {
                val end = text.indexOf('*', i + 1)
                if (end != -1 && end > i + 1 &&
                    !(i + 1 < text.length && text[i + 1] == '*')
                ) {
                    val start = length
                    append(text.substring(i + 1, end))
                    addStyle(
                        SpanStyle(fontStyle = FontStyle.Italic),
                        start, length
                    )
                    i = end + 1
                    continue
                }
            }
            append(text[i])
            i++
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Streaming indicator (blinking cursor)
// ═══════════════════════════════════════════════════════════════

@Composable
fun StreamingIndicator(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "streaming_indicator")
    val visible by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blink"
    )
    Box(
        modifier = Modifier
            .width(6.dp)
            .height(16.dp)
            .background(color.copy(alpha = visible))
    )
}

// ═══════════════════════════════════════════════════════════════
// Emoji picker grid
// ═══════════════════════════════════════════════════════════════

private val EMOJIS = listOf(
    "😀", "😂", "❤️", "🔥", "👍", "🎉", "✨", "💪",
    "😊", "🤣", "😍", "😭", "🥺", "🙏", "💀", "🤝",
    "👋", "🙌", "🚀", "⭐", "💡", "✅", "❌", "📌",
    "🎯", "💯", "🤔", "😎", "👀", "💜", "🎶", "🏆",
    "📱", "💻", "🔗", "📎", "📄", "📂", "🗂️", "📁"
)

@Composable
fun EmojiPickerGrid(
    onEmojiSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(EMOJIS.size) { index ->
                Text(
                    text = EMOJIS[index],
                    fontSize = 24.sp,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { onEmojiSelected(EMOJIS[index]) }
                        .then(Modifier.padding(2.dp)),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
