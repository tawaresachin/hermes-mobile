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
     * Otherwise, create a new empty session.
     * Cancel any previous init to prevent the "StandaloneCoroutine was cancelled" race.
     */
    fun initSession(sessionId: String?) {
        initJob?.cancel()
        // Don't skip even if same ID — a fresh observeMessages ensures we show latest data
        initJob = viewModelScope.launch {
            if (sessionId != null) {
                resumeSession(sessionId)
            } else {
                createNewSession()
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
    fun sendMessage(query: String) {
        val sid = _sessionId.value ?: return
        if (query.isBlank()) return

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

            try {
                repository.sendMessage(
                    sessionId = sid,
                    query = query,
                    onChunk = { chunk ->
                        val newContent = _streamingContent.value + chunk
                        _streamingContent.value = newContent
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
                viewModelScope.launch {
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

    // ── Dismiss error ──
    fun dismissError() {
        _errorMessage.value = null
    }

    fun setError(msg: String) {
        _errorMessage.value = msg
    }

    // ── Upload and send image ──
    suspend fun uploadAndSend(context: android.content.Context, uri: android.net.Uri, text: String): String? {
        val sid = _sessionId.value ?: return null
        showEmojiPicker.value = false
        return try {
            // Copy content:// URI to temp file for upload
            val fileName = "IMG_${System.currentTimeMillis()}.jpg"
            val tempFile = java.io.File(context.cacheDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // Upload to server
            val result = repository.uploadFile(sid, tempFile, fileName, "image/jpeg")
            tempFile.delete()
            if (result != null) {
                // Send as a message with attachment
                sendMessageWithAttachment(sid, text, result, "image")
            }
            result
        } catch (e: Exception) {
            _errorMessage.value = "Upload failed: ${e.message}"
            null
        }
    }

    private fun sendMessageWithAttachment(sessionId: String, text: String, url: String, type: String) {
        viewModelScope.launch {
            repository.sendMessageWithAttachment(sessionId, text, url, type)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Screen composable
// ═══════════════════════════════════════════════════════════════

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

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // ── Image picker ──
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                vm.showEmojiPicker.value = false
                val url = vm.uploadAndSend(context, uri, inputText.trim())
                if (url != null) {
                    inputText = ""
                }
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

    // Auto-scroll to bottom
    LaunchedEffect(messages.size, streamingContent) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
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

        Box(modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp)
            )
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            )
        ) {
            if (messages.isEmpty() && !isStreaming) {
                EmptyChatState()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    items(items = messages, key = { it.id }) { message ->
                        val isStreamingThis = isStreaming && message.isStreaming
                        val displayContent = if (isStreamingThis) streamingContent else message.content
                        MessageBubble(message = message, displayContent = displayContent, isStreaming = isStreamingThis)
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
                if (inputText.isNotBlank()) {
                    vm.sendMessage(inputText.trim())
                    inputText = ""
                }
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
            onEmoji = { emoji -> inputText += emoji },
            onAttach = { imagePickerLauncher.launch("image/*") },
            isRecording = isRecording,
            isStreaming = isStreaming,
            showEmojiPicker = vm.showEmojiPicker.value,
            onToggleEmojiPicker = { vm.showEmojiPicker.value = !vm.showEmojiPicker.value },
            enabled = sessionIdState != null
        )
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
        Icon(
            imageVector = Icons.Filled.Chat,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Start a conversation",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Type a message below or use voice input",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
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
    isStreaming: Boolean
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
                    if (message.attachmentUrl != null && message.attachmentType == "image") {
                        AsyncImage(
                            model = message.attachmentUrl,
                            contentDescription = message.attachmentName ?: "Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = if (displayContent.isNotBlank()) 8.dp else 0.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.FillWidth
                        )
                    }
                    // ── File attachment (non-image) ──
                    if (message.attachmentUrl != null && message.attachmentType != null && message.attachmentType != "image") {
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
    isRecording: Boolean,
    isStreaming: Boolean,
    showEmojiPicker: Boolean,
    onToggleEmojiPicker: () -> Unit,
    enabled: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column {
            // Emoji picker popup
            if (showEmojiPicker) {
                EmojiPickerGrid(onEmojiSelected = onEmoji)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Voice button
                FilledIconButton(
                    onClick = onVoice,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isRecording) ErrorRed.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isRecording) ErrorRed
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    enabled = enabled
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (isRecording) "Stop recording" else "Voice input",
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Emoji button
                FilledIconButton(
                    onClick = onToggleEmojiPicker,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (showEmojiPicker) HermesPrimary.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (showEmojiPicker) HermesPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    enabled = enabled
                ) {
                    Text(
                        text = "😀",
                        fontSize = 18.sp
                    )
                }

                // Attach button
                FilledIconButton(
                    onClick = onAttach,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    enabled = enabled
                ) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = "Attach file",
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Text field
                OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = if (isStreaming) "Waiting for response…" else "Type a message…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = HermesPrimary,
                    unfocusedBorderColor = HermesPrimary.copy(alpha = 0.5f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    cursorColor = HermesPrimary
                ),
                maxLines = 4,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (inputText.isNotBlank()) {
                            onSend()
                        }
                    }
                )
            )

            // Send button
            FilledIconButton(
                onClick = onSend,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (inputText.isBlank() || !enabled)
                        MaterialTheme.colorScheme.surfaceVariant
                    else HermesPrimary,
                    contentColor = if (inputText.isBlank() || !enabled)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else Color.White
                ),
                enabled = enabled && inputText.isNotBlank() && !isStreaming
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    modifier = Modifier.size(20.dp)
                )
            }   // close FilledIconButton
        }   // close Row
        }   // close Column
    }   // close Surface
}   // close InputBar

// ═══════════════════════════════════════════════════════════════
// Markdown text rendering (simple)
// ═══════════════════════════════════════════════════════════════

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
