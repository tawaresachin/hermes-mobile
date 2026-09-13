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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.*
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.runtime.*
import com.hermes.mobile.network.ServerCommand
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.hermes.mobile.ui.markdown.TableParse
import com.hermes.mobile.ui.preview.FilePreviewSheet
import com.hermes.mobile.ui.preview.previewKindFor
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
    savedStateHandle: SavedStateHandle,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
) : ViewModel() {

    /** For the file-preview sheet (composable-level needs the download fn). */
    fun repository(): HermesRepository = repository

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

    // ── Tool calls detected during streaming ──
    private val _toolCalls = MutableStateFlow<List<ToolCallInfo>>(emptyList())
    val toolCalls: StateFlow<List<ToolCallInfo>> = _toolCalls.asStateFlow()

    // ── Connection status ──
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()
    // ── Model selection ──
    private val _currentModel = MutableStateFlow("")
    val currentModel: StateFlow<String> = _currentModel.asStateFlow()

    // ── Context meter (live tokens used in THIS session / model window) ──
    private val _contextUsed = MutableStateFlow(0L)
    val contextUsed: StateFlow<Long> = _contextUsed.asStateFlow()
    private val _contextTotal = MutableStateFlow(0L)
    val contextTotal: StateFlow<Long> = _contextTotal.asStateFlow()

    // Bubble text size: user-tunable (Preferences). Default 15sp — the
    // 16sp Telegram parity read too heavy on a phone in practice.
    val chatFontSp: StateFlow<Float> = repository.chatFontSp

    /** Resolve the selected model's context window once per model id. */
    fun refreshContextTotal() {
        val model = _currentModel.value
        if (model.isBlank() || _contextTotal.value > 0L) return
        viewModelScope.launch {
            val slug = providerFor(model)
            val total = repository.fetchContextWindow(model, slug)
            if (total > 0) _contextTotal.value = total
        }
    }

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    // Current model name + provider for header display
    private val _selectedModelName = MutableStateFlow("AI Assistant")
    val selectedModelName: StateFlow<String> = _selectedModelName.asStateFlow()

    private val _selectedModelProvider = MutableStateFlow("")
    val selectedModelProvider: StateFlow<String> = _selectedModelProvider.asStateFlow()

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
                _sessionId.value != null -> {
                    observeMessages(_sessionId.value!!)
                    observeLiveTurn(_sessionId.value!!)
                }
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
    /** Server-truth slash palette (plugin /api/mobile/commands — same
     * registry Telegram's menu renders). Empty until first load / on old
     * plugins without the route -> the hardcoded list keeps working. */
    private val _serverCommands = MutableStateFlow<List<ServerCommand>>(emptyList())
    val serverCommands: StateFlow<List<ServerCommand>> = _serverCommands.asStateFlow()

    fun refreshServerCommands() {
        viewModelScope.launch {
            val cmds = try { repository.fetchServerCommands() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            if (cmds != null) _serverCommands.value = cmds
        }
    }

    init {
        checkConnection()
        refreshServerCommands()
        // Poll connection every 5s when not connected
        viewModelScope.launch {
            try {
                while (isActive) {
                    delay(5000)
                    if (_connectionStatus.value != ConnectionStatus.CONNECTED) {
                        checkConnection()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
    }

    /** Apply this session's own saved model (or clear to "" so loadModels
     * can pick the server default for a session that never chose one). */
    private fun restoreSessionModel(sessionId: String) {
        _currentModel.value = repository.savedModelForSession(sessionId) ?: ""
        _currentProviderSlug.value = repository.savedModelSlugForSession(sessionId)
        _swarmEnabled.value = repository.isSwarmForSession(sessionId)
        _contextTotal.value = 0L
        _contextUsed.value = 0L
    }

    // ── Swarm mode (sticky per session, like the model pick) ──
    // ON: each run carries SWARM_DIRECTIVE — the agent may split substantive
    // work into a real Kanban Swarm graph (official `hermes kanban swarm`
    // CLI + gateway dispatcher spawns the workers; proven E2E on this box).
    private val _swarmEnabled = MutableStateFlow(false)
    val swarmEnabled: StateFlow<Boolean> = _swarmEnabled.asStateFlow()

    fun setSwarmEnabled(on: Boolean) {
        _swarmEnabled.value = on
        _sessionId.value?.let { repository.saveSwarmForSession(it, on) }
    }

    private suspend fun createNewSession() {
        try {
            val session = repository.createSession()
            _sessionId.value = session.id
            restoreSessionModel(session.id)
            observeMessages(session.id)
            observeLiveTurn(session.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Legitimate cancellation when scope is torn down — suppress
        } catch (e: Exception) {
            _errorMessage.value = "Could not start a new chat — pull to refresh or try again"
        }
    }

    private suspend fun resumeSession(sessionId: String) {
        try {
            _sessionId.value = sessionId
            restoreSessionModel(sessionId)
            observeLiveTurn(sessionId)
            // Clean stale streaming placeholders (app died mid-stream last
            // time) BEFORE observing — otherwise the next stream renders its
            // live text into the orphaned bubble too. SKIPPED while a
            // RunController turn is live for this session: the durable run
            // survived the process death and owns that placeholder row.
            if (!repository.runController.isBusy(sessionId)) {
                repository.finalizeStaleStreaming(sessionId)
            }
            observeMessages(sessionId)
            val warm = repository.resumeSession(sessionId) // warm cache
            // Seed the context meter from the last persisted turn so the
            // bar shows usage the moment the session opens, not only after
            // the next reply.
            warm.lastOrNull { it.role == MessageRole.ASSISTANT && it.contextTokens > 0 }
                ?.let { _contextUsed.value = it.contextTokens }
            refreshContextTotal()
            // Safety net: if the LAST response was lost (stream died while
            // the user was away), recover it from the server.
            repository.repairBlankAssistantResponse(sessionId)
            // Backfill attachment fields from the server onto local rows
            // created before in-stream attachments — Telegram keeps media
            // on every bubble forever; reopening must show it.
            repository.backfillAttachments(sessionId)
            // Re-read so backfilled bubbles render without a manual refresh.
            _messages.value = repository.resumeSession(sessionId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Legitimate cancellation when scope is torn down — suppress
        } catch (e: Exception) {
            _errorMessage.value = "Could not reopen this chat — it may have been deleted"
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
                _errorMessage.value = "Could not load messages — check the connection"
            }
        }
    }

    // ── Send message ──

    /** Provider SLUG for a model id (server contract). The display label
     * ("FreeLLM") must never go on the wire — it yields custom:custom:... 404s. */
    /** Provider SLUG for a model id, disambiguated by the pinned slug when
     * the same id exists under several providers (bare-id collision). */
    fun providerFor(modelId: String): String? {
        val matches = _availableModels.value.filter { it.id == modelId }
        val m = matches.firstOrNull { it.providerSlug == _currentProviderSlug.value }
            ?: matches.firstOrNull()
        return m?.providerSlug?.takeIf { it.isNotBlank() }
    }

    private val _currentProviderSlug = MutableStateFlow("")
    val currentProviderSlug: String get() = _currentProviderSlug.value

    /** Pin this session to `modelId` (persisted). Unknown ids are still
     * accepted — the server decides; we never substitute silently. */
    private fun pinModel(sessionId: String, modelId: String, providerSlug: String = _currentProviderSlug.value) {
        _currentModel.value = modelId
        _currentProviderSlug.value = providerSlug
        _contextTotal.value = 0L
        repository.saveModelForSession(sessionId, modelId, providerSlug)
        refreshContextTotal()
        val found = _availableModels.value.firstOrNull {
            it.id == modelId && (providerSlug.isBlank() || it.providerSlug == providerSlug)
        }
        _selectedModelName.value = found?.name ?: modelId.substringAfterLast("/").take(20)
        _selectedModelProvider.value = found?.provider ?: ""
    }

    // ── Slash command executor ──────────────────────────────────────────
    // Mirrors what the Telegram adapter does client-side: commands act on
    // THIS session locally; info commands read server truth. Unknown slash
    // text falls through to the agent (so "/me lol" style typing still works).
    private suspend fun handleSlashCommand(sid: String, raw: String) {
        val parts = raw.removePrefix("/").trim().split(Regex("[\\s]+"), limit = 2)
        val cmd = (parts.firstOrNull() ?: "").lowercase()
        val arg = if (parts.size > 1) parts[1].trim() else ""

        fun reply(text: String) {
            viewModelScope.launch {
                repository.insertLocalSystemMessage(sid, text)
                _messages.value = repository.resumeSession(sid)
            }
        }

        suspend fun systemModelLine(): String {
            val opts = repository.fetchModelOptions()
            val cur = opts?.current ?: "?"
            val prov = opts?.provider ?: "?"
            return cur + if (prov.isNotBlank() && prov != cur) " ($prov)" else ""
        }

        when (cmd) {
            "new", "reset", "clear" -> {
                stopStreaming()
                createNewSession()
            }
            "stop" -> {
                stopStreaming()
                reply("⏹ Stopped the current response.")
            }
            "model" -> {
                if (arg.isBlank()) showModelPickerGlobal.value = true
                else {
                    val hit = _availableModels.value.firstOrNull {
                        it.id.equals(arg, true) || it.name.equals(arg, true)
                    }
                    if (hit != null) {
                        switchModel(hit.id, hit.providerSlug)
                        reply("Model set to ${hit.name} (${hit.provider}).")
                    } else {
                        val avail = _availableModels.value.take(12).joinToString(", ") { it.name }
                        reply("Model '$arg' not found.\nCurrent: ${_currentModel.value.ifBlank { "(server default)" }}\nAvailable: $avail${if (_availableModels.value.size > 12) " … (open the picker for all)" else ""}")
                    }
                }
            }
            "whoami" -> {
                val j = repository.getJson("/api/system/status")
                reply("You: Sachin (paired device)\nServer: " +
                    (j?.optString("os") ?: "?") + " · python " + (j?.optString("python")?.take(4) ?: "?"))
            }
            "tools" -> {
                val j = repository.getJson("/v1/toolsets")
                val names = j?.optJSONArray("data")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        o.optString("label", o.optString("name")) +
                            (if (o.optBoolean("enabled")) "" else " (off)")
                    }
                }.orEmpty()
                reply("Toolsets: " + names.joinToString(", "))
            }
            "usage" -> {
                val s = repository.getServerUsageStats()
                reply(if (s != null)
                    "Server ledger — sessions: ${s.sessions}, messages: ${s.messages}, " +
                    "tokens in: ${meterFmt(s.inputTokens)}, out: ${meterFmt(s.outputTokens)}"
                else "Usage endpoint not reachable.")
            }
            "queue" -> {
                if (arg.isBlank()) reply("Usage: /queue <text> — send now; it will run after the current reply.")
                else sendMessage(arg)
            }
            "steer" -> {
                // Telegram-parity default is queue; steer stays reachable as
                // a command instead of a persistent chip. sendMessage's
                // steer path falls through to queue when the run settled.
                if (arg.isBlank()) reply("Usage: /steer <text> — inject into the running reply instead of queueing.")
                else sendMessage(arg, steer = true)
            }
            "compress" -> reply("Context: ${meterFmt(_contextUsed.value)} / ${meterFmt(_contextTotal.value)} tokens.\nAuto-compression runs server-side at ~50% — nothing to do manually.")
            "commands" -> reply("Commands act here: " + SLASH_COMMANDS.joinToString(" ") { it.command } +
                "\nAnything else starting with / goes to the agent as-is.")
            "help" -> reply(
                "Slash commands\n" +
                "/new /reset — new chat\n" +
                "/stop — stop current response\n" +
                "/model — model list · /model <name> — switch\n" +
                "/status — this session\n" +
                "/context — token window usage\n" +
                "/title <text> — rename session\n" +
                "/retry — resend last message\n" +
                "/skills — installed skills\n" +
                "/version — server + app version"
            )
            "status" -> reply(
                "Session: ${(sid.take(10))}\n" +
                "Model: ${_currentModel.value.ifBlank { "(default)" }}" +
                (providerFor(_currentModel.value)?.let { " · provider $it" } ?: "") + "\n" +
                "Messages: ${_messages.value.size}\n" +
                "Server: " + run {
                    val s = repository.getServerUsageStats()
                    if (s != null) "connected · ${s.sessions} sessions" else "offline?"
                }
            )
            "context" -> {
                val used = _contextUsed.value
                val total = _contextTotal.value
                reply(if (total > 0) {
                    val pct = (used * 100 / total).coerceAtMost(100L)
                    "Context: ${meterFmt(used)} / ${meterFmt(total)} tokens (${pct}%)\n" +
                    (if (pct >= 85) "Auto-compression will kick in soon." else "Auto-compression active below 50% headroom.")
                } else "No context data yet — send a message first.")
            }
            "title" -> {
                if (arg.isBlank()) reply("Usage: /title <new session name>")
                else {
                    repository.renameSession(sid, arg)
                    reply("Session renamed to '$arg'.")
                }
            }
            "retry" -> {
                val lastUser = _messages.value.lastOrNull { it.role == MessageRole.USER }
                if (lastUser == null) reply("Nothing to retry yet.")
                else sendMessage(lastUser.content)
            }
            "skills" -> {
                val json = repository.getJson("/v1/skills")
                val names = json?.optJSONArray("data")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
                    }
                }.orEmpty()
                reply(if (names.isEmpty()) "No skills reported by the server."
                      else "Skills (${names.size}): " + names.take(25).joinToString(", ") +
                           if (names.size > 25) " …" else "")
            }
            "version" -> {
                val status = repository.getJson("/health")
                val pkg = try {
                    val pi = appContext.packageManager
                        .getPackageInfo(appContext.packageName, 0)
                    pi.versionName
                } catch (_: Exception) { "?" }
                reply("App v$pkg\nServer: Hermes " +
                    (status?.optString("version")?.takeIf { it.isNotBlank() } ?: "unknown") +
                    " · plugin " + (repository.getJson("/api/audio/health")
                        ?.optString("plugin_version") ?: "?"))
            }
            else -> {
                // Skill commands ride the server: /archify foo expands to
                // the same invocation prompt the Telegram adapter injects,
                // then runs as a normal durable turn. Core commands the app
                // doesn't implement locally (and anything unknown) still go
                // to the agent as-is — the model decides.
                val expanded = repository.resolveSkillCommand("/" + cmd, arg)
                if (expanded != null) {
                    // Show the friendly "/cmd args" as the bubble text; the
                    // expanded prompt is what the agent receives.
                    sendMessage(expanded, bypassSlash = true, displayText = raw)
                } else {
                    sendMessage(raw, bypassSlash = true)
                }
            }
        }
    }

    // Picker open-state as flow so the slash executor can raise it.
    private val showModelPickerGlobal = MutableStateFlow(false)
    val showModelPickerState: StateFlow<Boolean> = showModelPickerGlobal.asStateFlow()
    fun consumeModelPickerRequest() { showModelPickerGlobal.value = false }

    fun sendMessage(query: String, attachmentUrl: String? = null, attachType: String? = null, replyTo: Message? = null, bypassSlash: Boolean = false, attachmentPath: String = "", steer: Boolean = false, displayText: String? = null) {
        val sid = _sessionId.value ?: return
        // ── Slash commands ──
        // The gateway's slash handlers are messaging-platform-only (adapter
        // command table); api_server chats never see them — the raw text
        // would go to the MODEL instead. The Telegram adapter answers these
        // client-side too, so the app mirrors it with real executors below.
        if (!bypassSlash && attachmentUrl.isNullOrBlank() && query.trim().startsWith("/") && query.length <= 200) {
            viewModelScope.launch { handleSlashCommand(sid, query.trim()) }
            return
        }
        val model = _currentModel.value
        val provider = providerFor(model)
        // STEER MODE: the user chose to redirect the RUNNING turn instead
        // of queueing behind it (Telegram mid-run message parity). If the
        // run rejects the steer (already settled), fall through to queue.
        if (steer && _isStreaming.value && attachmentUrl.isNullOrBlank()) {
            steerRunning(query) {
                // Run rejected the steer (settled between tap and request):
                // the message still gets its own turn — queue it.
                viewModelScope.launch { enqueueMessage(sid, query, attachmentUrl, attachType, replyTo, attachmentPath) }
            }
            return
        }
        // TELEGRAM QUEUE MODEL: one message → ONE complete response, and
        // NO QUERY IS EVER DISCARDED. If the agent is already working, the
        // new message is saved locally + queued; it gets its own turn the
        // moment the current response completes (FIFO). Nothing is
        // cancelled, nothing is dropped, responses never interleave.
        if (_isStreaming.value) {
            viewModelScope.launch { enqueueMessage(sid, query, attachmentUrl, attachType, replyTo, attachmentPath, displayText) }
            return
        }
        startTurn(sid, query, attachmentUrl, attachType, replyTo, null, model, provider, attachmentPath, displayText = displayText)
    }

    private data class QueuedMessage(
        val query: String,
        val attachmentUrl: String?,
        val attachType: String?,
        val replyTo: Message?,
        val userMsgId: Long?,
        val attachmentPath: String = "",
        val displayText: String? = null
    )

    private val pendingQueue = ArrayDeque<QueuedMessage>()

    /** Room ids of messages currently WAITING in the queue (not yet sent to
     * the server). Each shows its own Stop square — cancelling one removes
     * it from the queue and ticks it FAILED locally. */
    private val _queuedIds = MutableStateFlow<Set<Long>>(emptySet())
    val queuedIds: StateFlow<Set<Long>> = _queuedIds.asStateFlow()

    /** Cancel ONE queued message (its Stop square): remove it from the FIFO
     * and tick it FAILED locally — the server never sees it, nothing is
     * lost, the remaining queue keeps draining normally. */
    fun stopQueuedMessage(msgId: Long) {
        val sid = _sessionId.value ?: return
        pendingQueue.removeAll { it.userMsgId == msgId }
        _queuedIds.value = _queuedIds.value - msgId
        viewModelScope.launch {
            try {
                repository.markMessageFailed(msgId)
                _messages.value = repository.resumeSession(sid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
    }

    /** Telegram INTERRUPT mode — the Stop button. Asks the SERVER to stop
     * the durable run (POST /v1/runs/{id}/stop): the agent interrupts, the
     * partial persists, the terminal event finalizes the bubble. Clears the
     * queue: the operator chose to stop, not to batch-send. */
    fun stopStreaming() {
        val sid = _sessionId.value ?: return
        repository.runController.stop(sid)
        pendingQueue.clear()
        _queuedIds.value = emptySet()
        if (repository.runController.liveTurn(sid) == null) {
            _isStreaming.value = false
            _streamingContent.value = ""
            _toolCalls.value = emptyList()
        }
    }

    // ── Live turn observation (durable runs) ──
    // The RunController owns the turn in an APP-level scope: navigating away
    // or losing the socket cannot cancel it. This collector is the screen's
    // window onto it — re-attached on every session open, dropped on clear.

    private var liveJob: kotlinx.coroutines.Job? = null
    private var lastBusySid: String? = null

    private fun observeLiveTurn(sid: String) {
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            repository.runController.turns
                .map { it[sid] }
                .distinctUntilChanged()
                .collect { turn ->
                    _isStreaming.value = turn != null
                    _streamingContent.value = turn?.streamingText ?: ""
                    _toolCalls.value = turn?.toolLines?.map {
                        ToolCallInfo(
                            id = it.name, name = it.name, arguments = it.label,
                            status = when (it.status) {
                                "completed" -> ToolCallStatus.COMPLETED
                                "failed" -> ToolCallStatus.FAILED
                                else -> ToolCallStatus.RUNNING
                            })
                    } ?: emptyList()
                    _pendingApproval.value = turn?.pendingApproval
                        ?.let { ApprovalUi(it.command, it.description, it.choices) }
                    _turnStatusNote.value = turn?.statusNote
                    // Turn settled (busy → idle for THIS session): drain the
                    // FIFO queue — the next queued message gets its turn now.
                    if (turn == null && lastBusySid == sid) {
                        lastBusySid = null
                        drainQueue(sid)
                        refreshContextMeter()
                    } else if (turn != null) {
                        lastBusySid = sid
                    }
                }
        }
        // Re-attach the event stream: the controller kept the run alive while
        // this screen was gone; now the UI should see its deltas again.
        repository.runController.attach(sid)
    }

    private suspend fun enqueueMessage(
        sid: String, query: String, attachmentUrl: String?, attachType: String?,
        replyTo: Message?, attachmentPath: String, displayText: String? = null
    ) {
        try {
            val uid = repository.insertLocalUserMessage(
                sid, displayText ?: query, attachmentUrl ?: "", attachType ?: "", replyTo?.content)
            pendingQueue.addLast(
                QueuedMessage(query, attachmentUrl, attachType, replyTo, uid, attachmentPath, displayText))
            _queuedIds.value = _queuedIds.value + uid
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) { }
    }

    private fun drainQueue(sid: String) {
        val next = pendingQueue.removeFirstOrNull() ?: return
        if (next.userMsgId != null) _queuedIds.value = _queuedIds.value - next.userMsgId
        startTurn(sid, next.query, next.attachmentUrl, next.attachType, next.replyTo,
            next.userMsgId, _currentModel.value, providerFor(_currentModel.value),
            next.attachmentPath, next.displayText)
    }

    private fun refreshContextMeter() {
        val sid = _sessionId.value ?: return
        viewModelScope.launch {
            val live = try { repository.fetchContextUsage(sid) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { 0L }
            if (live > 0) _contextUsed.value = live
            if (_contextTotal.value == 0L) refreshContextTotal()
        }
    }

    /** A tool call waiting for the user's decision (rendered as an inline
     * approval card above the composer). */
    data class ApprovalUi(val command: String, val description: String, val choices: List<String>)

    private val _pendingApproval = MutableStateFlow<ApprovalUi?>(null)
    val pendingApproval: StateFlow<ApprovalUi?> = _pendingApproval.asStateFlow()

    /** Agent lifecycle note from the live run (rate-limit wait, retry
     * countdown) — the header subtitle shows it instead of bare "thinking". */
    private val _turnStatusNote = MutableStateFlow<String?>(null)
    val turnStatusNote: StateFlow<String?> = _turnStatusNote.asStateFlow()

    fun resolveApproval(choice: String) {
        val sid = _sessionId.value ?: return
        repository.runController.resolveApproval(sid, choice)
    }

    /** Telegram mid-run steer: inject guidance into the RUNNING turn.
     * Returns false (caller falls back to queue) when the run rejects it. */
    fun steerRunning(text: String, onRejected: () -> Unit = {}) {
        val sid = _sessionId.value ?: return
        repository.runController.steer(sid, text) { accepted ->
            if (!accepted) onRejected()
        }
    }

    /** Branch this chat from here (server-side transcript copy). */
    fun forkSession(onDone: (String?) -> Unit = {}) {
        val sid = _sessionId.value ?: return
        viewModelScope.launch {
            val newId = try { repository.forkSession(sid) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            onDone(newId)
        }
    }

    private fun startTurn(
        sid: String,
        query: String,
        attachmentUrl: String?,
        attachType: String?,
        replyTo: Message?,
        userMsgId: Long?,
        model: String? = null,
        provider: String? = null,
        attachmentPath: String = "",
        displayText: String? = null,
    ) {
        _errorMessage.value = null
        repository.runController.startTurn(
            sessionId = sid,
            query = query,
            model = model ?: _currentModel.value,
            provider = provider,
            attachmentUrl = attachmentUrl ?: "",
            attachType = attachType ?: "",
            attachmentPath = attachmentPath,
            replyTo = replyTo?.content,
            userMsgId = userMsgId,
            displayText = displayText,
            onAdmitError = { msg ->
                _errorMessage.value = when {
                    msg.contains("401") -> "API key rejected. Update it in Settings → Account."
                    msg.contains("429") -> "Server is busy (concurrency limit). Try again shortly."
                    else -> msg
                }
                _connectionStatus.value = ConnectionStatus.ERROR
            },
        )
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

    // ── Response delivery (push-first, poll as fallback) ──
    // Primary: a per-session SSE subscription — the server PUSHES
    // 'response_ready' the instant a response is saved (instant, idle).
    // Catch-up: one poll on open (covers responses saved while away).
    // Fallback: a 5s poll ONLY while the subscription is down + retry.
    private var pollJob: kotlinx.coroutines.Job? = null

    /** Cross-surface catch-up: a turn answered on Telegram/CLI while this
     * session was open must appear here too. One lightweight GET per 5s
     * (the old per-tick SSE resubscribe targeted a route the gateway never
     * served — a 404 storm). Own durable runs never rely on this: the
     * RunController pushes their state directly. */
    fun startResponsePolling() {
        pollJob?.cancel()
        val sid = _sessionId.value ?: return
        pollJob = viewModelScope.launch {
            var tick = 0
            while (true) {
                kotlinx.coroutines.delay(5_000)
                tick++
                // ~20s: pull the server-side live context fill (catches
                // compression rotations / other-surface turns while away).
                if (tick % 4 == 0 && !_isStreaming.value) {
                    val live = try {
                        repository.fetchContextUsage(sid)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) { 0L }
                    if (live > 0) _contextUsed.value = live
                    if (_contextTotal.value == 0L) refreshContextTotal()
                }
                // Skip the transcript poll while a durable run owns this
                // session — its placeholder would fight the live text.
                if (repository.runController.isBusy(sid)) continue
                val changed = try {
                    repository.pollServerResponse(sid)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (changed) {
                    _streamingContent.value = ""
                    _messages.value = repository.resumeSession(sid)
                }
            }
        }
    }

    fun stopResponsePolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Last safety net for the push SSE + poll loop: the composable's
     * onDispose handles the normal path, but any VM cleared without
     * composition-dispose (config edge cases) would otherwise leak an
     * open socket that the 5s resubscription loop keeps resurrecting. */
    override fun onCleared() {
        stopResponsePolling()
        // NOTE: the live turn is NOT cancelled here — RunController owns it
        // in an app-level scope; the run keeps executing server-side and the
        // next visit to this session re-attaches to its events.
        super.onCleared()
    }

    /** Top-bar "+": start a fresh session and switch to it — same as the
     * Home "New chat" card. (Was: cleared the CURRENT session's local
     * messages, which looked like a no-op in Sessions and destroyed chat
     * history on a mis-tap.) A live durable run on the old session keeps
     * running there; returning via Sessions re-attaches to it. */
    fun newChat() {
        viewModelScope.launch { createNewSession() }
    }

    // ── Model Management ──

    fun loadModels() {
        val sid = _sessionId.value ?: return
        viewModelScope.launch {
            _modelsLoading.value = true
            try {
                // Grouped inventory first (same source as the Hermes picker),
                // legacy alias list only as fallback. Default = server's
                // current; stale saved ids reset. No hardcoded model names.
                val response = repository.fetchModelOptions() ?: repository.listModels()
                if (response != null) {
                    val models = response.models.filter { it.id.isNotBlank() }
                    _availableModels.value = models
                    // Priority: THIS session's saved pick (even if missing
                    // from the catalog — per-session choice must not be
                    // silently replaced) > server default > previous value.
                    val saved = repository.savedModelForSession(sid)
                    if (saved != null) _currentProviderSlug.value = repository.savedModelSlugForSession(sid)
                    val serverDefault = response.current.takeIf { c -> models.any { it.id == c } }
                    val keepCurrent = _currentModel.value.takeIf { c -> models.any { it.id == c } }
                    val currentModel = saved ?: keepCurrent ?: serverDefault ?: models.firstOrNull()?.id ?: ""
                    _currentModel.value = currentModel
                    val found = models.firstOrNull { it.id == currentModel }
                    _selectedModelName.value = found?.name ?: currentModel.substringAfterLast("/").take(20)
                    _selectedModelProvider.value = found?.provider ?: ""
                    // Slug follows whatever was chosen (saved restore set it above).
                    if (saved == null) found?.providerSlug?.let { _currentProviderSlug.value = it }
                    refreshContextTotal()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { }
            _modelsLoading.value = false
        }
    }

    fun switchModel(modelId: String, providerSlug: String = "", global: Boolean = false) {
        val sid = _sessionId.value ?: return
        viewModelScope.launch {
            val success = repository.switchModel(sid, modelId, global)
            if (success) {
                pinModel(sid, modelId, providerSlug)
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
        // Double-tap guard: an attachment upload takes seconds (2.7 MB PDF
        // over Tailscale ~10s). _isStreaming only flips AFTER the upload, so
        // every tap during upload queued another identical message — the
        // "4-5 copies" bug. In-flight flag + immediate UI clear close the gap.
        private var sendInFlight = false
        fun sendWithAttachment(
            text: String,
            attachment: PendingAttachment?,
            context: android.content.Context,
            onAttachComplete: () -> Unit,
            replyTo: Message? = null,
            steer: Boolean = false
        ) {
            val sid = _sessionId.value ?: return
            if (sendInFlight) return
            sendInFlight = true
            // Clear the composer BEFORE the slow upload starts: the send
            // slot falls back to the mic and a second tap has nothing to
            // resend. (Was: cleared only in onAttachComplete AFTER upload.)
            onAttachComplete()
            viewModelScope.launch {
                try {
                    var attachUrl: String? = null
                    var attachType: String? = null
                    var attachPath: String = ""
                    if (attachment != null) {
                        // Server truth: aiohttp client_max_size on the api_server
                        // app rejects ANY body > 10 MB with 413 body_too_large
                        // before the upload route runs — so 10 MB is the real
                        // cap, not the route's own 25 MB constant.
                        var tempFile: java.io.File? = null
                        try {
                            tempFile = cacheAttachmentToTemp(context, attachment.uri)
                            if (tempFile == null) {
                                _errorMessage.value = "Attachment too large or unreadable (max 9 MB — gateway limit)"
                                return@launch
                            }
                            repository.uploadFile(
                                tempFile!!, attachment.fileName, attachment.mimeType, sid
                            )?.let { (url, path) ->
                                attachUrl = url
                                attachPath = path
                                attachType = attachment.attachType
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            _errorMessage.value = "Upload failed — check the connection and try again"
                        } finally {
                            // The cacheDir copy must vanish even when the
                            // upload throws or the scope is cancelled.
                            tempFile?.delete()
                        }
                    }
                    if (text.isNotBlank() || attachUrl != null) {
                        sendMessage(text, attachUrl, attachType, replyTo = replyTo, attachmentPath = attachPath, steer = steer)
                    }
                } finally {
                    sendInFlight = false
                }
            }
        }

        /** Send an image the user annotated in the markup editor: upload the
         * flattened PNG, then send it as a normal image attachment. */
        fun sendMarkedImage(text: String, file: java.io.File, replyTo: Message?) {
            val sid = _sessionId.value ?: return
            if (sendInFlight) { file.delete(); return }
            sendInFlight = true
            viewModelScope.launch {
                try {
                    var attachPath: String = ""
                    val url = try {
                        repository.uploadFile(file, file.name, "image/png", sid)?.let { (u, p) ->
                            attachPath = p; u
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _errorMessage.value = "Upload failed — check the connection and try again"
                        null
                    } finally {
                        file.delete()
                    }
                    if (url != null) {
                        sendMessage(text, url, "image", replyTo = replyTo, attachmentPath = attachPath)
                    }
                } finally {
                    sendInFlight = false
                }
            }
        }

        // ── Telegram-style message actions ──
        // Snackbar + Undo — matches session delete UX. A long-press menu
        // or left-swipe delete is destructive, so the row comes back when
        // the user taps Undo (exact same Message, same id via REPLACE).
        private val _lastDeletedMessage = MutableStateFlow<Message?>(null)
        val lastDeletedMessage: StateFlow<Message?> = _lastDeletedMessage.asStateFlow()

        fun deleteMessage(message: Message) {
            viewModelScope.launch {
                repository.deleteMessage(message.sessionId, message.id)
                _messages.value = _messages.value.filterNot { it.id == message.id }
                _lastDeletedMessage.value = message
            }
        }

        /** Edit a user message (local only). */
        fun editMessage(message: Message, newContent: String) {
            viewModelScope.launch {
                repository.editMessage(message.id, newContent)
                _messages.value = _messages.value.map { msg ->
                    if (msg.id == message.id) msg.copy(content = newContent, editedAt = System.currentTimeMillis())
                    else msg
                }
            }
        }

        fun restoreLastDeletedMessage() {
            val msg = _lastDeletedMessage.value ?: return
            _lastDeletedMessage.value = null
            viewModelScope.launch {
                repository.restoreMessage(msg)
                _messages.value = repository.resumeSession(msg.sessionId).sortedBy { it.timestamp }
            }
        }

        /** Telegram-style tap-to-open: download the attachment bytes (Bearer
         * attached), save to the device (MediaStore gallery for images,
         * Downloads otherwise), then launch the system viewer. */
        fun openAttachment(context: android.content.Context, message: Message) {
            val url = message.attachmentUrl ?: return
            viewModelScope.launch {
                val bytes = repository.downloadAttachment(url) ?: run {
                    _errorMessage.value = "Download failed"
                    return@launch
                }
                val name = message.attachmentName
                    ?: url.substringAfterLast('/').ifBlank { "attachment" }
                val mime = message.attachmentType?.let { t ->
                    if (t.startsWith("image")) "image/*" else t
                } ?: "*/*"
                try {
                    // ── Save to the device (MediaStore — API 29+ friendly) ──
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                    }
                    val collection = if (message.attachmentType?.startsWith("image") == true)
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    else
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    val uri = context.contentResolver.insert(collection, values)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(bytes)
                        }
                        // ── Open with the system viewer ──
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(intent) }
                            .onFailure { _errorMessage.value = "Saved — open the file from Downloads" }
                        return@launch
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // MediaStore insert failed — fall back to app cache + FileProvider
                }
                // ── Fallback: cache file + FileProvider share intent ──
                try {
                    val dir = java.io.File(context.cacheDir, "attachments").apply { mkdirs() }
                    val f = java.io.File(dir, name)
                    f.writeBytes(bytes)
                    val fpUri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", f)
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(fpUri, mime)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(intent) }
                        .onFailure { _errorMessage.value = "Saved to cache" }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _errorMessage.value = "Could not open attachment"
                }
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
            val source = repository.getSessionTitle(message.sessionId)
            val label = "Forwarded from" + (source?.takeIf { it.isNotBlank() }?.let { " \"$it\"" } ?: " another chat")
            // Same durable engine as a typed message (the legacy synchronous
            // forwardMessage path is gone — one turn path, one recovery story).
            // The label rides INSIDE the query (not replyTo — that would
            // render a fake quote chip): the agent sees it as import context.
            repository.runController.startTurn(
                sessionId = targetSessionId,
                query = label + "\n\n" + message.content,
                model = repository.savedModelForSession(targetSessionId).orEmpty(),
                provider = repository.savedModelSlugForSession(targetSessionId).takeIf { it.isNotBlank() },
                attachmentUrl = message.attachmentUrl ?: "",
                attachType = message.attachmentType ?: "",
            )
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
                                // The gateway 413s any body over 10 MB
                                // (aiohttp client_max_size); 9 MB leaves
                                // headroom for multipart framing overhead.
                                if (total > 9L * 1024 * 1024) return@use false
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                tempFile.delete()
                null
            }
        }
    }
// ═══════════════════════════════════════════════════════════════
// Screen composable
// ═══════════════════════════════════════════════════════════════

/** Compact token formatter for the context meter: 1234567 -> "1.2M". */
private fun meterFmt(tokens: Long): String = when {
    tokens < 1_000 -> tokens.toString()
    tokens < 1_000_000 -> String.format(java.util.Locale.US, "%.1fk", tokens / 1000.0).replace(".0k", "k")
    else -> String.format(java.util.Locale.US, "%.1fM", tokens / 1_000_000.0).replace(".0M", "M")
}


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
    val haptic = LocalHapticFeedback.current
    val vm: ChatViewModel = hiltViewModel()

    // Kill any in-flight dictation recognizer when the screen leaves —
    // otherwise the mic service keeps running (a leak).
    DisposableEffect(Unit) {
        onDispose {
            stopActiveDictation()
            // Stop the 5s response poller when leaving the chat.
            vm.stopResponsePolling()
        }
    }

    val messages by vm.messages.collectAsState()
    val streamingContent by vm.streamingContent.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val serverCommands by vm.serverCommands.collectAsState()
    val queuedIds by vm.queuedIds.collectAsState()
    val connectionStatus by vm.connectionStatus.collectAsState()
    val toolCalls by vm.toolCalls.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()
    val chatHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val sessionIdState by vm.sessionId.collectAsState()
    val showEmojiPicker by vm.showEmojiPicker.collectAsState()
    val currentModel by vm.currentModel.collectAsState()
    val availableModels by vm.availableModels.collectAsState()
    val modelsLoading by vm.modelsLoading.collectAsState()
    val selectedModelName by vm.selectedModelName.collectAsState()
    val selectedModelProvider by vm.selectedModelProvider.collectAsState()
    val contextUsed by vm.contextUsed.collectAsState()
    val contextTotal by vm.contextTotal.collectAsState()
    val pendingApproval by vm.pendingApproval.collectAsState()
    val turnStatusNote by vm.turnStatusNote.collectAsState()
    // Telegram-parity send-while-running: always QUEUE (no mode chips).
    // Steer stays available to power users as the /steer slash command.
    // User-tunable chat text size (Preferences slider; default 15sp).
    val chatFontSp by vm.chatFontSp.collectAsState()

    // ── Telegram-style delete snackbar (same UX as session delete:
    //    destructive actions get an Undo, never instant removal) ──
    val snackbarHostState = remember { SnackbarHostState() }
    val lastDeletedMsg by vm.lastDeletedMessage.collectAsState()
    // Reply landed / run failed — Telegram-style completion + error buzzes.
    LaunchedEffect(errorMessage) {
        if (errorMessage != null)
            com.hermes.mobile.ui.haptics.Haptics.error(chatHaptics, context)
    }
    var lastStreaming by remember { mutableStateOf(false) }
    LaunchedEffect(isStreaming) {
        val wasStreaming = lastStreaming
        lastStreaming = isStreaming
        if (wasStreaming && !isStreaming)
            com.hermes.mobile.ui.haptics.Haptics.success(chatHaptics, context)
    }
    LaunchedEffect(lastDeletedMsg?.id) {
        val msg = lastDeletedMsg ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Message deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            vm.restoreLastDeletedMessage()
        }
    }

    var inputText by remember { mutableStateOf("") }
    var pendingAttachment by remember { mutableStateOf<PendingAttachment?>(null) }
    // Image awaiting annotation in the markup editor (Cursor-style visual
    // direction — draw on the photo before the agent sees it).
    var markupTarget by remember { mutableStateOf<PendingAttachment?>(null) }
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
    // /model (no args) from the slash executor raises the sheet via this flow.
    LaunchedEffect(Unit) { vm.showModelPickerState.collect { if (it) { showModelPicker = true; vm.consumeModelPickerRequest() } } }
    // ── Telegram-style interactions ──
    var pendingReply by remember { mutableStateOf<Message?>(null) }
    var menuTarget by remember { mutableStateOf<Message?>(null) }
    // ── Edit mode: editing a user message ──
    var editingMessageId by remember { mutableStateOf<Long?>(null) }
    // ── Telegram-style selection mode (batch copy/delete) ──
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    androidx.activity.compose.BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedIds = emptySet()
    }
    var showAttachSheet by remember { mutableStateOf(false) }
    // In-chat file preview sheet (md/html/pdf/csv/json/code/xlsx/docx/pptx)
    var previewTarget by remember { mutableStateOf<Message?>(null) }
    var forwardTarget by remember { mutableStateOf<Message?>(null) }
    // ── Search jump-to + highlight ──
    var highlightId by remember { mutableStateOf<Long?>(null) }
    var searchIndex by remember { mutableStateOf(0) }
    // ── Full-screen image viewer (Telegram style) ──
    var showImageViewer by remember { mutableStateOf<String?>(null) }
    var imageViewerUrl by remember { mutableStateOf<String?>(null) }

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

    // Initialise session — read pending session once on creation. The id
    // is consumed here; the sign-out gate clears it so it can never leak
    // into another account's session.
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
    // Two further bounce guards (video-verified: list yanked back mid-drag):
    // - isStreaming is NOT a key: the stream-end toggle used to re-fire the
    //   effect and hard-jump to item 0 while the user was still touching.
    // - isScrollInProgress: never yank while a gesture/fling is running —
    //   userScrolledAway alone flutters as the index crosses 0..2 mid-scroll.
    LaunchedEffect(messages.size, userScrolledAway) {
        if (messages.isNotEmpty() && !userScrolledAway && !listState.isScrollInProgress) {
            listState.scrollToItem(0)
        }
    }

    // Load models when session ID is available + restore the draft
    LaunchedEffect(sessionIdState) {
        if (sessionIdState != null) {
            vm.loadModels()
            DraftStore.init(context)
            inputText = DraftStore.get(sessionIdState!!)
            // Start the 5s server-response poller — the session always
            // catches up with responses the stream missed.
            vm.startResponsePolling()
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
                // Hermes logo — the golden Caduceus (circle‑clipped)
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
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            // Telegram-style: while the agent is thinking
                            // (streaming, no token yet) the subtitle becomes
                            // an animated "thinking…" instead of the model —
                            // or the live provider note (rate-limit wait,
                            // retry countdown) so a long backoff never reads
                            // as a dead chat.
                            text = if (isStreaming && streamingContent.isBlank())
                                turnStatusNote?.takeIf { it.isNotBlank() } ?: ThinkingSubtitle()
                            else
                                selectedModelName,
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
                // Swarm mode toggle for THIS session (icon-only, like the
                // command pill — colored when ON)
                val swarmOn by vm.swarmEnabled.collectAsState()
                IconButton(onClick = { vm.setSwarmEnabled(!swarmOn) }) {
                    Icon(
                        imageVector = Icons.Filled.Hive,
                        contentDescription = if (swarmOn) "Swarm mode ON" else "Swarm mode OFF",
                        tint = if (swarmOn) HermesPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Search
                IconButton(onClick = { showSearch = !showSearch; searchQuery = "" }) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = if (showSearch) HermesPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // New chat — creates + opens a fresh session
                IconButton(onClick = { vm.newChat() }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "New chat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Memoized display list (newest-first for the inverted LazyColumn).
        // Full O(n) filter+reverse only re-runs when the message list,
        // search query or LIVE streaming content changes (streaming emits
        // ~20Hz of recompositions). Hoisted so the search bar can jump to
        // matches too.
        val displayMessages = remember(messages, searchQuery, streamingContent) {
            filteredMessages(searchQuery, messages)
                .asReversed()
                .filter {
                    // Telegram behavior: while the agent is "thinking"
                    // (streaming, no content yet) there is NO bubble in the
                    // list — the header's "thinking…" subtitle is the only
                    // indicator. The bubble appears the moment content
                    // streams. Blank non-streaming rows stay filtered out.
                    it.role != MessageRole.ASSISTANT ||
                        (it.isStreaming && streamingContent.isNotBlank()) ||
                        (!it.isStreaming && it.content.isNotBlank())
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

        // ── Telegram-style selection action bar (replaces the top bar
        //    actions while in selection mode) ──
        if (selectionMode) {
            val clipboardContext = context
            SelectionActionBar(
                count = selectedIds.size,
                total = displayMessages.size,
                onSelectAll = {
                    selectedIds = if (selectedIds.size == displayMessages.size)
                        emptySet() else displayMessages.map { it.id }.toSet()
                },
                onCopy = {
                    val cm = clipboardContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val text = displayMessages
                        .asReversed()
                        .filter { it.id in selectedIds }
                        .joinToString("\n\n") { it.content.ifBlank { "(attachment)" } }
                    if (text.isNotBlank()) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("selected messages", text))
                    }
                    selectionMode = false
                    selectedIds = emptySet()
                },
                onDelete = {
                    val targets = displayMessages.filter { it.id in selectedIds }
                    targets.forEach { msg ->
                        if (pendingReply?.id == msg.id) pendingReply = null
                        vm.deleteMessage(msg)
                    }
                    selectionMode = false
                    selectedIds = emptySet()
                },
                onExit = {
                    selectionMode = false
                    selectedIds = emptySet()
                }
            )
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
            // Error banner as an OVERLAY (aligned top, floating above the
            // messages) — as a Column child it PUSHED the whole chat area
            // down/up each time a dictation error appeared and cleared,
            // which the user saw as the list "bouncing" on every mic tap.
            errorMessage?.let { err ->
                // Auto-dismiss: transient voice/dictation errors must not
                // linger as a banner.
                LaunchedEffect(err) {
                    kotlinx.coroutines.delay(4000)
                    vm.dismissError()
                }
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    action = { TextButton(onClick = { vm.dismissError() }) { Text("Dismiss") } },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) { Text(err) }
            }
            // Faded Hermes watermark — same on every screen (shared component)
            HermesWatermark()
            if (messages.isEmpty() && !isStreaming) {
                EmptyChatState(
                    onSuggestion = { suggestion -> vm.sendMessage(suggestion) }
                )
            } else {
                // Overscroll bounce/glow mirrors oddly on the reversed list —
                // disable it (clean Telegram feel, no rubber-band at the ends).
                CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                LazyColumn(
                    state = listState,
                    // Telegram-style bottom anchoring via the NATIVE reversed
                    // list (reverseLayout = true) — exactly how DrKLO's
                    // RecyclerView anchors chats. The old "inverted list"
                    // trick (whole list flipped + each item flipped back via
                    // graphicsLayer) cost two GPU flips per visible item per
                    // frame and flipped the touch space under the gesture
                    // detectors — that was the scroll "hooking" feel.
                    // reverseLayout puts item 0 at the BOTTOM edge natively;
                    // short content is forced to the bottom; history grows
                    // upward. No per-item flips, no flipped hit-testing.
                    reverseLayout = true,
                    modifier = Modifier
                        .fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 8.dp,
                        end = 8.dp,
                        top = 2.dp,
                        bottom = 2.dp
                    )
                ) {
                    // Reversed list: FIRST declared items render at the
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
                            // Inline typing pulse REMOVED — the header now
                            // shows the animated "thinking…" subtitle
                            // (Telegram's top-bar placement). The list never
                            // shifts during streaming; tool cards remain.
                            // The Stop control lives in the composer slot
                            // (send button → Stop while streaming).
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                toolCalls.forEach { toolCall -> ToolCallCard(toolCall = toolCall) }
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
                        // Telegram's exact rule: same sender AND gap ≤ 5 min.
                        val prevMsg = displayMessages.getOrNull(index - 1)
                        val nextMsg = displayMessages.getOrNull(index + 1)
                        val isGroupStart = nextMsg == null ||
                            nextRole != message.role ||
                            (message.timestamp - nextMsg.timestamp) > GROUP_WINDOW_MS
                        val isGroupEnd = prevMsg == null ||
                            prevRole != message.role ||
                            (prevMsg.timestamp - message.timestamp) > GROUP_WINDOW_MS
                        // Telegram-style date separator: a "Today" /
                        // "Yesterday" / "12 Aug" pill above the FIRST message
                        // of a new day (day differs from the older neighbor).
                        val olderTs = displayMessages.getOrNull(index + 1)?.timestamp
                        val newDay = olderTs != null && !isSameDay(olderTs, message.timestamp)
                        val dateLabel = remember(message.timestamp) { datePillLabel(message.timestamp) }
                        // reverseLayout: index grows UPWARD, so the gap toward
                        // the OLDER neighbor (above) is this item's TOP
                        // padding — tight 1dp inside a group, 6dp between
                        // groups (replaces the old uniform spacedBy(2.dp)).
                        Column(
                            Modifier.padding(top = if (isGroupStart) 6.dp else 1.dp)
                        ) {
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
                                onImageTap = { url ->
                                    imageViewerUrl = url
                                    showImageViewer = url
                                },
                                onStop = if (
                                    message.role == MessageRole.USER &&
                                    (
                                        // The ACTIVE request: newest user
                                        // message while a stream runs.
                                        (isStreaming &&
                                            messages.lastOrNull { it.role == MessageRole.USER }?.id == message.id) ||
                                        // A QUEUED message: waiting in the
                                        // FIFO — stop = remove from queue.
                                        queuedIds.contains(message.id)
                                        )
                                ) {
                                    {
                                        if (queuedIds.contains(message.id)) {
                                            vm.stopQueuedMessage(message.id)
                                        } else {
                                            vm.stopStreaming()
                                        }
                                    }
                                } else null,
                                selectionMode = selectionMode,
                                selected = message.id in selectedIds,
                                onToggleSelect = if (selectionMode) {
                                    {
                                        selectedIds = if (message.id in selectedIds)
                                            selectedIds - message.id else selectedIds + message.id
                                    }
                                } else null,
                                onEdit = if (message.role == MessageRole.USER && !isStreamingThis) {
                                    {
                                        // Enter edit mode: populate input with message content
                                        editingMessageId = message.id
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
                                highlighted = message.id == highlightId,
                                fontSizeSp = chatFontSp,
                                onMenu = { menuTarget = message },
                                onDownloadAttachment = { msg ->
                                    // Download icon: save to Downloads + open.
                                    scope.launch { vm.openAttachment(context, msg) }
                                },
                                onAttachmentTap = { msg ->
                                    // Images → fullscreen viewer (Telegram-style).
                                    // Everything previewable → in-app sheet; the
                                    // sheet's header keeps Open externally/Share,
                                    // and unpreviewable types fall back there.
                                    val t = msg.attachmentType.orEmpty()
                                    val n = (msg.attachmentName ?: msg.attachmentUrl.orEmpty()).lowercase()
                                    if (t.startsWith("image/") || Regex("\\.(png|jpg|jpeg|gif|webp|bmp)$").containsMatchIn(n)) {
                                        // In-app fullscreen viewer (Telegram-style),
                                        // not the gallery hand-off.
                                        msg.attachmentUrl?.let { url ->
                                            imageViewerUrl = url
                                            showImageViewer = url
                                        }
                                    } else {
                                        previewTarget = msg
                                    }
                                }
                            )
                            // Full-width table overlay for assistant messages
                            FullWidthTableOverlay(
                                message = message,
                                displayContent = displayContent,
                                isStreaming = isStreamingThis,
                                isDark = LocalDarkTheme.current
                            )
                        } // Column (date pill + bubble) — reverseLayout, no flip
                    }
                }
            }
            } // CompositionLocalProvider (overscroll off)

            // ── Telegram-style scroll-to-bottom FAB with unread counter ──
            // Visible only while scrolled up; shows how much NEW content
            // arrived while away from the bottom; tap = fast animated return.
            // atBottom reuses the existing userScrolledAway flag
            // (firstVisibleItemIndex-based) — a viewport shrink (keyboard
            // opening) must NOT fake a scroll-away + phantom counter.
            val atBottom = !userScrolledAway
            val totalItems = displayMessages.size + (if (isStreaming) 1 else 0)
            var knownAtBottom by remember { mutableStateOf(totalItems) }
            LaunchedEffect(atBottom, totalItems) {
                if (atBottom) knownAtBottom = totalItems
            }
            val newCount = (totalItems - knownAtBottom).coerceAtLeast(0)
            androidx.compose.animation.AnimatedVisibility(
                visible = !atBottom && totalItems > 0,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                FloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier
                        .padding(end = 10.dp, bottom = 6.dp)
                        .size(40.dp),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = HermesPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(6.dp)
                ) {
                    Box {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Scroll to latest",
                            modifier = Modifier.size(22.dp)
                        )
                        if (newCount > 0) {
                            Text(
                                text = "$newCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 2.dp, y = (-2).dp)
                                    .background(
                                        HermesPrimary,
                                        CircleShape
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── Telegram-style delete confirmation (Undo snackbar) ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            SnackbarHost(hostState = snackbarHostState)
        }

        // ── Telegram-style reply bar (quote above the input) ──
        pendingReply?.let { replyMsg ->
            ReplyBar(
                message = replyMsg,
                onCancel = { pendingReply = null }
            )
        }

        // ── Edit mode bar ──
        editingMessageId?.let { msgId ->
            val editMsg = messages.find { it.id == msgId }
            if (editMsg != null) {
                EditBar(
                    message = editMsg,
                    onCancel = { editingMessageId = null; inputText = "" },
                    onSend = {
                        val trimmed = inputText.trim()
                        if (trimmed.isNotBlank()) {
                            vm.editMessage(editMsg, trimmed)
                            editingMessageId = null
                            inputText = ""
                        }
                    }
                )
            } else {
                editingMessageId = null
                inputText = ""
            }
        }

        // ── Context meter (live tokens-in-context / model window) ──
        // Telegram-slim: 2dp hairline + 10sp caption, sits between the
        // reply bar and the input; fades in only once real numbers exist.
        androidx.compose.animation.AnimatedVisibility(
            visible = contextUsed > 0 && contextTotal > 0,
            enter = fadeIn(), exit = fadeOut()
        ) {
            val fraction = (contextUsed.toFloat() / contextTotal.toFloat()).coerceIn(0f, 1f)
            // Traffic bands keyed to the server's OWN behaviour: the Hermes
            // compressor fires at ~50% of the window, so green = plenty of
            // headroom, yellow = compression zone, red = near the limit.
            val warn = fraction > 0.85f
            val meterColor = when {
                fraction >= 0.80f -> MaterialTheme.colorScheme.error
                fraction >= 0.50f -> Color(0xFFF9A825)
                else -> Color(0xFF43A047)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp)),
                    color = meterColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    maxLines = 1,
                    lineHeight = 14.sp,
                    text = "${meterFmt(contextUsed)} / ${meterFmt(contextTotal)}" +
                        if (warn) " · auto-compress soon"
                        else if (fraction >= 0.5f) " · auto-compress zone"
                        else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = meterColor.copy(alpha = 0.9f),
                )
            }
        }

        // ── Approval card (durable runs): a tool call is waiting for the
        //    user's decision. Telegram renders inline buttons for exactly
        //    this; the card rides above the composer so it can't be missed.
        pendingApproval?.let { approval ->
            ApprovalCard(
                approval = approval,
                onResolve = { choice -> vm.resolveApproval(choice) },
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
                haptic.performHapticFeedback(
                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
                )
                // Use ViewModel scope so cancellation doesn't lose messages
                vm.sendWithAttachment(inputText.trim(), pendingAttachment, context, onAttachComplete = {
                    pendingAttachment = null
                    inputText = ""
                }, replyTo = pendingReply)
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
            onMarkup = { pendingAttachment?.let { markupTarget = it } },
            isStreaming = isStreaming,
            showEmojiPicker = showEmojiPicker,
            onToggleEmojiPicker = { vm.toggleEmojiPicker() },
            enabled = sessionIdState != null,
            serverCommands = serverCommands
        )

        // ── Model Picker Bottom Sheet ──
        if (showModelPicker) {
            ModelPickerSheet(
                availableModels = availableModels,
                currentModel = currentModel,
                currentProviderSlug = vm.currentProviderSlug,
                modelsLoading = modelsLoading,
                onSelect = { modelId, slug, global -> vm.switchModel(modelId, slug, global = global) },
                onDismiss = { showModelPicker = false }
            )
        }

        // ── Markup editor (draw on the image before sending) ──
        markupTarget?.let { target ->
            com.hermes.mobile.ui.components.MarkupEditorDialog(
                attachment = target,
                onDismiss = { markupTarget = null },
                onSend = { flattenedFile ->
                    markupTarget = null
                    pendingAttachment = null
                    vm.sendMarkedImage(inputText.trim(), flattenedFile, pendingReply)
                }
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

        // ── In-chat file preview sheet (md/html/pdf/csv/json/code/office) ──
        previewTarget?.let { target ->
            FilePreviewSheet(
                message = target,
                repository = vm.repository(),
                onDismiss = { previewTarget = null },
                onOpenExternal = {
                    previewTarget = null
                    scope.launch { vm.openAttachment(context, target) }
                },
            )
        }

        // ── Full-screen image viewer (Telegram style) ──
        if (showImageViewer != null && imageViewerUrl != null) {
            ImageViewerDialog(
                url = imageViewerUrl!!,
                onDismiss = { showImageViewer = null; imageViewerUrl = null }
            )
        }

        // ── Telegram-style message action sheet (long-press menu) ──
        menuTarget?.let { target ->
            val contextForClipboard = context
            MessageActionSheet(
                message = target,
                onSelect = {
                    selectionMode = true
                    selectedIds = setOf(target.id)
                    menuTarget = null
                },
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
                onEdit = if (target.role == com.hermes.mobile.data.model.MessageRole.USER
                    && target.content.isNotBlank()) {
                    {
                        editingMessageId = target.id
                        inputText = target.content
                        menuTarget = null
                    }
                } else null,
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
                onFork = {
                    menuTarget = null
                    vm.forkSession { newId ->
                        if (newId != null) vm.initSession(newId)
                        else scope.launch { snackbarHostState.showSnackbar("Fork failed — check the connection") }
                    }
                },
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        onError("Voice error: ${e.message}")
    }
}

// ═══════════════════════════════════════════════════════════════
// Connection status bar
// ═══════════════════════════════════════════════════════════════

/**
 * Telegram-style selection action bar: "N selected" + Select-all / Copy /
 * Delete / close. Rendered under the header while selection mode is on.
 */
@Composable
private fun SelectionActionBar(
    count: Int,
    total: Int,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = HermesPrimary.copy(alpha = 0.08f),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (count == 0) "Select messages" else "$count selected",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onSelectAll) {
                Icon(
                    imageVector = if (count == total) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                    contentDescription = "Select all",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onCopy, enabled = count > 0) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy",
                    tint = if (count > 0) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete, enabled = count > 0) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = if (count > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onExit) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Exit selection",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun ConnectionStatusBar(connectionStatus: ConnectionStatus) {
    // Telegram-style debounced state machine: a connection blip (Tailscale
    // drop that recovers in <400ms) must NOT flash the red banner. The
    // transient state only shows once the status has been non-connected
    // for the debounce window (LaunchedEffect restarts on every change, so
    // a quick recovery cancels the pending show).
    var displayStatus by remember { mutableStateOf(connectionStatus) }
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.CONNECTED) {
            displayStatus = ConnectionStatus.CONNECTED
        } else {
            kotlinx.coroutines.delay(400)
            displayStatus = connectionStatus
        }
    }
    // Soft transient wording: a drop after a live connection is
    // "Reconnecting…" (amber, the VM polls every 5s to recover) — only a
    // hard error goes red. No alarm on recoverable blips.
    val (text, color, icon) = when (displayStatus) {
        ConnectionStatus.CONNECTED -> Triple("Connected", SuccessGreen, Icons.Filled.CheckCircle)
        ConnectionStatus.CONNECTING -> Triple("Connecting…", WarningAmber, Icons.Filled.Sync)
        ConnectionStatus.DISCONNECTED -> Triple("Reconnecting…", WarningAmber, Icons.Filled.Sync)
        ConnectionStatus.ERROR -> Triple("Connection lost — retrying", ErrorRed, Icons.Filled.Error)
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

/** Telegram-style edit bar (above input when editing a message). */
@Composable
private fun EditBar(message: Message, onCancel: () -> Unit, onSend: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(32.dp)
                .background(HermesPrimary, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Editing your message",
                style = MaterialTheme.typography.labelSmall,
                color = HermesPrimary,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Tap send to save changes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Cancel",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        FilledIconButton(
            onClick = onSend,
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = HermesPrimary)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

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

/** Telegram's grouping window: same sender + gap ≤ 5 min = one cluster. */
private const val GROUP_WINDOW_MS = 5 * 60 * 1000L

private fun datePillLabel(timestamp: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val now = java.util.Calendar.getInstance()
    if (isSameDay(cal.timeInMillis, now.timeInMillis)) return "Today"
    now.add(java.util.Calendar.DAY_OF_YEAR, -1)
    if (isSameDay(cal.timeInMillis, now.timeInMillis)) return "Yesterday"
    return java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
}

/** Format timestamp as HH:MM for display under bubbles. */
private fun formatTime(timestamp: Long): String {
    return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
}

// ═══════════════════════════════════════════════════════════════
// Message bubble
// ═══════════════════════════════════════════════════════════════

/**
 * Telegram-style animated "thinking…" subtitle (three pulsing dots).
 */
@Composable
private fun ThinkingSubtitle(): String {
    val dots by rememberInfiniteTransition(label = "thinking").animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dots"
    )
    return "thinking" + ".".repeat(dots.toInt())
}

/**
 * Telegram-style delivery tick: clock (sending) → ✓ (sent) →
 * blue ✓✓ (read) → red ! (failed).
 */
@Composable
private fun StatusTick(status: MessageStatus, tint: Color) {
    when (status) {
        MessageStatus.SENDING -> CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = tint
        )
        MessageStatus.SENT -> Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = "Sent",
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        MessageStatus.READ -> Icon(
            imageVector = Icons.Filled.DoneAll,
            contentDescription = "Read",
            tint = HermesPrimary,
            modifier = Modifier.size(14.dp)
        )
        MessageStatus.FAILED -> Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp)
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    displayContent: String,
    isStreaming: Boolean,
    baseUrl: String = "",
    onEdit: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    onReact: (() -> Unit)? = null,
    highlighted: Boolean = false,
    fontSizeSp: Float = 15f,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    // Telegram: tap an attachment bubble to open/save the file
    onAttachmentTap: ((Message) -> Unit)? = null,
    // Download icon: save to Downloads + open with system viewer
    onDownloadAttachment: ((Message) -> Unit)? = null,
    // Telegram: tap image to open full-screen viewer
    onImageTap: ((String) -> Unit)? = null
) {
    val isUser = message.role == MessageRole.USER
    val isDark = LocalDarkTheme.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val hapticCtx = androidx.compose.ui.platform.LocalContext.current
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
        topStart = if (isUser) 17.dp else (if (isFirstInGroup && !isLastInGroup) 17.dp else 3.dp),
        topEnd = if (isUser) (if (isFirstInGroup && !isLastInGroup) 17.dp else 3.dp) else 17.dp,
        bottomStart = if (isUser) 17.dp else (if (isLastInGroup) 17.dp else 3.dp),
        bottomEnd = if (isUser) (if (isLastInGroup) 17.dp else 3.dp) else 17.dp
    )
    val textColor = if (isUser) {
        if (isDark) Color.White else Color(0xFF000000)
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    // Build absolute URL for images served by the gateway plugin
    // Response media (Telegram parity): an assistant reply can CARRY media —
    // an inlined `![img](data:...)` from api_server or a trailing
    // `MEDIA:/abs/file.ext` tag (any file; fetched via the plugin's
    // /api/mobile/file route). Resolved at render time only (stored text is
    // never rewritten); a persisted attachment row always wins.
    val responseMedia = remember(displayContent) {
        if (message.role == MessageRole.USER) null
        else com.hermes.mobile.ui.chat.ResponseMedia.extract(displayContent)
    }
    val effAttachmentUrl = message.attachmentUrl ?: responseMedia?.url
    val effAttachmentType = if (message.attachmentUrl != null) message.attachmentType
        else responseMedia?.type
    val effAttachmentName = if (message.attachmentUrl != null) message.attachmentName
        else responseMedia?.name
    val bubbleContent = responseMedia?.cleanText ?: displayContent
    val absoluteImageUrl = remember(effAttachmentUrl, baseUrl) {
        val rel = effAttachmentUrl ?: return@remember null
        if (rel.startsWith("http") || rel.startsWith("data:")) rel
        else baseUrl.trimEnd('/') + rel
    }

    // Telegram-style bubble with the user's full-width preference: wide
    // bubbles (up to ~94% incl. tail/avatar lanes); short texts still hug.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val bubbleMax = this.maxWidth * 0.94f
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // In selection mode: tap toggles selection; long-press and
                // double-tap actions are disabled.
                .combinedClickable(
                    enabled = !isStreaming,
                    onClick = { if (selectionMode) onToggleSelect?.invoke() },
                    onLongClick = if (selectionMode) null else { {
                        com.hermes.mobile.ui.haptics.Haptics.press(haptics)
                        onLongPress?.invoke()
                    } },
                    onDoubleClick = if (selectionMode) null else { {
                        com.hermes.mobile.ui.haptics.Haptics.tick(haptics)
                        onReact?.invoke()
                    } }
                )
                .pointerInput(onReply, onDelete, isStreaming, selectionMode) {
                    if (isStreaming || selectionMode) return@pointerInput
                    // Telegram gestures: swipe LEFT = reply (hint_swipe_reply),
                    // swipe RIGHT = delete. Accumulate the drag (per-event
                    // deltas) and fire once past 90px; vertical scroll is
                    // untouched.
                    var acc = 0f
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        acc += dragAmount
                        if (acc < -90f && onReply != null) {
                            com.hermes.mobile.ui.haptics.Haptics.tick(haptics)
                            onReply()
                            acc = 0f
                        } else if (acc > 90f && onDelete != null) {
                            com.hermes.mobile.ui.haptics.Haptics.press(haptics)
                            onDelete()
                            acc = 0f
                        }
                    }
                },
            horizontalArrangement = alignment
        ) {
            // ── Selection-mode checkbox (Telegram's animated leading
            //    checkbox; dims the bubble while selected) ──
            if (selectionMode) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInHorizontally { -it / 2 },
                    exit = fadeOut() + slideOutHorizontally { -it / 2 }
                ) {
                    val checkColor = if (selected) HermesPrimary
                    else MaterialTheme.colorScheme.outlineVariant
                    Icon(
                        imageVector = if (selected) Icons.Filled.CheckCircleOutline
                        else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = if (selected) "Selected" else "Not selected",
                        tint = checkColor,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(22.dp)
                            .alpha(if (selected) 1f else 0.55f)
                    )
                }
            }
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
            // ⋮ menu — TOP-RIGHT corner overlay (Telegram-style trailing
            // affordance). Opens the full sheet: select/copy/reply/edit/
            // forward/regenerate/delete. Long-press on the bubble does the
            // same, for muscle memory.
            if (!isStreaming && (onMenu != null || onLongPress != null)) {
                IconButton(
                    onClick = onMenu ?: onLongPress!!,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 3.dp, end = 3.dp)
                        .size(18.dp)
                ) {
                    Box(contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Message options",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .widthIn(max = bubbleMax)
                    // Telegram selection: dim the bubble while selected
                    // (checkbox stays bright).
                    .alpha(if (selected) 0.55f else 1f)
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
                Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)) {
                    // ── Telegram-style quote chip (reply preview) ──
                    // Rendered at the top of the replying bubble: accent-tinted
                    // box with the quoted text, max 2 lines.
                    if (message.replyToText?.isNotBlank() == true) {
                        Surface(
                            color = HermesPrimary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp)
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
                    // ── Image attachment (persisted OR carried in the reply text) ──
                    if (absoluteImageUrl != null && effAttachmentType?.startsWith("image") == true) {
                        val isGif = effAttachmentType == "image/gif"
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = if (bubbleContent.isNotBlank()) 8.dp else 0.dp)
                        ) {
                            if (isGif) {
                                // GIF: use Coil with animation enabled
                                AsyncImage(
                                    model = absoluteImageUrl,
                                    contentDescription = effAttachmentName ?: "GIF",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(300.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = !isStreaming) {
                                            onImageTap?.invoke(absoluteImageUrl)
                                        },
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                // Static image
                                AsyncImage(
                                    model = absoluteImageUrl,
                                    contentDescription = effAttachmentName ?: "Image",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 100.dp, max = 400.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = !isStreaming) {
                                            onImageTap?.invoke(absoluteImageUrl)
                                        },
                                    contentScale = ContentScale.FillWidth
                                )
                            }
                        }
                    }
                    // ── File attachment (non-image; persisted OR from a MEDIA tag) ──
                    if (effAttachmentUrl != null && (effAttachmentType == null || !effAttachmentType!!.startsWith("image"))) {
                        // Video support
                        if (effAttachmentType?.startsWith("video") == true) {
                            VideoAttachmentRow(
                                name = effAttachmentName ?: "Video",
                                url = effAttachmentUrl,
                                onClick = { onImageTap?.invoke(effAttachmentUrl) }
                            )
                        } else {
                            FileAttachmentRow(
                                name = effAttachmentName ?: "File",
                                modifier = Modifier.padding(bottom = if (bubbleContent.isNotBlank()) 8.dp else 0.dp),
                                onDownload = if (effAttachmentUrl != null) {
                                    {
                                        val target = if (message.attachmentUrl != null) message
                                        else message.copy(
                                            attachmentUrl = effAttachmentUrl,
                                            attachmentType = effAttachmentType,
                                            attachmentName = effAttachmentName)
                                        onDownloadAttachment?.invoke(target)
                                    }
                                } else null,
                                onClick = {
                                    // Persisted row: full Message flow (save/open).
                                    // Response-carried MEDIA tag: build a transient
                                    // Message so the same download+viewer path runs.
                                    if (message.attachmentUrl != null) onAttachmentTap?.invoke(message)
                                    else onAttachmentTap?.invoke(
                                        message.copy(
                                            attachmentUrl = effAttachmentUrl,
                                            attachmentType = effAttachmentType,
                                            attachmentName = effAttachmentName,
                                        )
                                    )
                                }
                            )
                        }
                    }
                    // ── Grouped tool activity (Telegram: one compact line
                    //    per call, persisted with the bubble) ──
                    if (!isStreaming && !message.toolActivity.isNullOrBlank()) {
                        ToolActivityGroup(jsonLines = message.toolActivity!!)
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    // ── Text content ──
                    if (bubbleContent.isNotBlank()) {
                        // A markdown table gets lifted into the full-width
                        // overlay (wide grids don't fit bubble width) — but
                        // the surrounding prose must STAY in the bubble
                        // (the old code hid all text when a table existed,
                        // so "Here's the comparison:" and the closing note
                        // silently vanished).
                        val table = parseTablesOf(bubbleContent)
                        val bubbleText = table?.prose ?: bubbleContent
                        if (bubbleText.isNotBlank()) {
                            if (isStreaming) {
                                StreamingText(
                                    text = bubbleText,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = fontSizeSp.sp,
                                        lineHeight = (fontSizeSp + 6).sp),
                                    color = textColor,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                MarkdownText(
                                    text = bubbleText,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = fontSizeSp.sp,
                                        lineHeight = (fontSizeSp + 6).sp),
                                    color = textColor,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    // The old blinking cursor bar is REMOVED — the header's
                    // "thinking…" subtitle is the generating indicator now
                    // (Telegram's top-bar placement; no blinking in the list).
                    // Telegram-style delivery tick — always visible on
                    // finished user messages (clock → ✓ → blue ✓✓ / red !).
                    // Legacy rows (pre-status column) default to READ —
                    // past messages were all read, never single-tick.
                    if (isUser && !isStreaming && displayContent.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusTick(
                                status = message.status ?: MessageStatus.READ,
                                tint = textColor.copy(alpha = 0.5f)
                            )
                        }
                    }
                    // Stop stays bottom-right (live action); the ⋮ menu moved
                    // to the bubble's TOP-RIGHT corner overlay.
                    val showStopAction = onStop != null && isUser
                    if (showStopAction) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                                IconButton(
                                    onClick = onStop,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Stop,
                                        contentDescription = "Stop generating",
                                        tint = MaterialTheme.colorScheme.error,
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
                    // ── Telegram-style: timestamp + edit indicator + delivery ticks ──
                    if (!isFirstInGroup || !isLastInGroup) {
                        // Show timestamp for non-grouped or mid-group messages
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (message.editedAt > 0) {
                                Text(
                                    text = "edited",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isUser) Color.White.copy(alpha = 0.45f)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = formatTime(message.timestamp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp)),
                                color = if (isUser) Color.White.copy(alpha = 0.65f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            if (message.isStreaming) {
                                // Tiny streaming indicator
                                Spacer(modifier = Modifier.width(2.dp))
                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .background(HermesPrimary, CircleShape)
                                )
                            }
                            if (isUser && !isStreaming) {
                                // Delivery status ticks (Telegram style)
                                Spacer(modifier = Modifier.width(2.dp))
                                when (message.status) {
                                    MessageStatus.SENDING -> Icon(
                                        Icons.Filled.Schedule,
                                        contentDescription = "Sending",
                                        modifier = Modifier.size(12.dp),
                                        tint = Color.White.copy(alpha = 0.5f)
                                    )
                                    MessageStatus.SENT -> Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "Sent",
                                        modifier = Modifier.size(12.dp),
                                        tint = Color.White.copy(alpha = 0.65f)
                                    )
                                    MessageStatus.READ -> Icon(
                                        Icons.Filled.DoneAll,
                                        contentDescription = "Read",
                                        modifier = Modifier.size(12.dp),
                                        tint = Color(0xFF4FC3F7) // Light blue for read
                                    )
                                    MessageStatus.FAILED -> Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = "Failed",
                                        modifier = Modifier.size(12.dp),
                                        tint = ErrorRed
                                    )
                                    else -> {}
                                }
                            }
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

// ── Full-width table overlay for assistant messages ──
@Composable
fun FullWidthTableOverlay(
    message: Message,
    displayContent: String,
    isStreaming: Boolean,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    if (isStreaming || message.role == MessageRole.USER) return
    val table = parseTablesOf(displayContent) ?: return
    // EVERY table in the message renders (the first parser silently left a
    // second table as raw pipe text); prose is handled by the bubble itself.
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        table.tables.forEach { rows -> MarkdownTable(rows, isDark) }
    }
}

// ── File attachment row ──
@Composable
fun FileAttachmentRow(
    name: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onDownload: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
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
        // Telegram: download affordance on every file bubble — actually saves
        // to Downloads and opens (was decorative-only → "download not working").
        Icon(
            imageVector = Icons.Filled.Download,
            contentDescription = "Download",
            modifier = Modifier.size(28.dp)
                .clip(CircleShape)
                .clickable(enabled = onDownload != null) { onDownload?.invoke() }
                .padding(5.dp)
                .size(18.dp),
            tint = MaterialTheme.colorScheme.primary
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

/** Telegram-style: compact grouped lines for a completed turn's tool calls.
 * Parses the persisted [{n,e,l,s}] JSON; renders nothing when malformed. */
// ── Approval card (durable runs) ─────────────────────────────────────
// A tool call is waiting for the user's decision. Telegram renders inline
// buttons for exactly this; the card shows the COMMAND VERBATIM (the user
// must see what they're approving) + the server's own choice set.
@Composable
fun ApprovalCard(
    approval: ChatViewModel.ApprovalUi,
    onResolve: (String) -> Unit,
) {
    val label = mapOf(
        "once" to "Allow once", "session" to "Allow session",
        "always" to "Always allow", "deny" to "Deny")
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFE5A100).copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("\u26a0\ufe0f", fontSize = 14.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Needs your approval",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (approval.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    approval.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
            if (approval.command.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    approval.command,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(8.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                approval.choices.forEach { choice ->
                    val deny = choice == "deny"
                    Button(
                        onClick = { onResolve(choice) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (deny) MaterialTheme.colorScheme.error
                                             else HermesPrimary),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(label[choice] ?: choice, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun ToolActivityGroup(jsonLines: String) {
    var expanded by remember(jsonLines) { mutableStateOf(false) }
    val lines = remember(jsonLines) {
        try {
            val arr = org.json.JSONArray(jsonLines)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Triple(
                    o.optString("e", "⚙️") + " " + o.optString("n", "tool"),
                    o.optString("l", ""),
                    o.optString("s", "completed")
                )
            }
        } catch (_: Exception) { emptyList() }
    }
    if (lines.isEmpty()) return
    // Bluish, collapsed to the first two calls + "N more" — tap to expand.
    // Tints the whole agent-work trail (tools/MCP/skills) distinctly from
    // the prose answer, like IDE fold regions.
    val shown = if (expanded) lines else lines.take(2)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = HermesPrimary.copy(alpha = 0.07f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, HermesPrimary.copy(alpha = 0.16f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(start = 10.dp, end = 26.dp, top = 4.dp, bottom = 4.dp)) {
            if (!expanded && lines.size > 2) {
                Text(
                    text = "Agent work · ${lines.size} steps",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = HermesPrimary,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            shown.forEach { (head, label, status) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 0.5.dp)
                ) {
                    Text(
                        text = if (status == "failed") "✕" else if (status == "running") "◌" else "✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = when (status) {
                            "failed" -> ErrorRed
                            "running" -> WarningAmber
                            else -> SuccessGreen
                        }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = head,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = HermesPrimary.copy(alpha = 0.95f),
                        maxLines = 1
                    )
                    if (label.isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = HermesPrimary.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
            if (lines.size > 2) {
                Text(
                    text = if (!expanded) "show all ${lines.size} steps" else "collapse",
                    style = MaterialTheme.typography.labelSmall,
                    color = HermesPrimary.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

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
                    fontSize = 12.sp,
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
                        fontSize = 12.sp
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
    onRemove: () -> Unit,
    onMarkup: (() -> Unit)? = null
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
            // Mark up button — only for images (Cursor-style visual direction)
            if (onMarkup != null && attachment.attachType == "image") {
                IconButton(onClick = onMarkup, modifier = Modifier.size(32.dp)) {
                    Text("✏️", fontSize = 16.sp)
                }
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

// Mirrors the app-side command executor (see ChatViewModel.handleSlashCommand).
private val SLASH_COMMANDS = listOf(
    SlashCommand("/new", "Start a fresh conversation"),
    SlashCommand("/reset", "Same as /new"),
    SlashCommand("/clear", "Same as /new"),
    SlashCommand("/stop", "Stop the current response"),
    SlashCommand("/model", "Pick a model (or: /model <name>)"),
    SlashCommand("/status", "Session, model & connection info"),
    SlashCommand("/context", "Token window usage for this chat"),
    SlashCommand("/title", "Rename this session: /title <text>"),
    SlashCommand("/retry", "Resend the last message"),
    SlashCommand("/steer", "Inject into the running reply (else it queues)"),
    SlashCommand("/skills", "Installed Hermes skills (server)"),
    SlashCommand("/tools", "Enabled toolsets (server)"),
    SlashCommand("/usage", "Server token ledger totals"),
    SlashCommand("/queue", "Queue text for the next turn"),
    SlashCommand("/compress", "Context status (compression is automatic)"),
    SlashCommand("/whoami", "Device + server identity"),
    SlashCommand("/version", "App + server + plugin versions"),
    SlashCommand("/commands", "Every command handled client-side"),
    SlashCommand("/help", "List these commands"),
)

@Composable
fun SlashCommandList(
    query: String,
    onCommandSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    serverCommands: List<ServerCommand> = emptyList()
) {
    // Only show when text starts with "/"
    if (!query.startsWith("/") || query.length > 30) {
        onDismiss()
        return
    }

    val filter = query.substring(1).lowercase()
    // Server truth first (Telegram's own menu source); local list fills gaps
    // and provides handlers for commands the app executes itself.
    val localMatches = SLASH_COMMANDS.filter {
        it.command.removePrefix("/").contains(filter)
    }
    val serverMatches = serverCommands.filter {
        it.name.removePrefix("/").lowercase().contains(filter)
    }
    val seen = localMatches.map { it.command.lowercase() }.toSet()
    val matched: List<SlashCommand> = localMatches + serverMatches
        .filter { "/${it.name.lowercase()}" !in seen }
        .map { SlashCommand("/" + it.name.lowercase(), it.description) }

    val capped = matched.take(30)
    if (capped.isEmpty()) {
        onDismiss()
        return
    }

    // Distinct elevated sheet: surfaceContainer tone + solid divider so the
    // list never reads as command text floating over the chat (background and
    // surface were near-identical in light theme → "overlapping" look).
    // Capped at 180dp — more than that crushed the visible history.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        shadowElevation = 10.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        LazyColumn(
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            itemsIndexed(capped) { _, cmd ->
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

@OptIn(ExperimentalMaterial3Api::class)
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
    onMarkup: (() -> Unit)? = null,
    isStreaming: Boolean,
    showEmojiPicker: Boolean,
    onToggleEmojiPicker: () -> Unit,
    enabled: Boolean,
    serverCommands: List<ServerCommand> = emptyList()
) {
    // ── Slash command state ──
    var commandsSheetOpen by remember { mutableStateOf(false) }
    var commandSearch by remember { mutableStateOf("") }
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
                    onDismiss = {},
                    serverCommands = serverCommands
                )
            }

            // Attachment preview (like Telegram: thumbnail + name above text field)
            if (pendingAttachment != null) {
                AttachmentPreview(
                    attachment = pendingAttachment,
                    onRemove = onRemoveAttachment,
                    onMarkup = onMarkup
                )
            }

            // Emoji picker popup (above the bar, like Telegram)
            if (showEmojiPicker) {
                EmojiPickerGrid(onEmojiSelected = onEmoji)
            }

            // Telegram composer: ONE unified rounded pill holds
            // [Menu][emoji][field][attach][mic/send] — no separate
            // inner field pill, no emoji glyph, flat outline icons.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        if (LocalDarkTheme.current) InputBarDark else InputBarLight
                    )
                    .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                    val hasContent = inputText.isNotBlank() || pendingAttachment != null

                    // ── 0. Bot command pill (opens the full command sheet;
                    // typing replaces it with the emoji button) ──
                    if (!hasContent && serverCommands.isNotEmpty()) {
                        Surface(
                            onClick = {
                                commandsSheetOpen = true
                                commandSearch = ""
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = HermesPrimary,
                            enabled = enabled
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                            ) {
                                // Icon-only pill — label removed by user request.
                                Icon(
                                    imageVector = Icons.Outlined.Menu,
                                    contentDescription = "Commands",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(2.dp))
                    }

                    // ── 1. Emoji button (flat outline smiley, Telegram tint) ──
                    IconButton(
                        onClick = onToggleEmojiPicker,
                        modifier = Modifier.size(40.dp),
                        enabled = enabled
                    ) {
                        Icon(
                            imageVector = if (showEmojiPicker) Icons.Outlined.Keyboard
                                          else Icons.Outlined.Mood,
                            contentDescription = "Emoji",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // ── 2. Text field (transparent inside the unified pill) ──
                    TextField(
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

            // Bot commands sheet (icon-only pill opens it; search field filters)
            if (commandsSheetOpen) {
                ModalBottomSheet(
                    onDismissRequest = { commandsSheetOpen = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                ) {
                    val query = commandSearch.trim().lowercase()
                    val matched = remember(serverCommands, query) {
                        if (query.isEmpty()) serverCommands
                        else serverCommands.filter {
                            it.name.lowercase().startsWith(query.removePrefix("/")) ||
                                it.description.lowercase().contains(query)
                        }
                    }
                    Column(Modifier.padding(bottom = 16.dp)) {
                        Text(
                            "Commands",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                        // Find field — the registry is ~200 strong.
                        OutlinedTextField(
                            value = commandSearch,
                            onValueChange = { commandSearch = it },
                            placeholder = { Text("Search commands", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                if (commandSearch.isNotEmpty()) {
                                    IconButton(onClick = { commandSearch = "" }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = "Clear",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(22.dp),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .padding(bottom = 8.dp)
                        )
                        if (matched.isEmpty()) {
                            Text(
                                "No command matches \u201C${commandSearch.trim()}\u201D",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                            )
                        }
                        LazyColumn(Modifier.heightIn(max = 460.dp)) {
                            items(matched, key = { it.name }) { cmd ->
                                Surface(
                                    onClick = {
                                        commandsSheetOpen = false
                                        commandSearch = ""
                                        onInputChange("/" + cmd.name.lowercase() + " ")
                                    },
                                    color = Color.Transparent,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "/" + cmd.name.lowercase(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = HermesPrimary,
                                            modifier = Modifier.width(140.dp)
                                        )
                                        Text(
                                            cmd.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
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
 * `> quote` lines, and |tables|.
 */
private fun parseMarkdown(
    text: String,
    style: TextStyle,
    baseColor: Color
): AnnotatedString {
    // No table detection here - tables are handled by FullWidthTableOverlay
    // Parse normal markdown only
    return parseMarkdownBody(text, style, baseColor)
}

/** Parse the markdown body without table detection. */
private fun parseMarkdownBody(
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

/** Render a markdown table as formatted text. */


/**
 * Table detection lives in ui/markdown/MarkdownTableParser.kt (JVM-tested).
 * All tables in a message render as full-width overlays below the bubble;
 * the bubble keeps the prose between/around them.
 */
private fun parseTablesOf(text: String): TableParse? =
    com.hermes.mobile.ui.markdown.parseTables(text)

/** Telegram-style markdown table rendering as a proper Compose UI. */
@Composable
fun MarkdownTable(tableRows: List<List<String>>, darkTheme: Boolean) {
    val columnCount = tableRows.maxOfOrNull { it.size } ?: 0
    if (columnCount == 0 || tableRows.size < 2) return
    // Wide tables crush into illegible ellipses when every column weights
    // to fill the screen (5+ cols x min 60dp > phone width). Few columns:
    // weighted fill (looks great). Many: fixed 140dp columns in a
    // horizontally scrollable strip — Telegram's answer for wide grids.
    val wide = columnCount > 4

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (darkTheme) Color(0xFF2B3A4A) else Color(0xFFEEF2F7)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .then(if (wide) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
        ) {
            tableRows.forEachIndexed { rowIdx, row ->
                Row(
                    modifier = Modifier
                        .then(if (wide) Modifier else Modifier.fillMaxWidth())
                        // Header is row 0 (the separator was stripped at
                        // parse time — the old code painted row 1, i.e. the
                        // first DATA row, as a second header).
                        .then(
                            if (rowIdx == 0) Modifier
                                .background(
                                    if (darkTheme) Color(0xFF1E2D3D) else Color(0xFFDCE4ED),
                                    shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                                )
                            else Modifier
                        )
                ) {
                    row.forEachIndexed { colIdx, cell ->
                        Box(
                            modifier = Modifier
                                .then(if (wide) Modifier.width(140.dp) else Modifier.weight(1f))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .defaultMinSize(minWidth = 60.dp)
                        ) {
                            Text(
                                text = cell,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = if (rowIdx == 0) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (rowIdx == 0)
                                        (if (darkTheme) Color.White else Color(0xFF1A1A1A))
                                    else
                                        (if (darkTheme) Color(0xFFB0BEC5) else Color(0xFF425262))
                                ),
                                maxLines = if (wide) 4 else 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        // Vertical divider (except last column)
                        if (colIdx < row.size - 1) {
                            VerticalDivider(
                                color = if (darkTheme) Color(0xFF3D4F60) else Color(0xFFC4CDD4),
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                }
                // Divider under the header + between data rows, none after
                // the last row.
                if (rowIdx < tableRows.size - 1) {
                    HorizontalDivider(
                        color = if (darkTheme) Color(0xFF3D4F60) else Color(0xFFC4CDD4),
                        thickness = 0.5.dp
                    )
                }
            }
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

// ── Telegram-style media viewers ──

/** Full-screen image viewer (Telegram style with zoom). */
@Composable
private fun ImageViewerDialog(url: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = "Image viewer",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentScale = ContentScale.Fit
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
                TextButton(onClick = { /* TODO: download */ }) {
                    Text("Save")
                }
            }
        },
        dismissButton = {}
    )
}

/** Video attachment row (plays inline or opens player). */
@Composable
private fun VideoAttachmentRow(name: String, url: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = HermesPrimary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Video",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
