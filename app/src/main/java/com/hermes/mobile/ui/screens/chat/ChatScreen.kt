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
import androidx.compose.foundation.lazy.rememberLazyListState
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
    private val _voiceText = MutableStateFlow("")
    val voiceText: StateFlow<String> = _voiceText.asStateFlow()

    private val _pendingVoiceText = MutableStateFlow("")
    val pendingVoiceText: StateFlow<String> = _pendingVoiceText.asStateFlow()

    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

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
        _isStreaming.value = true
        _streamingContent.value = ""
        _toolCalls.value = emptyList()
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                repository.sendMessage(
                    sessionId = sid,
                    query = query,
                    onChunk = { chunk ->
                        val newContent = _streamingContent.value + chunk
                        _streamingContent.value = newContent
                        // Detect tool calls in the chunk stream
                        detectToolCalls(newContent)
                    }
                )
                // streaming finished – mark as done
                _isStreaming.value = false
                _streamingContent.value = ""
                _toolCalls.value = emptyList()
            } catch (e: Exception) {
                _isStreaming.value = false
                _errorMessage.value = "Send failed: ${e.message}"
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

    fun clearPendingVoiceText() {
        _pendingVoiceText.value = ""
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
    val voiceText by vm.voiceText.collectAsState()
    val pendingVoiceText by vm.pendingVoiceText.collectAsState()
    val toolCalls by vm.toolCalls.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()
    val sessionIdState by vm.sessionId.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

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
            isRecording = isRecording,
            isStreaming = isStreaming,
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
                    if (isStreaming) {
                        Spacer(modifier = Modifier.height(4.dp))
                        StreamingIndicator(color = textColor.copy(alpha = 0.6f))
                    }
                }
            }
        }
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
// Tool call card
// ═══════════════════════════════════════════════════════════════

@Composable
fun ToolCallCard(toolCall: ToolCallInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = toolCall.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = toolCall.status.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (toolCall.status) {
                        ToolCallStatus.RUNNING -> WarningAmber
                        ToolCallStatus.COMPLETED -> SuccessGreen
                        ToolCallStatus.FAILED -> ErrorRed
                        ToolCallStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (toolCall.arguments.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = toolCall.arguments,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
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
    isRecording: Boolean,
    isStreaming: Boolean,
    enabled: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
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
                enabled = enabled && !isStreaming,
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
            }
        }
    }
}

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
        buildAnnotatedString {
            append(text)
        }
    }
    Text(
        text = annotated,
        style = style,
        color = color,
        modifier = modifier
    )
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
