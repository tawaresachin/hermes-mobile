package com.hermes.mobile.data.runs

import android.content.Context
import com.hermes.mobile.data.local.MessageDao
import com.hermes.mobile.data.local.SessionDao
import com.hermes.mobile.data.model.Message
import com.hermes.mobile.data.model.MessageRole
import com.hermes.mobile.data.model.MessageStatus
import com.hermes.mobile.network.HermesApiService
import com.hermes.mobile.notifications.ResponseWatcherService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable per-session turn engine on the gateway's /v1/runs API.
 *
 * Why this exists: the chat-completions SSE is synchronous — closing the
 * socket (screen switch, process death, network drop) makes the gateway
 * INTERRUPT the agent. /v1/runs decouples the turn from the transport:
 * the run executes server-side no matter what the phone does, its status
 * is pollable, its events are re-subscribable, and stop/steer/approval
 * are explicit routes. This controller owns the CLIENT half of that
 * contract:
 *
 *  - one live turn per session, keyed by session id, in an APP-LEVEL scope
 *    (SupervisorJob) — never the ViewModel's, so navigating away cannot
 *    cancel it;
 *  - live state (streaming text, tool trail, pending approval) exposed as
 *    StateFlows the chat screen re-attaches to when reopened;
 *  - run ids persisted in prefs — after process death, [recover] re-attaches
 *    or finalizes every unfinished turn from server truth;
 *  - Room is the single writer: chunks update the placeholder row live, so
 *    any screen reading the session sees progress without this class.
 *
 * Event consumption is deliberately transport-tolerant: SSE loss falls back
 * to status polling (3s), never to a dead bubble.
 */
@Singleton
class RunController @Inject constructor(
    private val api: HermesApiService,
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val POLL_INTERVAL_MS = 3_000L
        private const val SLOW_POLL_INTERVAL_MS = 10_000L
        // A run whose status endpoint 404s (gateway restarted, TTL swept):
        // stop polling and settle the bubble from the session transcript.
        private const val STATUS_MISS_LIMIT = 4
    }

    /** A tool call awaiting the user's Approve/Deny decision. */
    data class ApprovalRequest(
        val command: String,
        val description: String,
        val choices: List<String>,
        val requestId: String,
    )

    /** Everything the chat UI needs for one in-flight (or awaiting-approval)
     * turn. Immutable snapshots; the map is the source of truth. */
    data class LiveTurn(
        val sessionId: String,
        val runId: String,
        val assistantMsgId: Long,
        val userMsgId: Long?,
        val query: String,
        val model: String,
        val streamingText: String = "",
        val toolLines: List<ToolTrailReducer.Line> = emptyList(),
        val pendingApproval: ApprovalRequest? = null,
        val stopping: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _turns = MutableStateFlow<Map<String, LiveTurn>>(emptyMap())
    /** Live turns per session — collect with mapLatest(sessionId) in the VM. */
    val turns: StateFlow<Map<String, LiveTurn>> = _turns.asStateFlow()

    // Transport handles + poll fallbacks, one per session.
    private val eventSources = ConcurrentHashMap<String, EventSource>()
    private val pollJobs = ConcurrentHashMap<String, Job>()

    fun liveTurn(sessionId: String): LiveTurn? = _turns.value[sessionId]
    fun isBusy(sessionId: String): Boolean = _turns.value.containsKey(sessionId)

    /** Live streaming text for a session (voice screen partial display). */
    fun streamingTextFlow(sessionId: String): Flow<String> =
        turns.map { it[sessionId]?.streamingText ?: "" }.distinctUntilChanged()

    /** Suspend until the session's turn settles; returns the final assistant
     * text (null on timeout / admit failure). Poll-based on purpose: it is
     * immune to the start/settle race a callback would have, and 250ms is
     * invisible next to multi-second LLM turns. */
    suspend fun awaitTurn(sessionId: String, sinceMs: Long, timeoutMs: Long = 300_000L): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        // startTurn registers the turn asynchronously — wait for it to appear
        // (or for admission to fail fast, which also clears quickly).
        var registered = isBusy(sessionId)
        while (!registered && System.currentTimeMillis() < deadline) {
            delay(100)
            registered = isBusy(sessionId) || api.activeRunId(sessionId) != null
            // Admit failed: placeholder deleted, no run saved, and the newest
            // assistant row predates this call -> nothing will ever arrive.
            if (!registered && System.currentTimeMillis() - sinceMs > 8_000) return null
        }
        while (System.currentTimeMillis() < deadline) {
            if (!isBusy(sessionId) && api.activeRunId(sessionId) == null) {
                val last = messageDao.getMessagesOnce(sessionId)
                    .lastOrNull { it.role == com.hermes.mobile.data.model.MessageRole.ASSISTANT && !it.isStreaming }
                return when {
                    last != null && last.timestamp >= sinceMs -> last.content
                    else -> null
                }
            }
            delay(250)
        }
        return null
    }

    private inline fun updateTurn(sessionId: String, crossinline f: (LiveTurn) -> LiveTurn) {
        _turns.update { cur -> cur[sessionId]?.let { cur + (sessionId to f(it)) } ?: cur }
    }

    // ── Start a turn ────────────────────────────────────────────────────

    /**
     * Admit a durable run for [sessionId] and own it until it settles.
     * Inserts the user row (unless a queued row already exists) + assistant
     * placeholder BEFORE admission so the bubbles appear instantly; on
     * admission failure the placeholder is removed and the error surfaces
     * through [onAdmitError].
     */
    fun startTurn(
        sessionId: String,
        query: String,
        model: String,
        provider: String?,
        attachmentUrl: String = "",
        attachType: String = "",
        attachmentPath: String = "",
        replyTo: String? = null,
        userMsgId: Long? = null,
        onAdmitError: (String) -> Unit = {},
        // Skill invocations: `query` is the server-expanded prompt (huge);
        // `displayText` is what the user actually typed ("/archify foo") and
        // what the bubble shows. Null displayText = show the query verbatim.
        displayText: String? = null,
    ) {
        scope.launch {
            // 1. Local rows first — instant feedback, survives everything.
            var uid = userMsgId
            if (uid == null) {
                uid = insertUserRow(sessionId, displayText ?: query, attachmentUrl, attachType, replyTo)
            } else {
                try { messageDao.updateMessageStatus(uid, MessageStatus.SENT) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
            }
            val placeholder = messageDao.insertMessage(
                Message(sessionId = sessionId, role = MessageRole.ASSISTANT,
                    content = "", isStreaming = true)
            )

            // 2. Wire text: quote + media note ride inside the user message.
            val wire = TurnText.wireQuery(query, replyTo, attachmentUrl, attachType, attachmentPath)

            // 3. Token Optimizer: terse directive only where it pays (learned
            //    per model). Rides as `instructions` — /v1/runs extracts it as
            //    the ephemeral system prompt, same as the chat path's system msg.
            val safeModel = model.ifBlank { api.fetchDefaultModelId() }
            val applyTerse = api.terseDecision(safeModel)
            val serverId = api.serverIdFor(sessionId)?.takeIf { it.isNotBlank() } ?: sessionId

            val runId = try {
                api.startRun(serverId, wire, safeModel, provider,
                    TurnText.buildInstructions(applyTerse))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                // Admission failed — no server work exists. Clean the ghost.
                try {
                    messageDao.deleteMessage(placeholder)
                    messageDao.updateMessageStatus(uid, MessageStatus.FAILED)
                } catch (e2: kotlinx.coroutines.CancellationException) { throw e2 } catch (_: Exception) { }
                onAdmitError(e.message ?: "Could not reach the server.")
                return@launch
            }

            api.saveActiveRun(sessionId, runId)
            _turns.update {
                it + (sessionId to LiveTurn(
                    sessionId = sessionId, runId = runId, assistantMsgId = placeholder,
                    userMsgId = uid, query = query, model = safeModel))
            }
            // Ongoing "is typing…" watcher — survives screen changes; the
            // completion path replaces it with the reply notification.
            ResponseWatcherService.start(context, sessionId, query)
            attach(sessionId)
        }
    }

    private suspend fun insertUserRow(
        sessionId: String, query: String, attachmentUrl: String, attachType: String, replyTo: String?
    ): Long {
        val id = messageDao.insertMessage(
            Message(
                sessionId = sessionId, role = MessageRole.USER, content = query,
                attachmentUrl = attachmentUrl.ifBlank { null },
                attachmentType = attachType.ifBlank { null },
                replyToText = replyTo?.take(300),
                status = MessageStatus.SENDING,
            )
        )
        sessionDao.incrementMessageCount(sessionId)
        try { messageDao.updateMessageStatus(id, MessageStatus.SENT) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        return id
    }

    // ── Event consumption (SSE primary, status polling fallback) ────────

    /** Wire the delivery channels for a session's live turn. Idempotent:
     * a reopen after navigating away must NOT cancel the healthy event
     * source — the server drops the run's event queue when an SSE handler
     * exits, so resubscribing would orphan the run (terminal events would
     * then reach nobody). Polling is armed unconditionally as the
     * backstop: it is the delivery guarantee (status is durable server-
     * side state); the event stream is only the smoothness layer. */
    fun attach(sessionId: String) {
        val turn = _turns.value[sessionId] ?: return
        startPolling(sessionId)
        if (eventSources.containsKey(sessionId)) return
        val source = api.subscribeRunEvents(
            runId = turn.runId,
            onEvent = { ev -> handleEvent(sessionId, ev) },
            onTransportLost = {
                eventSources.remove(sessionId)
                startPolling(sessionId)
            },
        )
        if (source == null) startPolling(sessionId) else eventSources[sessionId] = source
    }

    private fun handleEvent(sessionId: String, ev: RunEventCodec.RunEvent) {
        when (ev) {
            is RunEventCodec.RunEvent.Delta -> {
                updateTurn(sessionId) { it.copy(streamingText = it.streamingText + ev.text) }
            }
            is RunEventCodec.RunEvent.ToolStarted -> {
                updateTurn(sessionId) { it.copy(toolLines = ToolTrailReducer.started(it.toolLines, ev.tool, ev.preview)) }
            }
            is RunEventCodec.RunEvent.ToolCompleted -> {
                updateTurn(sessionId) { it.copy(toolLines = ToolTrailReducer.completed(it.toolLines, ev.tool, ev.duration, ev.error)) }
            }
            is RunEventCodec.RunEvent.Approval -> {
                updateTurn(sessionId) {
                    it.copy(pendingApproval = ApprovalRequest(ev.command, ev.description, ev.choices, ev.requestId))
                }
                if (api.isAutoApprove()) {
                    // User opted in (Settings): answer 'session' immediately —
                    // 'once' would re-prompt per call, 'always' outlives the run.
                    val choice = if ("session" in ev.choices) "session"
                        else if ("once" in ev.choices) "once" else "deny"
                    resolveApproval(sessionId, choice)
                    return
                }
                // The phone's real job: wake the user for the decision.
                ResponseWatcherService.notifyApproval(context, sessionId, ev.command, ev.description)
            }
            is RunEventCodec.RunEvent.Completed -> finish(sessionId, ev.output, ev.inputTokens, ev.outputTokens, failed = null)
            is RunEventCodec.RunEvent.Failed -> finish(sessionId, "", 0, 0, failed = ev.error)
            is RunEventCodec.RunEvent.Cancelled -> finish(sessionId, ev.partial, 0, 0, failed = null)
            is RunEventCodec.RunEvent.Status -> {
                if (ev.name == "run.stopping") updateTurn(sessionId) { it.copy(stopping = true) }
                if (ev.name == "approval.responded") updateTurn(sessionId) { it.copy(pendingApproval = null) }
            }
        }
    }

    /** SSE died (or never opened): poll run status until terminal. The run
     * itself keeps executing server-side — this only restores delivery. */
    private fun startPolling(sessionId: String) {
        if (pollJobs.containsKey(sessionId)) return
        pollJobs[sessionId] = scope.launch {
            var misses = 0
            while (true) {
                // Event stream healthy -> status is just a watchdog tick;
                // stream lost / never opened -> the 3s poll IS the delivery.
                delay(if (eventSources.containsKey(sessionId)) SLOW_POLL_INTERVAL_MS else POLL_INTERVAL_MS)
                val turn = _turns.value[sessionId] ?: break
                val status = api.fetchRunStatus(turn.runId)
                if (status == null) {
                    if (++misses >= STATUS_MISS_LIMIT) {
                        // Run state gone (gateway restart / sweep): settle from
                        // the session transcript — the server persisted the turn.
                        settleFromTranscript(sessionId)
                        break
                    }
                    continue
                }
                misses = 0
                when (status.optString("status")) {
                    "completed" -> {
                        val u = status.optJSONObject("usage")
                        finish(sessionId, status.optString("output", ""),
                            u?.optLong("input_tokens", 0L) ?: 0L,
                            u?.optLong("output_tokens", 0L) ?: 0L, failed = null)
                        break
                    }
                    "failed", "interrupted" -> {
                        finish(sessionId, "", 0, 0, failed = status.optString("error", "Run failed"))
                        break
                    }
                    "cancelled" -> {
                        finish(sessionId, status.optString("output", ""), 0, 0, failed = null)
                        break
                    }
                    "waiting_for_approval" -> {
                        val a = status.optJSONObject("approval")
                        if (a != null && _turns.value[sessionId]?.pendingApproval == null) {
                            val choices = a.optJSONArray("choices")?.let { arr -> (0 until arr.length()).map { i -> arr.optString(i) } }
                                ?: listOf("once", "deny")
                            updateTurn(sessionId) {
                                it.copy(pendingApproval = ApprovalRequest(
                                    a.optString("command", ""), a.optString("description", ""),
                                    choices, a.optString("request_id", "")))
                            }
                            if (api.isAutoApprove()) {
                                val choice = if ("session" in choices) "session"
                                    else if ("once" in choices) "once" else "deny"
                                resolveApproval(sessionId, choice)
                            }
                        }
                    }
                    // queued/running/stopping — keep polling.
                }
            }
            pollJobs.remove(sessionId)
        }
    }

    // ── Terminal handling ───────────────────────────────────────────────

    /** Persist the final bubble + bookkeeping. Idempotent per session: the
     * SSE terminal event and the poll fallback can race; first writer wins. */
    private fun finish(sessionId: String, output: String, usageIn: Long, usageOut: Long, failed: String?) {
        val turn = _turns.value[sessionId] ?: return  // already settled
        _turns.update { it - sessionId }
        eventSources.remove(sessionId)?.cancel()
        pollJobs.remove(sessionId)?.cancel()
        api.clearActiveRun(sessionId)
        scope.launch { finalizeTurn(turn, output, usageIn, usageOut, failed) }
    }

    private suspend fun finalizeTurn(
        turn: LiveTurn, output: String, usageIn: Long, usageOut: Long, failed: String?
    ) {
        val sid = turn.sessionId
        // Session totals → this turn's delta (runs report cumulative usage).
        val (prevIn, prevOut) = api.lastUsageTotals(sid)
        val turnOut = if (usageOut > prevOut) usageOut - prevOut else 0L
        if (usageIn > 0 || usageOut > 0) api.saveUsageTotals(sid, maxOf(usageIn, prevIn), maxOf(usageOut, prevOut))
        // Terse-replies learning rides the per-turn OUTPUT delta.
        if (turnOut > 0) api.terseObserve(turn.model, turnOut, api.terseDecision(turn.model))

        val content = when {
            failed != null && output.isBlank() -> "⚠️ $failed"
            failed != null -> output + "\n\n⚠️ $failed"
            output.isBlank() && turn.streamingText.isNotBlank() -> turn.streamingText
            else -> output
        }
        val clean = TurnText.stripUploadUrls(sid, content)
        try {
            if (clean.isBlank() && turn.toolLines.isEmpty()) {
                // Nothing arrived at all (e.g. instant provider rejection
                // already surfaced via `failed`) — no empty bubble ghosts.
                if (failed == null) messageDao.deleteMessage(turn.assistantMsgId)
                else messageDao.updateMessage(turn.assistantMsgId, "⚠️ $failed", false)
            } else {
                messageDao.updateMessage(turn.assistantMsgId, clean, false)
            }
            if (turn.toolLines.isNotEmpty()) {
                messageDao.updateMessageToolActivity(turn.assistantMsgId, ToolTrailReducer.toJson(turn.toolLines))
            }
            if (turnOut > 0) messageDao.updateMessageTokens(turn.assistantMsgId, turnOut)
            // Context fill for the meter seed on reopen: the live server value.
            val live = try { api.fetchContextUsage(sid) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { 0L }
            if (live > 0) messageDao.updateMessageContextTokens(turn.assistantMsgId, live)
            // Compression rotation: adopt the live session tip so the NEXT turn
            // continues the child transcript (runs never echo it back mid-turn).
            val serverId = api.serverIdFor(sid)?.takeIf { it.isNotBlank() } ?: sid
            val tip = try { api.resolveSessionTip(serverId) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            if (!tip.isNullOrBlank()) api.saveServerId(sid, tip)
            if (turn.userMsgId != null && failed == null) {
                messageDao.updateMessageStatus(turn.userMsgId, MessageStatus.READ)
            } else if (turn.userMsgId != null && failed != null) {
                messageDao.updateMessageStatus(turn.userMsgId, MessageStatus.FAILED)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }

        // Notify (backgrounded) + drop the "is typing…" watcher.
        if (clean.isNotBlank() && failed == null) {
            ResponseWatcherService.notifyReady(context, sid, clean, turn.query)
        }
        ResponseWatcherService.stop(context)
    }

    /** Run state gone on the server: pull the session transcript tail and
     * settle the placeholder with whatever the agent actually saved. */
    private suspend fun settleFromTranscript(sessionId: String) {
        val turn = _turns.value[sessionId] ?: return
        _turns.update { it - sessionId }
        eventSources.remove(sessionId)?.cancel()
        pollJobs.remove(sessionId)?.cancel()
        api.clearActiveRun(sessionId)
        val serverId = api.serverIdFor(sessionId)?.takeIf { it.isNotBlank() } ?: sessionId
        val msgs = api.fetchSessionMessages(serverId)
        val tail = msgs?.lastOrNull { it.optString("role") == "assistant" }
        val content = tail?.optString("content", "").orEmpty()
        finalizeTurn(turn, content, 0, 0, failed = null)
    }

    // ── Controls (Telegram parity: stop / steer / approve) ──────────────

    /** Real stop: POST /v1/runs/{id}/stop. The agent interrupts, the server
     * persists the partial, the terminal event finalizes the bubble. */
    fun stop(sessionId: String) {
        val turn = _turns.value[sessionId] ?: return
        updateTurn(sessionId) { it.copy(stopping = true) }
        scope.launch {
            if (!api.stopRun(turn.runId)) {
                // Stop rejected (run already gone): settle locally from the
                // text that arrived so far — never leave a stuck bubble.
                finish(sessionId, turn.streamingText, 0, 0, failed = null)
            }
        }
    }

    /** Steer the running turn (Telegram mid-run message parity). Returns via
     * the lambda: accepted / rejected (run not steering-able) / no run. */
    fun steer(sessionId: String, text: String, onResult: (Boolean) -> Unit = {}) {
        val turn = _turns.value[sessionId]
        if (turn == null) { onResult(false); return }
        scope.launch {
            val ok = api.steerRun(turn.runId, text)
            if (ok) {
                // Show the steer as its own user bubble (Telegram renders it
                // inline too — the agent sees it in the transcript).
                insertUserRow(sessionId, text, "", "", null)
            }
            onResult(ok)
        }
    }

    /** Resolve a pending approval (once | session | always | deny). */
    fun resolveApproval(sessionId: String, choice: String) {
        val turn = _turns.value[sessionId] ?: return
        val approval = turn.pendingApproval ?: return
        updateTurn(sessionId) { it.copy(pendingApproval = null) }
        scope.launch {
            val ok = api.resolveApproval(turn.runId, choice, approval.requestId)
            if (!ok) {
                // The run may have settled while we were tapping; the poll
                // loop re-reads truth either way.
                startPolling(sessionId)
            }
        }
    }

    // ── Process-death recovery ──────────────────────────────────────────

    /** Re-attach or finalize every turn that was live when the process died.
     * Called once at app start (HermesApp). Cheap: one status poll per run. */
    fun recover() {
        scope.launch {
            for (sid in api.sessionsWithActiveRuns()) {
                val runId = api.activeRunId(sid) ?: continue
                val status = api.fetchRunStatus(runId)
                when (status?.optString("status")) {
                    null, "interrupted" -> {
                        // Gone/unreachable: settle the placeholder from the
                        // transcript so no blank bubble survives.
                        recoverFromTranscript(sid, runId)
                    }
                    "completed" -> {
                        val u = status.optJSONObject("usage")
                        recoverFinalize(sid, runId, status.optString("output", ""),
                            u?.optLong("input_tokens", 0L) ?: 0L,
                            u?.optLong("output_tokens", 0L) ?: 0L, null)
                    }
                    "failed", "cancelled" -> {
                        recoverFinalize(sid, runId, status.optString("output", ""), 0, 0,
                            status.optString("error", "").ifBlank { null })
                    }
                    "waiting_for_approval" -> {
                        val a = status.optJSONObject("approval")
                        val approval = a?.let {
                            ApprovalRequest(
                                it.optString("command", ""), it.optString("description", ""),
                                it.optJSONArray("choices")?.let { arr -> (0 until arr.length()).map { i -> arr.optString(i) } }
                                    ?: listOf("once", "deny"),
                                it.optString("request_id", ""))
                        }
                        // Re-adopt the live turn so the chat screen shows the
                        // approval card + streaming chrome again.
                        val placeholder = messageDao.getMessagesOnce(sid)
                            .lastOrNull { it.role == MessageRole.ASSISTANT && it.isStreaming }
                        if (placeholder != null) {
                            _turns.update {
                                it + (sid to LiveTurn(sid, runId, placeholder.id,
                                    userMsgId = null, query = "", model = api.savedModelForSession(sid).orEmpty(),
                                    pendingApproval = approval))
                            }
                            ResponseWatcherService.start(context, sid, "")
                            attach(sid)
                        } else {
                            api.clearActiveRun(sid)
                        }
                    }
                    else -> {
                        // queued/running/stopping — re-adopt + keep watching.
                        val placeholder = messageDao.getMessagesOnce(sid)
                            .lastOrNull { it.role == MessageRole.ASSISTANT && it.isStreaming }
                        if (placeholder != null) {
                            _turns.update {
                                it + (sid to LiveTurn(sid, runId, placeholder.id,
                                    userMsgId = null, query = "", model = api.savedModelForSession(sid).orEmpty()))
                            }
                            ResponseWatcherService.start(context, sid, "")
                            attach(sid)
                        } else {
                            api.clearActiveRun(sid)
                        }
                    }
                }
            }
        }
    }

    private suspend fun recoverFinalize(sid: String, runId: String, output: String, inTok: Long, outTok: Long, failed: String?) {
        api.clearActiveRun(sid)
        val placeholder = messageDao.getMessagesOnce(sid).lastOrNull { it.role == MessageRole.ASSISTANT && it.isStreaming }
            ?: return
        finalizeTurn(
            LiveTurn(sid, runId, placeholder.id, userMsgId = null, query = "", model = api.savedModelForSession(sid).orEmpty()),
            output, inTok, outTok, failed)
    }

    private suspend fun recoverFromTranscript(sid: String, runId: String) {
        api.clearActiveRun(sid)
        val placeholder = messageDao.getMessagesOnce(sid).lastOrNull { it.role == MessageRole.ASSISTANT && it.isStreaming }
            ?: return
        val serverId = api.serverIdFor(sid)?.takeIf { it.isNotBlank() } ?: sid
        val tail = api.fetchSessionMessages(serverId)?.lastOrNull { it.optString("role") == "assistant" }
        val content = tail?.optString("content", "").orEmpty()
        if (content.isNotBlank()) {
            finalizeTurn(
                LiveTurn(sid, runId, placeholder.id, userMsgId = null, query = "", model = api.savedModelForSession(sid).orEmpty()),
                content, 0, 0, null)
        } else {
            try { messageDao.deleteMessage(placeholder.id) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
    }
}
