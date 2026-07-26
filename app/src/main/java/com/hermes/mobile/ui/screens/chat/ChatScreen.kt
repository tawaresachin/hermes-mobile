package com.hermes.mobile.ui.screens.chat

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.data.model.ConnectionStatus
import com.hermes.mobile.data.model.Message
import com.hermes.mobile.data.model.MessageRole
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.ui.theme.*
import com.hermes.mobile.voice.VoiceRecorder
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
// ViewModel
// ═══════════════════════════════════════════════════════════════

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: HermesRepository,
    private val voiceRecorder: VoiceRecorder,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // ── Session state ──
    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    // ── Messages from DB (persisted) ──
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    // ── Streaming state ──
    private val _streamingMessageId = MutableStateFlow<Long?>(null)
    val streamingMessageId: StateFlow<Long?> = _streamingMessageId.asStateFlow()

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
    private var amplitudeJob: Job? = null

    init {
        checkConnection()
    }

    fun initSession(sessionId: String?) {
        if (_sessionId.value != null && _sessionId.value == sessionId) return
        if (sessionId != null) {
            resumeSession(sessionId)
        } else {
            createNewSession()
        }
    }

    fun createNewSession() {
        viewModelScope.launch {
            try {
                val session = repository.createSession()
                _sessionId.value = session.id
                observeMessages(session.id)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to create session: ${e.message}"
            }
        }
    }

    fun resumeSession(sessionId: String) {
        viewModelScope.launch {
            try {
                _sessionId.value = sessionId
                observeMessages(sessionId)
                repository.resumeSession(sessionId) // warm cache
            } catch (e: Exception) {
                _errorMessage.value = "Failed to resume session: ${e.message}"
            }
        }
    }

    private fun observeMessages(sessionId: String) {
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            repository.getMessages(sessionId).collect { msgList ->
                _messages.value = msgList
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
                _streamingMessageId.value = null
                _streamingContent.value = ""
                _toolCalls.value = emptyList()
            } catch (e: Exception) {
                _isStreaming.value = false
                _streamingMessageId.value = null
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

    // ── Voice recording ──
    fun startRecording() {
        if (!voiceRecorder.hasPermission()) {
            _errorMessage.value = "Microphone permission required"
            return
        }
        val context = voiceRecorder::class.java.name // not ideal, but we get cache dir from context
        // In a real app we'd pass the context; here we simulate via the recorder
        val file = voiceRecorder.startRecording(
            java.io.File(
                android.os.Environment.getDataDirectory(),
                "hermes_voice_cache"
            )
        )
        if (file != null) {
            _isRecording.value = true
            _recordingAmplitude.value = 0f
            startAmplitudeSimulation()
        } else {
            _errorMessage.value = "Failed to start recording"
        }
    }

    fun stopRecording(): String? {
        amplitudeJob?.cancel()
        amplitudeJob = null
        _isRecording.value = false
        val file = voiceRecorder.stopRecording()
        _recordingAmplitude.value = 0f
        // In production we'd transcribe the file; for now return a placeholder
        return file?.absolutePath
    }

    private fun startAmplitudeSimulation() {
        amplitudeJob?.cancel()
        amplitudeJob = viewModelScope.launch {
            while (isActive) {
                _recordingAmplitude.value = (0.1f..1f).random()
                delay(100L)
            }
        }
    }

    // ── Connection ──
    fun checkConnection() {
        viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.CONNECTING
            val config = com.hermes.mobile.data.model.ServerConfig()
            val status = repository.checkConnection(config)
            _connectionStatus.value = status
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
            _streamingMessageId.value = null
        }
    }

    // ── Dismiss error ──
    fun dismissError() {
        _errorMessage.value = null
    }
}

// ═══════════════════════════════════════════════════════════════
// Screen composable
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun ChatScreen(
    paddingValues: PaddingValues,
    sessionId: String? = null,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val streamingContent by viewModel.streamingContent.collectAsState()
    val streamingMessageId by viewModel.streamingMessageId.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingAmplitude by viewModel.recordingAmplitude.collectAsState()
    val toolCalls by viewModel.toolCalls.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val sessionIdState by viewModel.sessionId.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Initialise session on first composition
    LaunchedEffect(sessionId) {
        viewModel.initSession(sessionId)
    }

    // Auto-scroll to bottom on new message or streaming
    LaunchedEffect(messages.size, streamingContent) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── Connection status bar ──
        ConnectionStatusBar(connectionStatus = connectionStatus)

        // ── Error snackbar ──
        errorMessage?.let { err ->
            Snackbar(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                action = {
                    TextButton(onClick = { viewModel.dismissError() }) {
                        Text("Dismiss")
                    }
                },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) {
                Text(err)
            }
        }

        // ── Tool calls during streaming ──
        if (isStreaming && toolCalls.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                toolCalls.forEach { toolCall ->
                    ToolCallCard(toolCall = toolCall)
                }
            }
        }

        // ── Message list ──
        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty() && !isStreaming) {
                // Empty state
                EmptyChatState()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    items(
                        items = messages,
                        key = { it.id }
                    ) { message ->
                        val isStreamingThis = isStreaming && message.id == streamingMessageId
                        val displayContent = if (isStreamingThis) {
                            streamingContent
                        } else {
                            message.content
                        }
                        MessageBubble(
                            message = message,
                            displayContent = displayContent,
                            isStreaming = isStreamingThis
                        )
                    }

                    // Typing indicator at the bottom
                    if (isStreaming && streamingContent.isBlank()) {
                        item(key = "typing_indicator") {
                            TypingIndicator()
                        }
                    }
                }
            }
        }

        // ── Voice recording overlay ──
        AnimatedVisibility(
            visible = isRecording,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it }
        ) {
            VoiceRecordingBar(
                amplitude = recordingAmplitude,
                onStop = {
                    viewModel.stopRecording()
                    // In production we'd transcribe and populate inputText
                }
            )
        }

        // ── Input bar ──
        InputBar(
            inputText = inputText,
            onInputChange = { inputText = it },
            onSend = {
                if (inputText.isNotBlank()) {
                    viewModel.sendMessage(inputText.trim())
                    inputText = ""
                }
            },
            onVoice = {
                if (isRecording) {
                    viewModel.stopRecording()
                } else {
                    viewModel.startRecording()
                }
            },
            isRecording = isRecording,
            isStreaming = isStreaming,
            enabled = sessionIdState != null
        )
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
        visible = connectionStatus != ConnectionStatus.CONNECTED,
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
    val bubbleColor = if (isUser) UserBubble else AssistantBubbleDark
    val alignment = if (isUser) Arrangement.End else Arrangement.Start
    val bubbleShape = RoundedCornerShape(
        topStart = if (isUser) 16.dp else 4.dp,
        topEnd = if (isUser) 4.dp else 16.dp,
        bottomStart = 16.dp,
        bottomEnd = 16.dp
    )
    val textColor = if (isUser) Color.White else DarkOnSurface

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = alignment
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .animateContentSize(
                    animationSpec = tween(durationMillis = 200)
                )
        ) {
            Surface(
                shape = bubbleShape,
                color = bubbleColor,
                shadowElevation = if (isUser) 4.dp else 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    if (displayContent.isBlank() && isStreaming) {
                        // Placeholder for initial streaming
                        TypingIndicator()
                    } else {
                        MarkdownText(
                            text = displayContent,
                            textColor = textColor,
                            isUser = isUser
                        )
                    }
                }
            }

            // Timestamp
            Text(
                text = formatTimestamp(message.timestamp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = if (isUser) 0.dp else 4.dp, end = if (isUser) 4.dp else 0.dp, top = 2.dp),
                textAlign = if (isUser) TextAlign.End else TextAlign.Start
            )
        }
    }
}

private fun formatTimestamp(epochMs: Long): String {
    val sdf = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(epochMs))
}

// ═══════════════════════════════════════════════════════════════
// Markdown renderer
// ═══════════════════════════════════════════════════════════════

@Composable
fun MarkdownText(
    text: String,
    textColor: Color,
    isUser: Boolean,
    modifier: Modifier = Modifier
) {
    // Split into blocks: code fences vs regular markdown
    val blocks = remember(text) { parseMarkdownBlocks(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.CodeBlock -> {
                    CodeBlock(
                        code = block.code,
                        language = block.language
                    )
                }
                is MarkdownBlock.TextBlock -> {
                    InlineMarkdownText(
                        text = block.text,
                        color = textColor,
                        isUser = isUser
                    )
                }
            }
        }
    }
}

private sealed class MarkdownBlock {
    data class CodeBlock(val code: String, val language: String = "") : MarkdownBlock()
    data class TextBlock(val text: String) : MarkdownBlock()
}

private fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.split("\n")
    val codeFenceStart = Regex("^```(\\w*)\\s*$")
    var inCodeBlock = false
    val codeLines = mutableListOf<String>()
    var codeLang = ""
    val textLines = mutableListOf<String>()

    fun flushText() {
        if (textLines.isNotEmpty()) {
            blocks.add(MarkdownBlock.TextBlock(textLines.joinToString("\n")))
            textLines.clear()
        }
    }

    for (line in lines) {
        if (!inCodeBlock) {
            val match = codeFenceStart.find(line)
            if (match != null) {
                flushText()
                inCodeBlock = true
                codeLang = match.groupValues[1]
                codeLines.clear()
            } else {
                textLines.add(line)
            }
        } else {
            if (line.trimStart().startsWith("```")) {
                inCodeBlock = false
                blocks.add(MarkdownBlock.CodeBlock(codeLines.joinToString("\n"), codeLang))
                codeLines.clear()
                codeLang = ""
            } else {
                codeLines.add(line)
            }
        }
    }

    if (inCodeBlock) {
        // Unclosed code fence – treat remaining as code
        if (codeLines.isNotEmpty()) {
            blocks.add(MarkdownBlock.CodeBlock(codeLines.joinToString("\n"), codeLang))
        }
    } else {
        flushText()
    }

    return blocks
}

// ═══════════════════════════════════════════════════════════════
// Inline markdown text (bold, italic, code, lists)
// ═══════════════════════════════════════════════════════════════

@Composable
fun InlineMarkdownText(
    text: String,
    color: Color,
    isUser: Boolean
) {
    // Process lines separately for list detection
    val lines = text.split("\n")
    Column {
        lines.forEachIndexed { index, line ->
            val isListItem = line.trimStart().startsWith("- ") ||
                    line.trimStart().startsWith("* ") ||
                    line.trimStart().matches(Regex("^\\d+\\.\\s.*"))
            val content = if (isListItem) {
                line.trimStart().substringAfter(" ").substringAfter(".")
            } else {
                line
            }

            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isListItem) {
                    Text(
                        text = if (line.trimStart().matches(Regex("^\\d+\\.\\s.*"))) {
                            // numbered list handled via prefix
                            ""
                        } else {
                            "\u2022  "
                        },
                        color = color,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = buildInlineAnnotatedString(content, color),
                    color = color,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = 24.sp
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
            if (index < lines.size - 1) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun buildInlineAnnotatedString(text: String, baseColor: Color) = buildAnnotatedString {
    // Simple inline parser: **bold**, *italic*, `code`
    val regex = Regex("""(\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`)""")
    var lastEnd = 0
    for (match in regex.findAll(text)) {
        // Plain text before this match
        if (match.range.first > lastEnd) {
            val plain = text.substring(lastEnd, match.range.first)
            append(plain)
        }

        when {
            match.groupValues[1].startsWith("**") -> {
                // Bold: **text**
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(match.groupValues[2])
                }
            }
            match.groupValues[1].startsWith("*") && match.groupValues[1].length > 1 -> {
                // Italic: *text*
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(match.groupValues[3])
                }
            }
            match.groupValues[1].startsWith("`") -> {
                // Inline code: `code`
                withStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = if (baseColor == Color.White)
                        Color.White.copy(alpha = 0.15f)
                    else
                        DarkSurfaceVariant,
                    color = if (baseColor == Color.White) Color.White else HermesPrimaryLight
                )) {
                    append(match.groupValues[4])
                }
            }
        }
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) {
        append(text.substring(lastEnd))
    }
}

// ═══════════════════════════════════════════════════════════════
// Code block with syntax highlighting
// ═══════════════════════════════════════════════════════════════

@Composable
fun CodeBlock(
    code: String,
    language: String
) {
    var isExpanded by remember { mutableStateOf(false) }
    val displayCode = if (isExpanded) code else code.lines().take(12).joinToString("\n")
    val isTruncated = code.lines().size > 12

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0D0D1A),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Header bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A2E))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Code,
                        contentDescription = null,
                        tint = HermesPrimaryLight,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = language.ifBlank { "code" },
                        color = HermesPrimaryLight,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Copy button
                    IconButton(
                        onClick = { /* copy to clipboard */ },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy",
                            tint = DarkOnSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    if (isTruncated) {
                        IconButton(
                            onClick = { isExpanded = !isExpanded },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Filled.UnfoldLess
                                else Icons.Filled.UnfoldMore,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                tint = DarkOnSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // Code content with syntax highlighting
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                SyntaxHighlightedCode(
                    code = displayCode,
                    language = language
                )
            }
        }
    }
}

@Composable
fun SyntaxHighlightedCode(
    code: String,
    language: String
) {
    val keywordColor = Color(0xFF82AAFF)
    val stringColor = Color(0xFFC3E88D)
    val commentColor = Color(0xFF546E7A)
    val numberColor = Color(0xFFF78C6C)
    val functionColor = Color(0xFFC792EA)
    val defaultColor = Color(0xFFD6DEEB)
    val punctuationColor = Color(0xFF89DDFF)

    Text(
        text = buildAnnotatedString {
            applySyntaxHighlighting(
                code = code,
                language = language,
                keywordColor = keywordColor,
                stringColor = stringColor,
                commentColor = commentColor,
                numberColor = numberColor,
                functionColor = functionColor,
                defaultColor = defaultColor,
                punctuationColor = punctuationColor
            )
        },
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp
    )
}

private fun AnnotatedString.Builder.applySyntaxHighlighting(
    code: String,
    language: String,
    keywordColor: Color,
    stringColor: Color,
    commentColor: Color,
    numberColor: Color,
    functionColor: Color,
    defaultColor: Color,
    punctuationColor: Color
) {
    // Language-specific keywords
    val keywords = when (language.lowercase()) {
        "kotlin", "kt" -> setOf(
            "fun", "val", "var", "class", "object", "interface", "enum", "data",
            "sealed", "abstract", "open", "override", "private", "public", "protected",
            "internal", "import", "package", "if", "else", "when", "for", "while",
            "do", "return", "suspend", "inline", "tailrec", "operator", "infix",
            "companion", "init", "constructor", "this", "super", "null", "true",
            "false", "is", "in", "as", "try", "catch", "finally", "throw",
            "let", "also", "apply", "run", "with", "by", "lazy", "lateinit"
        )
        "python", "py" -> setOf(
            "def", "class", "if", "elif", "else", "for", "while", "return",
            "import", "from", "as", "try", "except", "finally", "raise",
            "with", "yield", "lambda", "pass", "break", "continue", "and",
            "or", "not", "in", "is", "None", "True", "False", "self", "async",
            "await", "global", "nonlocal", "print", "len", "range", "type",
            "super", "del", "assert"
        )
        "javascript", "js", "typescript", "ts" -> setOf(
            "function", "const", "let", "var", "class", "if", "else", "for",
            "while", "do", "return", "import", "export", "from", "as",
            "async", "await", "try", "catch", "finally", "throw", "new",
            "this", "super", "null", "undefined", "true", "false", "typeof",
            "instanceof", "switch", "case", "default", "break", "continue",
            "in", "of", "yield", "delete", "void"
        )
        "json" -> emptySet()
        "xml", "html" -> setOf(
            "!DOCTYPE", "html", "head", "body", "div", "span", "p", "a",
            "img", "ul", "ol", "li", "table", "tr", "td", "th", "form",
            "input", "button", "style", "script", "meta", "link", "title",
            "h1", "h2", "h3", "h4", "h5", "h6", "section", "article",
            "header", "footer", "nav", "main", "aside"
        )
        "shell", "bash", "sh" -> setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done",
            "case", "esac", "function", "return", "exit", "export", "local",
            "source", "echo", "cd", "ls", "rm", "mv", "cp", "mkdir", "touch",
            "chmod", "chown", "grep", "sed", "awk", "cat", "head", "tail",
            "find", "sort", "uniq", "wc", "curl", "wget", "pip", "npm",
            "yarn", "docker", "git", "sudo", "apt", "yum", "brew"
        )
        "java" -> setOf(
            "public", "private", "protected", "class", "interface", "enum",
            "abstract", "final", "static", "void", "int", "long", "double",
            "float", "boolean", "char", "String", "new", "if", "else",
            "for", "while", "do", "switch", "case", "break", "continue",
            "return", "throw", "try", "catch", "finally", "import", "package",
            "extends", "implements", "super", "this", "null", "true", "false",
            "synchronized", "volatile", "transient", "instanceof"
        )
        "swift" -> setOf(
            "func", "var", "let", "class", "struct", "enum", "protocol",
            "extension", "if", "else", "for", "while", "repeat", "return",
            "import", "guard", "defer", "throw", "throws", "rethrows",
            "async", "await", "actor", "nonisolated", "mutating",
            "override", "open", "public", "internal", "fileprivate",
            "private", "static", "class", "self", "super", "nil",
            "true", "false", "in", "as", "is", "try", "catch"
        )
        else -> setOf(
            "if", "else", "for", "while", "return", "import", "from",
            "class", "def", "fun", "var", "val", "const", "let", "new",
            "this", "super", "null", "true", "false", "try", "catch",
            "throw", "async", "await", "enum", "interface", "type",
            "extends", "implements", "abstract", "static", "void"
        )
    }

    val lines = code.split("\n")
    for ((lineIdx, line) in lines.withIndex()) {
        if (lineIdx > 0) append("\n")

        val tokens = tokenizeLine(line)
        for (token in tokens) {
            val color = when {
                token.type == TokenType.COMMENT -> commentColor
                token.type == TokenType.STRING -> stringColor
                token.type == TokenType.NUMBER -> numberColor
                token.type == TokenType.KEYWORD -> keywordColor
                token.type == TokenType.FUNCTION -> functionColor
                token.type == TokenType.PUNCTUATION -> punctuationColor
                else -> defaultColor
            }
            withStyle(SpanStyle(color = color)) {
                append(token.text)
            }
        }
    }
}

private enum class TokenType {
    KEYWORD, STRING, COMMENT, NUMBER, FUNCTION, PUNCTUATION, PLAIN
}

private data class Token(val text: String, val type: TokenType)

private fun tokenizeLine(line: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var i = 0
    val len = line.length

    while (i < len) {
        // Line comment (// or #)
        if ((i + 1 < len && line[i] == '/' && line[i + 1] == '/') ||
            line[i] == '#'
        ) {
            tokens.add(Token(line.substring(i), TokenType.COMMENT))
            return tokens
        }

        // Block comment /* */
        if (i + 1 < len && line[i] == '/' && line[i + 1] == '*') {
            val end = line.indexOf("*/", i + 2)
            if (end != -1) {
                tokens.add(Token(line.substring(i, end + 2), TokenType.COMMENT))
                i = end + 2
                continue
            } else {
                tokens.add(Token(line.substring(i), TokenType.COMMENT))
                return tokens
            }
        }

        // String - double quote
        if (line[i] == '"') {
            val end = findStringEnd(line, i, '"')
            tokens.add(Token(line.substring(i, end), TokenType.STRING))
            i = end
            continue
        }

        // String - single quote
        if (line[i] == '\'') {
            val end = findStringEnd(line, i, '\'')
            tokens.add(Token(line.substring(i, end), TokenType.STRING))
            i = end
            continue
        }

        // String - backtick (template literals)
        if (line[i] == '`') {
            val end = line.indexOf('`', i + 1)
            val actualEnd = if (end == -1) len else end + 1
            tokens.add(Token(line.substring(i, actualEnd), TokenType.STRING))
            i = actualEnd
            continue
        }

        // Numbers
        if (line[i].isDigit() && (i == 0 || !line[i - 1].isLetterOrDigit())) {
            val start = i
            while (i < len && (line[i].isDigit() || line[i] == '.' || line[i] == 'f' ||
                        line[i] == 'L' || line[i] == 'x' || line[i] == 'X' ||
                        line[i] in 'a'..'f' || line[i] in 'A'..'F')
            ) {
                i++
            }
            tokens.add(Token(line.substring(start, i), TokenType.NUMBER))
            continue
        }

        // Punctuation / operators
        if (line[i] in "{}()[]<>,;:.=+-*/%!&|^~?:@") {
            tokens.add(Token(line[i].toString(), TokenType.PUNCTUATION))
            i++
            continue
        }

        // Word (identifier)
        if (line[i].isLetter() || line[i] == '_') {
            val start = i
            while (i < len && (line[i].isLetterOrDigit() || line[i] == '_')) {
                i++
            }
            val word = line.substring(start, i)
            // Check if followed by '(' — function call
            val trimmed = line.substring(i).trimStart()
            val isFunction = trimmed.startsWith("(")
            tokens.add(Token(word, if (isFunction) TokenType.FUNCTION else TokenType.PLAIN))
            continue
        }

        // Everything else (whitespace, etc.)
        tokens.add(Token(line[i].toString(), TokenType.PLAIN))
        i++
    }

    return tokens
}

private fun findStringEnd(line: String, start: Int, quote: Char): Int {
    var i = start + 1
    while (i < line.length) {
        if (line[i] == '\\') {
            i += 2 // skip escaped char
            continue
        }
        if (line[i] == quote) return i + 1
        i++
    }
    return line.length
}

// ═══════════════════════════════════════════════════════════════
// Tool call card
// ═══════════════════════════════════════════════════════════════

@Composable
fun ToolCallCard(toolCall: ToolCallInfo) {
    var expanded by remember { mutableStateOf(false) }

    val statusColor = when (toolCall.status) {
        ToolCallStatus.RUNNING -> WarningAmber
        ToolCallStatus.COMPLETED -> SuccessGreen
        ToolCallStatus.FAILED -> ErrorRed
        ToolCallStatus.PENDING -> DarkOnSurfaceVariant
    }

    val statusIcon = when (toolCall.status) {
        ToolCallStatus.RUNNING -> Icons.Filled.Sync
        ToolCallStatus.COMPLETED -> Icons.Filled.CheckCircle
        ToolCallStatus.FAILED -> Icons.Filled.Error
        ToolCallStatus.PENDING -> Icons.Filled.HourglassEmpty
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = DarkSurfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = null,
                    tint = HermesPrimaryLight,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "🛠  ${toolCall.name}",
                    color = DarkOnSurface,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                if (toolCall.status == ToolCallStatus.RUNNING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = statusColor
                    )
                } else {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess
                    else Icons.Filled.ExpandMore,
                    contentDescription = "Toggle details",
                    tint = DarkOnSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Expanded body
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                ) {
                    if (toolCall.arguments.isNotBlank()) {
                        Text(
                            text = "Arguments:",
                            color = DarkOnSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = DarkBackground
                        ) {
                            Text(
                                text = toolCall.arguments,
                                color = DarkOnSurface,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    toolCall.result?.let { result ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Result:",
                            color = DarkOnSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = DarkBackground
                        ) {
                            Text(
                                text = result,
                                color = SuccessGreen,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Typing indicator (animated dots)
// ═══════════════════════════════════════════════════════════════

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 0),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(4.dp)
    ) {
        Text(
            text = "Hermes is thinking",
            color = DarkOnSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
        Dot(dot1Alpha)
        Dot(dot2Alpha)
        Dot(dot3Alpha)
    }
}

@Composable
private fun Dot(alpha: Float) {
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(HermesPrimary.copy(alpha = alpha))
    )
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
                    focusedBorderColor = HermesPrimary.copy(alpha = 0.3f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
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
// Voice recording bar with waveform
// ═══════════════════════════════════════════════════════════════

@Composable
fun VoiceRecordingBar(
    amplitude: Float,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Recording indicator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(ErrorRed)
            )

            // Amplitude waveform
            VoiceWaveform(
                amplitude = amplitude,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
            )

            // Timer
            val elapsed = remember { mutableLongStateOf(0L) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(1000L)
                    elapsed.value += 1000
                }
            }
            Text(
                text = formatDuration(elapsed.longValue),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )

            // Stop button
            FilledIconButton(
                onClick = onStop,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = ErrorRed,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = "Stop recording",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun VoiceWaveform(
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val barCount = 24
    val barWidth = 4.dp
    val barGap = 2.dp

    // Animate amplitude transitions smoothly
    val animatedAmplitude by animateFloatAsState(
        targetValue = amplitude.coerceIn(0.1f, 1f),
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 200f),
        label = "waveform_amplitude"
    )

    Canvas(modifier = modifier.fillMaxWidth()) {
        val totalWidth = size.width
        val totalHeight = size.height
        val middle = totalHeight / 2f
        val barWidthPx = barWidth.toPx()
        val gapPx = barGap.toPx()
        val step = barWidthPx + gapPx
        val usableWidth = barCount * step - gapPx
        val startX = (totalWidth - usableWidth) / 2f

        for (i in 0 until barCount) {
            // Center bars are taller (simulate human speech envelope)
            val centerFactor = 1f - (kotlin.math.abs(i - barCount / 2f) / (barCount / 2f))
            val barHeight = (4f + centerFactor * animatedAmplitude * (totalHeight / 2.5f))
                .coerceAtMost(totalHeight / 2.2f)

            val x = startX + i * step
            val y1 = middle - barHeight / 2f
            val y2 = middle + barHeight / 2f

            drawRoundRect(
                color = HermesPrimary.copy(alpha = 0.6f + 0.4f * centerFactor),
                topLeft = androidx.compose.ui.geometry.Offset(x, y1),
                size = androidx.compose.ui.geometry.Size(barWidthPx, barHeight),
                cornerRadius = CornerRadius(barWidthPx / 2f, barWidthPx / 2f)
            )
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSecs = millis / 1000
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return "%d:%02d".format(mins, secs)
}

// ═══════════════════════════════════════════════════════════════
// Utility extension
// ═══════════════════════════════════════════════════════════════

private fun ClosedFloatingPointRange<Float>.random(): Float {
    return this.start + (this.endInclusive - this.start) * kotlin.random.Random.nextFloat()
}
