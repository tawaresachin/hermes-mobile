package com.hermes.mobile.ui.screens.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.hermes.mobile.data.local.DraftStore
import com.hermes.mobile.data.model.*
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.ui.components.AttachSheet
import com.hermes.mobile.ui.components.HermesWatermark
import com.hermes.mobile.ui.components.MessageActionSheet
import com.hermes.mobile.ui.components.ModelPickerSheet
import com.hermes.mobile.ui.components.ReplyBar
import com.hermes.mobile.R
import com.hermes.mobile.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.regex.Pattern
import javax.inject.Inject
import androidx.compose.ui.tooling.preview.Preview

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

enum class ToolCallStatus { RUNNING, COMPLETED, FAILED }

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

    // ── Model selection ──
    private val _currentModel = MutableStateFlow("")
    val currentModel: StateFlow<String> = _currentModel.asStateFlow()

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    // ── Emoji picker ──
    var showEmojiPicker = MutableStateFlow(false)
        private set

    fun toggleEmojiPicker() { showEmojiPicker.value = !showEmojiPicker.value }
    fun hideEmojiPicker() { showEmojiPicker.value = false }

    // ── Error state ──
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ── Initialisation ──
    private var messageJob: Job? = null
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
            when {
                sessionId != null -> resumeSession(sessionId)
                _sessionId.value != null -> observeMessages(_sessionId.value!!)
                else -> {
                    // Bottom-tab open with no active session: RESUME the
                    // latest session instead of silently starting a new one.
                    // New sessions come from the Home card / Sessions screen.
                    val last = try {
                        repository.getLastSession()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (last != null) resumeSession(last.id) else createNewSession()
                }
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
            // Clean stale streaming placeholders (app died mid-stream last
            // time) BEFORE observing — otherwise the next stream renders its
            // live text into the orphaned bubble too.
            repository.finalizeStaleStreaming(sessionId)
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
    // Generation counter: bump on every send so stale SSE chunks from a
    // cancelled stream can't clobber the new stream's UI content.
    private var streamGeneration = 0

    fun sendMessage(query: String, attachmentUrl: String? = null, attachType: String? = null, multiAgent: Boolean = false, replyTo: Message? = null) {
        val sid = _sessionId.value ?: return
        val gen = ++streamGeneration

        // Cancel previous stream and save pending content
        streamingJob?.cancel()
        val pendingContent = _streamingContent.value
        streamingJob = viewModelScope.launch {
            // Supersede the previous stream's placeholder. With text: finalize
            // it into a completed message. Without text (tool-call-only or the
            // throttled snapshot had nothing yet): DELETE it — finalizing blank
            // would leave an empty bubble, and leaving it isStreaming=1 makes
            // TWO rows render the next stream's text (the two-bubbles bug).
            if (pendingContent.isNotBlank()) {
                repository.finalizePendingMessage(sid, pendingContent)
            } else {
                repository.deletePendingMessage(sid)
            }

            _isStreaming.value = true
            _streamingContent.value = ""
            _toolCalls.value = emptyList()
            _errorMessage.value = null

            // StringBuilder avoids O(n²) re-concat per chunk; emissions are
            // time-throttled (≤20/s) so the UI doesn't recompose per chunk.
            val streamBuilder = StringBuilder()
            var lastEmitMs = 0L

            try {
                repository.sendMessage(
                    sessionId = sid,
                    query = query,
                    attachmentUrl = attachmentUrl ?: "",
                    attachType = attachType ?: "",
                    multiAgent = multiAgent,
                    replyTo = replyTo?.content,
                    onChunk = { chunk ->
                        // Drop chunks from a superseded stream (generation changed).
                        if (gen == streamGeneration) {
                            streamBuilder.append(chunk)
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastEmitMs >= 50L) {
                                lastEmitMs = now
                                _streamingContent.value = streamBuilder.toString()
                            }
                        }
                    },
                    onToolCall = { id, name, args ->
                        // Guard: stale callbacks from a superseded stream must
                        // not inject tool cards into the new stream's UI.
                        if (gen == streamGeneration) {
                            val tc = ToolCallInfo(
                                id = id,
                                name = name,
                                arguments = args,
                                status = ToolCallStatus.RUNNING
                            )
                            _toolCalls.value = _toolCalls.value + tc
                        }
                    },
                    onToolResult = { id, output ->
                        if (gen == streamGeneration) {
                            _toolCalls.value = _toolCalls.value.map {
                                if (it.id == id) it.copy(
                                    result = output,
                                    status = ToolCallStatus.COMPLETED
                                ) else it
                            }
                        }
                    }
                )
                _isStreaming.value = false
                // Clear streaming buffer — the finalized message (full content,
                // repo-side) replaces the streaming bubble in the list.
                _streamingContent.value = ""
                // Cancel any prior delayed clear so it can't wipe the NEXT message's tool calls
                toolClearJob?.cancel()
                val genAtComplete = streamGeneration
                toolClearJob = viewModelScope.launch {
                    delay(3000)
                    // Only clear if no new stream started since (a fast new
                    // send+complete within 3s must keep its tool cards).
                    if (genAtComplete == streamGeneration) {
                        _toolCalls.value = emptyList()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // never swallow cancellation into a ghost error
            } catch (e: Exception) {
                _isStreaming.value = false
                _errorMessage.value = e.message
            }
        }
    }

    fun getBaseUrl(): String = repository.getBaseUrl()

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

    // ── Send with attachment (ViewModel scope — survives recomposition cancellation) ──
        fun sendWithAttachment(
            text: String,
            attachment: PendingAttachment?,
            context: android.content.Context,
            onAttachComplete: () -> Unit,
            multiAgent: Boolean = false,
            replyTo: Message? = null
        ) {
            val sid = _sessionId.value ?: return
            viewModelScope.launch {
                var attachUrl: String? = null
                var attachType: String? = null
                if (attachment != null) {
                    try {
                        val tempFile = cacheAttachmentToTemp(context, attachment.uri)
                        if (tempFile == null) {
                            _errorMessage.value = "Attachment too large or unreadable (max 50 MB)"
                            onAttachComplete()
                            return@launch
                        }
                        attachUrl = repository.uploadFile(tempFile, attachment.fileName, attachment.mimeType)
                        tempFile.delete()
                        attachType = attachment.attachType
                    } catch (e: Exception) {
                        _errorMessage.value = "Upload failed: ${e.message}"
                    }
                }
                if (text.isNotBlank() || attachUrl != null) {
                    sendMessage(text, attachUrl, attachType, multiAgent = multiAgent, replyTo = replyTo)
                }
                onAttachComplete()
            }
        }

        // ── Telegram-style message actions ──
        fun deleteMessage(message: Message) {
            viewModelScope.launch {
                repository.deleteMessage(message.sessionId, message.id)
                _messages.value = _messages.value.filterNot { it.id == message.id }
            }
        }

        /** Regenerate: delete the assistant reply and resend the user
         *  message that preceded it. */
        fun regenerate(message: Message) {
            val list = _messages.value
            val idx = list.indexOfFirst { it.id == message.id }
            if (idx <= 0) return
            val userMsg = list.subList(0, idx).lastOrNull { it.role == MessageRole.USER } ?: return
            deleteMessage(message)
            sendMessage(userMsg.content)
        }

        /** Telegram-style reaction toggle (double-tap 👍 on a reply). */
        fun toggleReaction(message: Message) {
            val next = if (message.reaction == "👍") null else "👍"
            viewModelScope.launch {
                repository.setReaction(message.id, next)
                _messages.value = _messages.value.map {
                    if (it.id == message.id) it.copy(reaction = next) else it
                }
            }
        }

        /** All sessions — for the Telegram-style Forward dialog. */
        val allSessions = repository.allSessions
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

        /** Forward a message to another session: insert as a user message
         *  there and hand it to the AI (runs in the background — the target
         *  session's Room flow picks it up when opened). */
        fun forwardTo(targetSessionId: String, message: Message) {
            viewModelScope.launch {
                repository.forwardMessage(targetSessionId, message.content)
            }
        }

        /**
         * Copy a content-URI attachment into the app cache under a UUID
         * temp name. Security: the provider-controlled display name is NEVER
         * used as a path (a crafted "../" filename could traverse the cache
         * dir). 50 MB cap enforced while streaming the copy.
         */
        private suspend fun cacheAttachmentToTemp(
            context: android.content.Context,
            uri: android.net.Uri
        ): java.io.File? {
            val tempFile = java.io.File(context.cacheDir, "att_${java.util.UUID.randomUUID()}.tmp")
            return try {
                val copied = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: return@withContext false
                    input.use { ins ->
                        tempFile.outputStream().use { out ->
                            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > 50L * 1024 * 1024) return@use false
                                out.write(buf, 0, n)
                            }
                            true
                        }
                    }
                }
                if (!copied) {
                    tempFile.delete()
                    null
                } else {
                    tempFile
                }
            } catch (e: Exception) {
                tempFile.delete()
                null
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

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalAnimationApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun ChatScreen(
    paddingValues: PaddingValues
) {
    val context = LocalContext.current
    val vm: ChatViewModel = hiltViewModel()

    // Kill any in-flight dictation recognizer when the screen leaves —
    // otherwise the mic service keeps running (a leak).
    DisposableEffect(Unit) {
        onDispose { stopActiveDictation() }
    }

    val messages by vm.messages.collectAsState()
    val streamingContent by vm.streamingContent.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val connectionStatus by vm.connectionStatus.collectAsState()
    val toolCalls by vm.toolCalls.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()
    val sessionIdState by vm.sessionId.collectAsState()
    val showEmojiPicker by vm.showEmojiPicker.collectAsState()
    val currentModel by vm.currentModel.collectAsState()
    val availableModels by vm.availableModels.collectAsState()
    val modelsLoading by vm.modelsLoading.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var pendingAttachment by remember { mutableStateOf<PendingAttachment?>(null) }
    // Local in-chat search over the loaded messages.
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    // Telegram behavior: if the reader scrolled UP to read history, the
    // list NEVER yanks them back down. The flag flips when the user leaves
    // the bottom ~2 items and clears when they return — auto-scroll only
    // fires while they're parked at the bottom.
    var userScrolledAway by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index -> userScrolledAway = index > 2 }
    }
    val scope = rememberCoroutineScope()
    // ── Model picker state ──
    var showModelPicker by remember { mutableStateOf(false) }
    // ── Multi-agent mode: routes turns through the ruflo swarm ──
    var multiAgentMode by remember { mutableStateOf(false) }
    // ── Telegram-style interactions ──
    var pendingReply by remember { mutableStateOf<Message?>(null) }
    var menuTarget by remember { mutableStateOf<Message?>(null) }
    var showAttachSheet by remember { mutableStateOf(false) }
    var forwardTarget by remember { mutableStateOf<Message?>(null) }
    // ── Search jump-to + highlight ──
    var highlightId by remember { mutableStateOf<Long?>(null) }
    var searchIndex by remember { mutableStateOf(0) }

    // ── Gallery picker (Telegram-style attach sheet) ──
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            vm.hideEmojiPicker()
            scope.launch(Dispatchers.IO) {
                val cr = context.contentResolver
                val mimeType = cr.getType(uri) ?: "image/*"
                val ext = when (mimeType) {
                    "image/png" -> "png"
                    "image/gif" -> "gif"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                pendingAttachment = PendingAttachment(
                    uri = uri,
                    fileName = "gallery_${System.currentTimeMillis()}.$ext",
                    mimeType = mimeType,
                    attachType = "image"
                )
            }
        }
    }

    // ── File picker (stores selection, doesn't upload until send clicked) ──
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.hideEmojiPicker()
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
                        vm.sendMessage(text.trim(), multiAgent = multiAgentMode)
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

    // Auto-scroll: ONLY when a new message arrives (size change) and the
    // user is already near the bottom. The inverted layout keeps the newest
    // item pinned to the bottom edge — growing streaming text pushes UP
    // naturally, so no per-chunk scrolling is needed. The old effect keyed
    // on every streaming chunk and force-scrolled, fighting the user's
    // finger every ~50ms = the "stuck/bouncing" scroll feel.
    LaunchedEffect(messages.size, isStreaming, userScrolledAway) {
        if (messages.isNotEmpty() && !userScrolledAway) {
            listState.scrollToItem(0)
        }
    }

    // Load models when session ID is available + restore the draft
    LaunchedEffect(sessionIdState) {
        if (sessionIdState != null) {
            vm.loadModels()
            DraftStore.init(context)
            inputText = DraftStore.get(sessionIdState!!)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()  // Whole chat lifts above the keyboard (Telegram-style)
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
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(4.dp))
                // Hermes logo — the golden Caduceus (circle-clipped)
                Image(
                    painter = painterResource(R.drawable.hermes_caduceus),
                    contentDescription = "Hermes",
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Model chip (tappable — opens model picker)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            vm.loadModels()
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
                Spacer(modifier = Modifier.width(8.dp))
                // Multi-agent toggle — routes this chat's turns through the
                // ruflo swarm (8 parallel specialists). Slow (minutes) but
                // deep: use for heavy analysis/build tasks.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                ) {
                    Text(
                        text = "Multi",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (multiAgentMode) HermesPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = multiAgentMode,
                        onCheckedChange = { multiAgentMode = it },
                        modifier = Modifier.scale(0.75f),
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = HermesPrimary,
                            checkedThumbColor = Color.White
                        )
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                // Search
                IconButton(onClick = { showSearch = !showSearch; searchQuery = "" }) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = if (showSearch) HermesPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // New chat
                IconButton(onClick = { vm.clearSession() }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "New chat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Memoized display list (newest-first for the inverted LazyColumn).
        // Full O(n) filter+reverse only re-runs when the message list or
        // search query changes (NOT on every recomposition — streaming emits
        // ~20Hz of recompositions). Hoisted so the search bar can jump to
        // matches too.
        val displayMessages = remember(messages, searchQuery) {
            filteredMessages(searchQuery, messages)
                .asReversed()
                .filter {
                    // Never render blank assistant rows (whitespace-only
                    // responses / abandoned placeholders) — they show as
                    // unexplained gaps between messages. Streaming
                    // placeholders stay (they carry the live text).
                    it.role != MessageRole.ASSISTANT ||
                        it.isStreaming ||
                        it.content.isNotBlank()
                }
        }

        AnimatedVisibility(visible = showSearch) {
            val searchMatches = remember(messages, searchQuery) {
                if (searchQuery.isBlank()) emptyList()
                else messages.filter {
                    it.content.contains(searchQuery, ignoreCase = true) ||
                        (it.attachmentName?.contains(searchQuery, ignoreCase = true) == true)
                }
            }
            ChatSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it; searchIndex = 0; highlightId = null },
                resultCount = searchMatches.size,
                position = if (searchMatches.isEmpty()) 0 else searchIndex.coerceIn(0, searchMatches.size - 1),
                onPrev = if (searchMatches.isNotEmpty()) {
                    {
                        searchIndex = (searchIndex - 1 + searchMatches.size) % searchMatches.size
                        scope.launch {
                            jumpToSearchMatch(searchMatches[searchIndex], displayMessages, listState) {
                                highlightId = searchMatches[searchIndex].id
                            }
                        }
                    }
                } else null,
                onNext = if (searchMatches.isNotEmpty()) {
                    {
                        searchIndex = (searchIndex + 1) % searchMatches.size
                        scope.launch {
                            jumpToSearchMatch(searchMatches[searchIndex], displayMessages, listState) {
                                highlightId = searchMatches[searchIndex].id
                            }
                        }
                    }
                } else null,
                onClose = { showSearch = false; searchQuery = ""; highlightId = null }
            )
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
        // ── Telegram-style chat area (full width, no border) ──
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Telegram chat canvas: light gray (light) / deep navy (dark)
                // so white bubbles pop.
                .background(
                    if (LocalDarkTheme.current) ChatBackgroundDark else ChatBackgroundLight
                )
        ) {
            // Faded Hermes watermark — same on every screen (shared component)
            HermesWatermark()
            if (messages.isEmpty() && !isStreaming) {
                EmptyChatState(
                    onSuggestion = { suggestion -> vm.sendMessage(suggestion) }
                )
            } else {
                // Overscroll bounce/glow mirrors oddly on the inverted list —
                // disable it (clean Telegram feel, no rubber-band at the ends).
                CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                LazyColumn(
                    state = listState,
                    // Telegram-style bottom anchoring via the INVERTED LIST
                    // trick (bulletproof — does not rely on reverseLayout
                    // semantics): the whole list is flipped vertically so
                    // item 0 renders at the BOTTOM edge; each item is
                    // flipped back so content reads normally. Short content
                    // is mathematically forced to the bottom; history grows
                    // upward. Blank space sits ABOVE the conversation.
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleY = -1f },
                    // Per-item padding drives the gaps (1dp in-group, 6dp
                    // between groups) — the old uniform spacedBy is removed.
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        top = 2.dp,
                        bottom = 2.dp
                    )
                ) {
                    // Inverted list: FIRST declared items render at the
                    // visual BOTTOM — declare bottom-most UI first:
                    // 1. typing pulse (very bottom, above the input)
                    // 2. tool-execution cards (inline, under the newest msg)
                    // 3. messages, newest first
                    // ONE stable item for all live-stream UI (typing pulse +
                    // tool cards). A single always-present item avoids
                    // insert/remove layout shifts — separate conditional
                    // items made the list 'bounce' under the user's finger
                    // while reading history mid-stream.
                    if (isStreaming) {
                        item(key = "live_status") {
                            Box(Modifier.graphicsLayer { scaleY = -1f }) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (streamingContent.isBlank()) {
                                        TypingIndicator()
                                    }
                                    toolCalls.forEach { toolCall -> ToolCallCard(toolCall = toolCall) }
                                }
                            }
                        }
                    }
                    // Memoized display list (computed in the Box scope above —
                    // LazyListScope is not a composable context).
                    itemsIndexed(
                        items = displayMessages,
                        key = { _, it -> it.id.toString() }
                    ) { index, message ->
                        val isStreamingThis = isStreaming && message.isStreaming
                        val displayContent = if (isStreamingThis) streamingContent else message.content
                        // Telegram-style grouping: consecutive same-role
                        // messages form a group. In this newest-first list,
                        // the newer neighbor is index-1, the older is index+1.
                        val prevRole = displayMessages.getOrNull(index - 1)?.role
                        val nextRole = displayMessages.getOrNull(index + 1)?.role
                        val isGroupStart = nextRole != message.role  // oldest of group
                        val isGroupEnd = prevRole != message.role    // newest of group
                        // Telegram-style date separator: a "Today" /
                        // "Yesterday" / "12 Aug" pill at the day boundary
                        // (between this message and the newer one below).
                        val newerTs = displayMessages.getOrNull(index - 1)?.timestamp
                        val newDay = newerTs != null && !isSameDay(newerTs, message.timestamp)
                        val dateLabel = remember(message.timestamp) { datePillLabel(message.timestamp) }
                        Box(
                            Modifier
                                .graphicsLayer { scaleY = -1f }
                                // Tight 1dp inside a group, 6dp between groups
                                // (replaces the old uniform spacedBy(2.dp)).
                                .padding(bottom = if (isGroupStart) 6.dp else 1.dp)
                        ) {
                            Column {
                                if (newDay) {
                                    DatePill(text = dateLabel)
                                }
                                MessageBubble(
                                message = message,
                                displayContent = displayContent,
                                isStreaming = isStreamingThis,
                                baseUrl = vm.getBaseUrl(),
                                isFirstInGroup = isGroupStart,
                                isLastInGroup = isGroupEnd,
                                onEdit = if (message.role == MessageRole.USER && !isStreamingThis) {
                                    {
                                        inputText = message.content
                                        showSearch = false
                                        searchQuery = ""
                                    }
                                } else null,
                                onReply = if (isStreamingThis) null else {
                                    { pendingReply = message }
                                },
                                onDelete = if (isStreamingThis) null else {
                                    {
                                        if (pendingReply?.id == message.id) pendingReply = null
                                        vm.deleteMessage(message)
                                    }
                                },
                                onLongPress = if (isStreamingThis) null else {
                                    { menuTarget = message }
                                },
                                onReact = if (isStreamingThis || message.role == MessageRole.USER) null else {
                                    { vm.toggleReaction(message) }
                                },
                                highlighted = message.id == highlightId
                            )
                            } // Column (date pill + bubble)
                        }
                    }
                }
            }
            } // CompositionLocalProvider (overscroll off)
        }

        // ── Telegram-style reply bar (quote above the input) ──
        pendingReply?.let { replyMsg ->
            ReplyBar(
                message = replyMsg,
                onCancel = { pendingReply = null }
            )
        }

        InputBar(
            inputText = inputText,
            onInputChange = { text ->
                inputText = text
                // Only save drafts once the session id exists — saving under
                // "" would write a ghost draft and, worse, the session-init
                // restore would then OVERWRITE what the user just typed.
                sessionIdState?.let { DraftStore.set(it, text) }
            },
            onSend = {
                // Use ViewModel scope so cancellation doesn't lose messages
                vm.sendWithAttachment(inputText.trim(), pendingAttachment, context, onAttachComplete = {
                    pendingAttachment = null
                    inputText = ""
                }, multiAgent = multiAgentMode, replyTo = pendingReply)
                DraftStore.clear(sessionIdState ?: "")
                pendingReply = null
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
                                vm.sendMessage(text.trim(), replyTo = pendingReply)
                                pendingReply = null
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
                vm.hideEmojiPicker()
            },
            onAttach = { showAttachSheet = true },
            pendingAttachment = pendingAttachment,
            onRemoveAttachment = { pendingAttachment = null },
            isStreaming = isStreaming,
            showEmojiPicker = showEmojiPicker,
            onToggleEmojiPicker = { vm.toggleEmojiPicker() },
            enabled = sessionIdState != null
        )

        // ── Model Picker Bottom Sheet ──
        if (showModelPicker) {
            ModelPickerSheet(
                availableModels = availableModels,
                currentModel = currentModel,
                modelsLoading = modelsLoading,
                onSelect = { modelId, global -> vm.switchModel(modelId, global = global) },
                onDismiss = { showModelPicker = false }
            )
        }

        // ── Telegram-style attach sheet (Gallery / File) ──
        if (showAttachSheet) {
            AttachSheet(
                onGallery = {
                    showAttachSheet = false
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onFile = {
                    showAttachSheet = false
                    filePickerLauncher.launch(arrayOf("*/*"))
                },
                onDismiss = { showAttachSheet = false }
            )
        }

        // ── Telegram-style forward dialog ──
        forwardTarget?.let { target ->
            val sessions by vm.allSessions.collectAsState()
            ForwardDialog(
                sessions = sessions,
                onSelect = { sid ->
                    vm.forwardTo(sid, target)
                    forwardTarget = null
                },
                onDismiss = { forwardTarget = null }
            )
        }

        // ── Telegram-style message action sheet (long-press menu) ──
        menuTarget?.let { target ->
            val contextForClipboard = context
            MessageActionSheet(
                message = target,
                onCopy = {
                    val cm = contextForClipboard.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("message", target.content))
                    menuTarget = null
                },
                onReply = {
                    pendingReply = target
                    menuTarget = null
                },
                onForward = {
                    forwardTarget = target
                    menuTarget = null
                },
                onDelete = {
                    if (pendingReply?.id == target.id) pendingReply = null
                    vm.deleteMessage(target)
                    menuTarget = null
                },
                onRegenerate = if (target.role != MessageRole.USER) {
                    {
                        if (pendingReply?.id == target.id) pendingReply = null
                        vm.regenerate(target)
                        menuTarget = null
                    }
                } else null,
                onDismiss = { menuTarget = null }
            )
        }
    }
}
// ═══════════════════════════════════════════════════════════════
// Inline voice dictation — uses SpeechRecognizer directly
// ═══════════════════════════════════════════════════════════════

// Singleton: creating a second SpeechRecognizer while one is active errors
// out, and a recognizer left running after the screen leaves is a leak.
@Volatile
private var activeDictationRecognizer: android.speech.SpeechRecognizer? = null

private fun stopActiveDictation() {
    activeDictationRecognizer?.let { r ->
        try {
            r.destroy()
        } catch (_: Exception) {
        }
        activeDictationRecognizer = null
    }
}

private fun startVoiceDictation(
    context: android.content.Context,
    onFinalText: (String) -> Unit,
    onError: (String) -> Unit
) {
    if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
        onError("Speech recognition not available on this device")
        return
    }
    // Re-entry / double-tap guard: kill any previous recognizer first.
    stopActiveDictation()
    try {
        val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(context)
        if (recognizer == null) {
            onError("Speech recognition service unavailable")
            return
        }
        activeDictationRecognizer = recognizer
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
                stopActiveDictation()
                onError(msg)
            }
            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                stopActiveDictation()
                if (text.isNotBlank()) onFinalText(text)
                else onError("No speech detected")
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
fun EmptyChatState(onSuggestion: (String) -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Push the text below the centered Hermes watermark (240dp tall,
        // so its bottom edge sits ~124dp below screen center).
        Spacer(modifier = Modifier.height(132.dp))

        Text(
            text = "No messages here yet...",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Send a message or tap a suggestion below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )

        // Telegram-style suggestion chips — tap to ask instantly.
        Spacer(modifier = Modifier.height(20.dp))
        val suggestions = listOf(
            "What can you do?",
            "Plan my day",
            "Explain like I'm 5",
            "Summarize this article"
        )
        suggestions.chunked(2).forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                rowItems.forEach { suggestion ->
                    SuggestionChip(onClick = { onSuggestion(suggestion) }, label = suggestion)
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(onClick: () -> Unit, label: String) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = HermesPrimary.copy(alpha = 0.10f),
        modifier = Modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = HermesPrimary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

// ── Telegram-style date separators ──

/** Telegram-style forward dialog: pick the target session. */
@Composable
private fun ForwardDialog(
    sessions: List<Session>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forward to") },
        text = {
            if (sessions.isEmpty()) {
                Text(
                    "No other sessions yet. Create one from the Sessions tab first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(sessions) { s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(s.id) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = s.title ?: "Untitled Session",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun DatePill(text: String) {
    // fillMaxWidth + centered text: `align` only exists in BoxScope, and
    // this Surface is called from a Column (inside the flipped list item).
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

private fun isSameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

private fun datePillLabel(timestamp: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val now = java.util.Calendar.getInstance()
    if (isSameDay(cal.timeInMillis, now.timeInMillis)) return "Today"
    now.add(java.util.Calendar.DAY_OF_YEAR, -1)
    if (isSameDay(cal.timeInMillis, now.timeInMillis)) return "Yesterday"
    return java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
}

// ═══════════════════════════════════════════════════════════════
// Message bubble
// ═══════════════════════════════════════════════════════════════

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    displayContent: String,
    isStreaming: Boolean,
    baseUrl: String = "",
    onEdit: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onReact: (() -> Unit)? = null,
    highlighted: Boolean = false,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true
) {
    val isUser = message.role == MessageRole.USER
    val isDark = LocalDarkTheme.current
    val bubbleColor = if (isUser) {
        if (isDark) UserBubbleDark else UserBubbleLight
    } else {
        if (isDark) OtherBubbleDark else OtherBubbleLight
    }
    // Search-jump highlight: wash the bubble with the accent tint
    val effectiveBubbleColor = if (highlighted) {
        HermesPrimary.copy(alpha = 0.30f)
    } else bubbleColor
    val alignment = if (isUser) Arrangement.End else Arrangement.Start
    // Telegram-style grouping: within a group the inner corners square up
    // (6dp); the group's exposed ends stay rounded (18dp). Standalone
    // bubbles keep the exact same shape as before.
    val bubbleShape = RoundedCornerShape(
        topStart = if (isUser) 18.dp else (if (isFirstInGroup && !isLastInGroup) 18.dp else 6.dp),
        topEnd = if (isUser) (if (isFirstInGroup && !isLastInGroup) 18.dp else 6.dp) else 18.dp,
        bottomStart = if (isUser) 18.dp else (if (isLastInGroup) 18.dp else 6.dp),
        bottomEnd = if (isUser) (if (isLastInGroup) 18.dp else 6.dp) else 18.dp
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

    // Telegram-style bubble: width hugs the text (wraps), never wider than
    // ~78% of the available space — short texts get small bubbles.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val bubbleMax = this.maxWidth * 0.78f
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Telegram-style interactions:
                // - long-press → action sheet (copy / reply / delete / regen)
                // - horizontal swipe right → quick reply (fires once past
                //   ~90px; vertical scroll is untouched — separate axis)
                .combinedClickable(
                    enabled = !isStreaming,
                    onClick = {},
                    onLongClick = onLongPress,
                    onDoubleClick = onReact
                )
                .pointerInput(onReply, onDelete, isStreaming) {
                    if (isStreaming) return@pointerInput
                    // Telegram gestures: swipe RIGHT = reply, swipe LEFT =
                    // delete. Accumulate the drag (per-event deltas) and
                    // fire once past 90px; vertical scroll is untouched.
                    var acc = 0f
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        acc += dragAmount
                        if (acc > 90f && onReply != null) {
                            onReply()
                            acc = 0f
                        } else if (acc < -90f && onDelete != null) {
                            onDelete()
                            acc = 0f
                        }
                    }
                },
            horizontalArrangement = alignment
        ) {
            // Box so the tail can OVERLAY the bubble's top corner (a Column
            // child would render below the bubble instead).
            // Telegram-style: Hermes avatar on the LAST message of an
            // assistant group (left side, next to the bubble).
            if (!isUser && isLastInGroup && !isStreaming) {
                AsyncImage(
                    model = com.hermes.mobile.R.drawable.hermes_girl,
                    contentDescription = "Hermes",
                    modifier = Modifier
                        .padding(end = 6.dp, bottom = 2.dp)
                        .size(32.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
            Box {
            Column(
                modifier = Modifier
                    .widthIn(max = bubbleMax)
            ) {
            // Telegram-style: sender name above the FIRST message of an
            // assistant group.
            if (!isUser && isFirstInGroup && !isStreaming) {
                Text(
                    text = "Hermes",
                    style = MaterialTheme.typography.labelSmall,
                    color = HermesPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 14.dp, bottom = 2.dp)
                )
            }
            Surface(
                shape = bubbleShape,
                color = effectiveBubbleColor,
                // No shadowElevation: per-bubble shadows are the classic
                // Compose list-scroll jank source (Telegram bubbles are flat).
                shadowElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    // ── Telegram-style quote chip (reply preview) ──
                    // Rendered at the top of the replying bubble: accent-tinted
                    // box with the quoted text, max 2 lines.
                    if (message.replyToText?.isNotBlank() == true) {
                        Surface(
                            color = HermesPrimary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            Text(
                                text = message.replyToText!!.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isUser) textColor.copy(alpha = 0.85f)
                                else MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
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
                    // Edit affordance for finished user messages — loads the
                    // text back into the input to resend in the SAME session.
                    if (onEdit != null && !isStreaming && isUser && displayContent.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Telegram-style sent tick (single ✓)
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Sent",
                                tint = textColor.copy(alpha = 0.5f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Edit",
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = onEdit,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "Edit message",
                                    tint = textColor.copy(alpha = 0.6f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                    // Telegram-style reaction badge (double-tap to toggle 👍)
                    if (message.reaction != null && !isStreaming) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isUser) Color.White.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        ) {
                            Text(
                                text = message.reaction!!,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            } // Column (bubble)
            // ── Telegram-style tail (small arrow at the top corner) — a
            // BOX child so it overlays the bubble corner (BoxScope.align
            // accepts full alignments; ColumnScope only horizontal ones).
            // One tail per GROUP: only the first (top) message carries it.
            if (isFirstInGroup) {
            Canvas(
                modifier = Modifier
                    .align(if (isUser) Alignment.TopEnd else Alignment.TopStart)
                    .size(9.dp)
            ) {
                val tailPath = Path().apply {
                    if (isUser) {
                        // Right-side bubble: tail at the top-right, pointing out
                        moveTo(0f, 9.dp.toPx())
                        lineTo(9.dp.toPx(), 0f)
                        lineTo(9.dp.toPx(), 9.dp.toPx())
                    } else {
                        // Left-side bubble: tail at the top-left, pointing out
                        moveTo(0f, 0f)
                        lineTo(9.dp.toPx(), 9.dp.toPx())
                        lineTo(0f, 9.dp.toPx())
                    }
                    close()
                }
                drawPath(tailPath, bubbleColor)
            }
            }
            } // Box (tail overlay)
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
    // STATIC text — the old infinite alpha pulse animated the whole text
    // layer at 60fps (every frame re-rendered the bubble → scroll jank
    // while streaming). The blinking cursor is StreamingIndicator's tiny
    // separate glyph, which is cheap to animate.
    Text(
        text = text,
        style = style,
        color = color,
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
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (toolCall.status) {
                        ToolCallStatus.RUNNING -> WarningAmber
                        ToolCallStatus.COMPLETED -> SuccessGreen
                        ToolCallStatus.FAILED -> ErrorRed
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
        modifier = Modifier.fillMaxWidth(),
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
                    .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
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
                    // Telegram look: flat rounded field, NO outline. The
                    // container bg is the field itself (white on light,
                    // dark navy on dark), inside the bar.
                    TextField(
                        value = inputText,
                        onValueChange = onInputChange,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 36.dp, max = 120.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                if (LocalDarkTheme.current) InputBarDark else InputBarLight
                            ),
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
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
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

                    // ── 4. Mic / Send (alternate in the same slot — like Telegram) ──
                    // Empty input → mic; text/attachment present → send replaces it.
                    val hasContent = inputText.isNotBlank() || pendingAttachment != null
                    if (hasContent) {
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
                    } else {
                        FilledIconButton(
                            onClick = onVoice,
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = HermesPrimary,
                                contentColor = Color.White
                            ),
                            enabled = enabled
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "Voice input",
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
 * Supports: **bold**, *italic*, `inline code`, ```fenced code blocks```,
 * and `> quote` lines (italic + accent color).
 */
private fun parseMarkdown(
    text: String,
    style: TextStyle,
    baseColor: Color
): AnnotatedString {
    val codeBackground = baseColor.copy(alpha = 0.10f)
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = codeBackground
    )
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            // Fenced code block: ```...``` (multi-line, monospace + bg)
            if (text.startsWith("```", i)) {
                val end = text.indexOf("```", i + 3)
                if (end != -1) {
                    val start = length
                    append(text.substring(i + 3, end).trim('\n'))
                    addStyle(codeStyle, start, length)
                    i = end + 3
                    continue
                }
            }
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
            // Inline code: `...` → monospace + subtle background
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end != -1 && end > i + 1) {
                    val start = length
                    append(text.substring(i + 1, end))
                    addStyle(codeStyle, start, length)
                    i = end + 1
                    continue
                }
            }
            // Quote line: starts with "> " → italic + accent tint
            if (text[i] == '>' && (i == 0 || text[i - 1] == '\n')) {
                val lineEnd = text.indexOf('\n', i)
                val contentEnd = if (lineEnd == -1) text.length else lineEnd
                val start = length
                append(text.substring(i + 1, contentEnd))
                addStyle(
                    SpanStyle(
                        fontStyle = FontStyle.Italic,
                        color = baseColor.copy(alpha = 0.75f)
                    ),
                    start, length
                )
                i = contentEnd
                continue
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

// ── Local in-chat search helpers ──

/** Filter the loaded messages by [query] (case-insensitive substring). */
private fun filteredMessages(query: String, messages: List<Message>): List<Message> {
    if (query.isBlank()) return messages
    val q = query.trim()
    return messages.filter { it.content.contains(q, ignoreCase = true) }
}

/**
 * Telegram-style search jump: scroll the inverted list to the match and
 * flash its bubble. `displayList` is newest-first (index 0 = bottom).
 */
private suspend fun jumpToSearchMatch(
    target: Message,
    displayList: List<Message>,
    listState: LazyListState,
    onHighlight: () -> Unit
) {
    val idx = displayList.indexOfFirst { it.id == target.id }
    if (idx >= 0) {
        listState.scrollToItem(idx)
        onHighlight()
    }
}

/** Stripped-down search bar shown when the lens is active. */
@Composable
private fun ChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    resultCount: Int,
    position: Int = 0,
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Search in this chat") },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = HermesPrimary,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
            )
        )
        if (query.isNotBlank() && resultCount > 0) {
            // ── Jump-to: prev/next + "n/m" position (Telegram-style) ──
            IconButton(onClick = { onPrev?.invoke() }, enabled = onPrev != null) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Previous match",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Next match",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${position + 1}/$resultCount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
