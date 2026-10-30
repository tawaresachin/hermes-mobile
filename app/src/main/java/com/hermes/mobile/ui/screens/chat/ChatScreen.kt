package com.hermes.mobile.ui.screens.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.runtime.*
import com.hermes.mobile.network.ServerCommand
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
import com.hermes.mobile.data.runs.RunController
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
    private val _streamingContent = MutableStateFlow<String?>("")
    val streamingContent: StateFlow<String?> = _streamingContent.asStateFlow()

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
                    // Re-entering the Chat tab for the same session —
                    // observeMessages/observeLiveTurn may still be running
                    // from before. Cancelling first prevents a second
                    // collector firing _messages.value = ... on top of
                    // the live one (visible reload without new data).
                    messageJob?.cancel()
                    liveJob?.cancel()
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
     * can pick the server default for a session that never chose one).
     * If not in local prefs, fetches from server (for Desktop-created sessions). */
    private suspend fun restoreSessionModel(sessionId: String) {
        var model = repository.savedModelForSession(sessionId)
        var slug = repository.savedModelSlugForSession(sessionId)
        if (model.isNullOrBlank()) {
            // Session created on Desktop — fetch model from server
            val resp = try { repository.fetchSessionModel(sessionId) } catch (_: Exception) { null }
            resp?.current?.takeIf { it.isNotBlank() }?.let { serverModel ->
                model = serverModel
                repository.saveModelForSession(sessionId, model, slug)
            }
        }
        _currentModel.value = model ?: ""
        _currentProviderSlug.value = slug
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
            // Cancel any running collectors from a prior session before
            // reading server truth — otherwise both observeMessages() and
            // observeLiveTurn() run simultaneously for different sessions,
            // each calling _messages.value = ... and producing a visible
            // reload (two passes over the same data on a single open).
            messageJob?.cancel()
            liveJob?.cancel()
            _messages.value = emptyList()
            _streamingContent.value = null
            _isStreaming.value = false

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
                reply("Device: " + android.os.Build.MODEL + " (paired)\nServer: " +
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
    private val _pendingApproval = MutableStateFlow<RunController.ApprovalRequest?>(null)
    val pendingApproval: StateFlow<RunController.ApprovalRequest?> = _pendingApproval.asStateFlow()

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
            sendWithAttachments(text, listOfNotNull(attachment), context, onAttachComplete, replyTo, steer)
        }
        fun sendWithAttachments(
            text: String,
            attachments: List<PendingAttachment>,
            context: android.content.Context,
            onAttachComplete: () -> Unit,
            replyTo: Message? = null,
            steer: Boolean = false
        ) {
            val sid = _sessionId.value ?: return
            if (sendInFlight) return
            sendInFlight = true
            onAttachComplete()
            viewModelScope.launch {
                try {
                    // Upload every file, collecting (url, path, type) per attachment.
                    val uploaded = mutableListOf<Triple<String, String, String>>()
                    for (attachment in attachments) {
                        var tempFile: java.io.File? = null
                        try {
                            tempFile = cacheAttachmentToTemp(context, attachment.uri)
                            if (tempFile == null) {
                                _errorMessage.value = "Attachment too large or unreadable (max 25 MB — gateway limit)"
                                continue
                            }
                            repository.uploadFile(
                                tempFile!!, attachment.fileName, attachment.mimeType, sid
                            )?.let { (url, path) ->
                                uploaded.add(Triple(url, path, attachment.attachType))
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            _errorMessage.value = "Upload failed — check the connection and try again"
                        } finally {
                            tempFile?.delete()
                        }
                    }
                    // No attachments (or every upload failed): send the text
                    // the pre-0.0.57 way. 0.0.57's rewrite silently dropped
                    // text-only messages here (empty upload list -> return),
                    // which is the "hello vanished, new/old chat broken" bug.
                    if (uploaded.isEmpty()) {
                        if (text.isNotBlank()) sendMessage(text, replyTo = replyTo, steer = steer)
                        return@launch
                    }
                    // The wire carries ONE attachment per message (TurnText.mediaNote
                    // is singular), so N files = N FIFO turns: the user's caption rides
                    // on the FIRST, every remaining file gets its own message right
                    // after it. Nothing is dropped or silently attached-twice.
                    val first = uploaded.first()
                    val headText = if (text.isNotBlank()) text else ""
                    enqueueMessage(sid, headText, first.first, first.third, replyTo, first.second,
                        displayText = if (text.isNotBlank()) text else null)
                    for (extra in uploaded.drop(1)) {
                        enqueueMessage(sid, "", extra.first, extra.third, null, extra.second)
                    }
                    // Idle: nothing is streaming, so the FIFO queue would sit — start
                    // the head message now. Streaming: the live-turn collector
                    // auto-drains the queue as each turn settles.
                    if (!_isStreaming.value) drainQueue(sid)
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
                    // MediaStore.Downloads is API 29+; below that the field is
                    // a class-load failure (an Error, so the catch below can't
                    // swallow it) — take the cache+FileProvider fallback.
                    val collection = when {
                        message.attachmentType?.startsWith("image") == true ->
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        android.os.Build.VERSION.SDK_INT >= 29 ->
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        else -> null
                    }
                    val uri = collection?.let { context.contentResolver.insert(it, values) }
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
                                // The gateway 413s any body over 25 MB
                                // (aiohttp client_max_size); 25 MB leaves
                                // headroom for multipart framing overhead.
                                if (total > 25L * 1024 * 1024) return@use false
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
    var pendingAttachments by remember { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    fun addPendingAttachment(a: PendingAttachment) { pendingAttachments = pendingAttachments + a }
    fun clearPendingAttachments() { pendingAttachments = emptyList() }
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

    // ── File picker (stores selection, doesn't upload until send clicked) ──
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris != null) {
            vm.hideEmojiPicker()
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val cr = context.contentResolver
                for (uri in uris) {
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
                    addPendingAttachment(PendingAttachment(
                        uri = uri,
                        fileName = fileName,
                        mimeType = mimeType,
                        attachType = attachType
                    ))
                }
            }
        }
    }

    // Camera launcher: requests permission then opens camera
    var cameraPhotoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            val uri = cameraPhotoUri
            if (uri != null) {
                vm.hideEmojiPicker()
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    addPendingAttachment(PendingAttachment(
                        uri = uri,
                        fileName = "camera_${System.currentTimeMillis()}.jpg",
                        mimeType = "image/jpeg",
                        attachType = "image"
                    ))
                }
            } else {
                vm.setError("Camera cancelled")
            }
        } else {
            vm.setError("Camera cancelled")
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val photoDir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
            val photoFile = java.io.File(photoDir, "photo_${System.currentTimeMillis()}.jpg")
            cameraPhotoUri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", photoFile
            )
            takePictureLauncher.launch(cameraPhotoUri!!)
        } else {
            vm.setError("Camera permission denied")
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
                        // Single status line: streaming note > provider note > model name
                        Text(
                            text = when {
                                isStreaming && (streamingContent?.isBlank() == true) ->
                                    turnStatusNote?.takeIf { it.isNotBlank() } ?: ThinkingSubtitle()
                                selectedModelProvider.isNotBlank() -> "via $selectedModelProvider"
                                else -> selectedModelName
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = HermesPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Token usage line (inside the chip, hides when unknown)
                        if (contextUsed > 0L && contextTotal > 0L) {
                            Text(
                                text = "${meterFmt(contextUsed)} / ${meterFmt(contextTotal)} tokens",
                                style = MaterialTheme.typography.labelSmall,
                                color = LightFgMuted,
                            )
                        }
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
                // Swarm mode toggle for THIS session (icon-only, vertically centered —
                // matches the layout before the 8191f9c "header declutter")
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
                        (it.isStreaming && (streamingContent?.isNotBlank() == true)) ||
                        (!it.isStreaming && it.content.isNotBlank())
                }
        }

        // Auto-scroll: fires when the VISIBLE list grows — i.e. a message
        // (re)appears at the bottom edge. Keying on messages.size missed
        // streamed replies: the assistant row exists (size stable) while
        // blank-filtered, then flips visible with no size change, so the
        // finished reply never scrolled into view. displayMessages.size
        // changes exactly when that filter flips OR a real new row lands
        // (poller catch-up included). Guards per the Telegram contract:
        // never yank while the reader scrolled up (userScrolledAway) or a
        // gesture/fling is running.
        LaunchedEffect(displayMessages.size, userScrolledAway) {
            if (displayMessages.isNotEmpty() && !userScrolledAway && !listState.isScrollInProgress) {
                listState.scrollToItem(0)
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
                                displayContent = displayContent ?: "",
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
                                    // One source of truth for kind dispatch
                                    // (the sheet's own routing uses the same
                                    // previewKindFor; the old inline regex
                                    // could drift from it).
                                    if (com.hermes.mobile.ui.preview.previewKindFor(n, t)
                                            == com.hermes.mobile.ui.preview.PreviewKind.Image) {
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
                                displayContent = displayContent ?: "",
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
                vm.sendWithAttachments(inputText.trim(), pendingAttachments, context, onAttachComplete = {
                    pendingAttachments = emptyList()
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
            pendingAttachments = pendingAttachments,
            onRemoveAttachment = { attachment -> pendingAttachments = pendingAttachments.filter { it != attachment } },
            onMarkup = { pendingAttachments.firstOrNull()?.let { markupTarget = it } },
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
                    pendingAttachments = emptyList()
                    vm.sendMarkedImage(inputText.trim(), flattenedFile, pendingReply)
                }
            )
        }

        // ── Telegram-style attach sheet (Gallery / File) ──
        if (showAttachSheet) {
            AttachSheet(
                onGallery = {
                    showAttachSheet = false
                    // PickVisualMedia is single-select at the API level — it
                    // cannot multi-select on any Android version. Multi-select
                    // lives in OpenMultipleDocuments (the same multi-URI path
                    // the File row uses), scoped to images + videos.
                    filePickerLauncher.launch(arrayOf("image/*", "video/*"))
                },
                onFile = {
                    showAttachSheet = false
                    filePickerLauncher.launch(arrayOf("*/*"))
                },
                onCamera = {
                    showAttachSheet = false
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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
            IconButton(onClick = onSelectAll, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(
                    imageVector = if (count == total) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                    contentDescription = "Select all",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onCopy, enabled = count > 0, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy",
                    tint = if (count > 0) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete, enabled = count > 0, modifier = Modifier.minimumInteractiveComponentSize()) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = if (count > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onExit, modifier = Modifier.minimumInteractiveComponentSize()) {
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
        // A "Save" action used to sit here doing nothing (`/* TODO: download */`).
        // A dead button is worse than none, so only Close remains until a real
        // save (authenticated fetch -> MediaStore) exists.
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        dismissButton = {}
    )
}
