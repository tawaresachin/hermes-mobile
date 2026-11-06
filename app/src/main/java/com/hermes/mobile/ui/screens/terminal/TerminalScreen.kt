package com.hermes.mobile.ui.screens.terminal

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import com.hermes.mobile.network.HermesApiService

// ═══════════════════════════════════════════════════════════
// Process-level session holder.
// The SSH socket outlives tab switches and screen rotations:
// navigation uses saveState/restoreState (composition dropped,
// state kept), and without configChanges the Activity itself
// recreates on rotation — the reader thread + connection live
// HERE, and the composable re-attaches on entry instead of
// orphaning the socket.
//
// The reader thread only appends to [output] (synchronized); the
// UI polls it. The ring is capped so a runaway command
// (`cat /dev/zero`, `yes`) cannot OOM the process.
// ═══════════════════════════════════════════════════════════
// ─── Saved-profile chip row: tap = fill form, long-press = delete ───
@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
private fun FlowChipRow(
    profiles: List<TerminalProfile>,
    onPick: (TerminalProfile) -> Unit,
    onDelete: (TerminalProfile) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<TerminalProfile?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Cap visible chips: a huge profile list must not turn the form
        // into an endless chip wall — one row, most recent 4.
        profiles.takeLast(4).forEach { p ->
            AssistChip(
                onClick = { onPick(p) },
                label = { Text(p.name, maxLines = 1) },
                // M3 AssistChip has no long-press parameter: combine
                // click + long-press on one detector so the two stay
                // mutually exclusive gestures (a raw pointerInput would
                // also swallow the chip's own onClick).
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = { onPick(p) },
                        onLongClick = { pendingDelete = p }
                    )
            )
        }
    }
    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete profile?") },
            text = { Text("\"${target.name}\" — its encrypted password is removed too.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

object SshTerminal {
    private const val OUTPUT_CAP_CHARS = 32_000
    private const val OUTPUT_TRIM_THRESHOLD = 64_000

    @Volatile var client: SSHClient? = null
    @Volatile var session: Session? = null
    @Volatile var alive: Boolean = false
    // closeAll() marks this; install() clears it. The UI's loss-detection
    // only says "Connection lost" for UNEXPECTED deaths — an intentional
    // Disconnect must not be narrated as one.
    @Volatile var closedByUser: Boolean = false
    @Volatile var length: Int = 0
    // Bumped when the head is dropped or the buffer is cleared: the UI's
    // delta baseline is stale, so it must rewrite the screen from scratch.
    @Volatile var trimmed: Long = 0
    // Serializes connect attempts across re-entries: a Connect whose
    // coroutine was cancelled by a tab switch still holds a live
    // sshj call on the IO pool; a second tap must not start another
    // one underneath it (orphaned parallel sessions).
    private val connectingGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    fun reserveConnect(): Boolean = connectingGuard.compareAndSet(false, true)
    fun releaseConnect() { connectingGuard.set(false) }

    private val output = StringBuilder(16_384)
    private val lock = Any()

    fun append(text: String) {
        synchronized(lock) {
            output.append(text)
            // Amortized: trim only when the buffer passes the threshold.
            if (output.length > OUTPUT_TRIM_THRESHOLD) {
                output.delete(0, output.length - OUTPUT_CAP_CHARS)
                trimmed++
            }
            length = output.length
        }
    }

    fun snapshot(): String = synchronized(lock) { output.toString() }

    fun install(c: SSHClient, s: Session) {
        client = c
        session = s
        closedByUser = false
        alive = true
    }

    fun closeAll() {
        val c = client
        val s = session
        client = null
        session = null
        closedByUser = true
        alive = false
        synchronized(lock) {
            output.setLength(0)
            length = 0
            trimmed++
        }
        try { s?.close() } catch (_: Exception) {}
        try { c?.disconnect() } catch (_: Exception) {}
    }
}

// ═══════════════════════════════════════════════════════════
// OpenSSH "ask"-mode host-key verification (replaces
// PromiscuousVerifier, which accepted ANY host key and let a
// man-in-the-middle impersonate the target):
//   unknown host  -> blocking trust prompt (fingerprint shown);
//                    90 s no answer = reject; on trust the
//                    fingerprint is persisted app-private
//   known host    -> strict fingerprint match; a changed key is
//                    REJECTED (that mismatch is the MITM signal)
//
// [HostKeyVerifier.verify] runs on sshj's transport thread; the bridge
// below blocks it on a latch while the Compose dialog answers.
// Compose snapshot-state writes are thread-safe, so the prompt
// flips its flags from the transport thread directly.
// ═══════════════════════════════════════════════════════════
class TrustPrompt {
    val host = mutableStateOf("")
    val fingerprint = mutableStateOf("")
    val showing = mutableStateOf(false)

    private var latch: CountDownLatch = CountDownLatch(1)
    private var answer = false

    /** Transport thread: block until the dialog answers (timeout = reject).
     *  The latch is recreated per prompt — a CountDownLatch that was already
     *  released by a previous prompt would let a second unknown-host
     *  connect through WITHOUT showing the dialog.
     *
     *  The monitor is released BEFORE [await]: [respond] (UI thread) takes
     *  the same monitor to count down the latch, so holding it across a
     *  90 s wait would make every Trust tap block the main thread (ANR)
     *  and the prompt could never be accepted. The `answer` read after a
     *  successful await is safe: respond() writes it before countDown(),
     *  and await()'s return happens-after countDown(). */
    fun awaitFromTransport(timeoutMs: Long): Boolean {
        val l: CountDownLatch
        synchronized(this) {
            answer = false
            latch = CountDownLatch(1)
            showing.value = true
            l = latch
        }
        if (l.await(timeoutMs, TimeUnit.MILLISECONDS)) return answer
        synchronized(this) { showing.value = false }
        return false
    }

    /** UI thread: the Trust / Reject button. */
    fun respond(ok: Boolean) {
        synchronized(this) {
            answer = ok
            showing.value = false
            latch.countDown()
        }
    }
}

/** OpenSSH-style "SHA256:<base64>" identity of a JCA public key. */
private fun fingerprintOf(key: PublicKey): String =
    "SHA256:" + Base64.getEncoder()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(key.encoded))

/**
 * Connect + PTY. On success the pair is installed into [SshTerminal]
 * (owner of the socket for its whole life). On failure the client is
 * disconnected and the error text returned.
 */
private fun connectPty(
    host: String,
    port: Int,
    user: String,
    pass: String,
    context: Context,
    trust: TrustPrompt,
    keyAuth: TerminalProfileStore.KeyAuth? = null
): Pair<Session?, String> {
    val prefs: SharedPreferences =
        context.getSharedPreferences("hermes_known_hosts", Context.MODE_PRIVATE)
    val ssh = SSHClient()
    try {
        // No implicit "connect until the app dies": socket connect,
        // KEX and auth all respect these, so a blackholed host fails
        // the button instead of spinning forever.
        ssh.setConnectTimeout(15_000)
        ssh.getTransport().setTimeoutMs(30_000)
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(host: String, port: Int, publicKey: PublicKey): Boolean {
                val fp = fingerprintOf(publicKey)
                // OpenSSH known_hosts host identity: port-aware, so
                // 10.0.0.5:2222 and 10.0.0.5:22 are separate entries.
                val id = if (port == 22) host else "[$host]:$port"
                val saved = prefs.getString("host:$id", null)
                if (saved != null) {
                    // Known host: strict match — a changed key is refused.
                    return saved == fp
                }
                // Unknown host: ask. Blocks the transport thread; the
                // dialog answers (or the timeout rejects).
                trust.host.value = id
                trust.fingerprint.value = fp
                val trusted = trust.awaitFromTransport(90_000L)
                if (trusted) prefs.edit().putString("host:$id", fp).commit()
                return trusted
            }

            override fun findExistingAlgorithms(host: String, port: Int): List<String> =
                // Empty = no host-key algorithm restriction: negotiate
                // normally (the standard "no known_hosts entry" behavior).
                emptyList()
        })
        ssh.connect(host, port)
        if (keyAuth != null) {
            // ── SSH public-key auth ──
            // SSHJ 0.38 has no in-memory key source: loadKeys() takes a
            // filesystem path. The key bytes (from SecurePrefs, or picked
            // via the SAF file picker this session) are staged into the
            // APP-PRIVATE cache dir, loaded, and deleted in finally —
            // the file exists for under a second, is never world-
            // readable (SELinux keeps the app dir process-private), and
            // is gone before any shell starts.
            val tmp = java.io.File(context.cacheDir,
                "hermes_ssh_key_" + System.nanoTime())
            try {
                tmp.writeBytes(keyAuth.keyBytes)
                val provider = if (keyAuth.passphrase.isNullOrBlank()) {
                    ssh.loadKeys(tmp.absolutePath)
                } else {
                    ssh.loadKeys(tmp.absolutePath, keyAuth.passphrase)
                }
                ssh.authPublickey(user, provider)
            } finally {
                tmp.delete()
            }
        } else {
            ssh.authPassword(user, pass)
        }
        val session = ssh.startSession()
        // Device-aware geometry: a phone at 80 cols wraps long
        // commands early; 0/0 pixel size = "server picks".
        // EMPTY (not null) modes map: SSHJ 0.38's PTYMode.encode
        // iterates modes.entrySet() without a null check — a null
        // modes argument throws NPE and the shell never opens.
        // SSHJ 0.38 has no post-allocation resize, so this is the
        // only size the session will ever have (documented limit).
        val (cols, rows) = ptyGeometry(context)
        session.allocatePTY("xterm-256color", cols, rows, 0, 0,
            java.util.Collections.emptyMap())
        val shell = session.startShell()
        val readerDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = Thread {
            val buf = CharArray(4096)
            try {
                // InputStreamReader keeps a partial multi-byte UTF-8
                // sequence across reads; String(bytes) would replace it.
                val stream = java.io.InputStreamReader(shell.inputStream, Charsets.UTF_8)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    SshTerminal.append(String(buf, 0, n))
                }
            } catch (_: Exception) {
            } finally {
                readerDone.set(true)
                // Stream ended: mark dead ONLY if this session is still
                // the live one — an intentional closeAll() already
                // cleared the holder, so it must not double-reset.
                if (SshTerminal.session === session) SshTerminal.alive = false
            }
        }
        reader.isDaemon = true
        reader.start()
        // Install AFTER start(): if start() threw, the holder (already
        // cleared by the caller's closeAll) is untouched instead of
        // holding a dead session marked alive.
        SshTerminal.install(ssh, session)
        if (readerDone.get()) {
            // The server closed the channel instantly (EOF before or
            // during install): don't hand the UI a dead session.
            SshTerminal.closeAll()
            return Pair(null, "Connection closed immediately")
        }
        return Pair(session, "")
    } catch (e: Exception) {
        try { ssh.disconnect() } catch (_: Exception) {}
        return Pair(null, e.message ?: "ERROR: ${e::class.java.simpleName}")
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TerminalScreen(paddingValues: PaddingValues = PaddingValues(0.dp)) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Crash/disconnect restore: prefill from the last recorded session
    // (tail text included below the form, labeled as restored history).
    // Held in state (not a plain remember) so the in-session saves can
    // refresh it instead of showing a stale tail.
    var restored by remember {
        mutableStateOf(if (!SshTerminal.alive) TerminalProfileStore.restoreRecord(context) else null)
    }
    var host by remember { mutableStateOf(restored?.host ?: "") }
    var port by remember { mutableStateOf(restored?.port?.toString() ?: "22") }
    var user by remember { mutableStateOf(restored?.user ?: "") }
    var pass by remember { mutableStateOf("") }
    // Neutral, non-error feedback ("profile saved", "profile removed").
    var notice by remember { mutableStateOf("") }
    // The live session's coordinates, for re-establishing after a drop.
    var lastHost by remember { mutableStateOf("") }
    var lastPort by remember { mutableStateOf(22) }
    var lastUser by remember { mutableStateOf("") }
    var hadSession by remember { mutableStateOf(false) }

    // ── Auth method: password or public key ──
    var authMode by remember { mutableStateOf(TerminalProfile.AUTH_PASSWORD) }
    // Picked this session via SAF; not persisted (persistence goes
    // through Save-profile into SecurePrefs).
    var pickedKey by remember {
        mutableStateOf<Pair<String, ByteArray>?>(null)
    }
    var keyPass by remember { mutableStateOf("") }
    // Key summary for a profile whose key lives in SecurePrefs.
    var storedKeySummary by remember { mutableStateOf("") }

    // SAF document picker: one key file, read into memory immediately
    // (the content URI may die with the picker; we never re-open it).
    val openKeyDoc = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            if (bytes.size > TerminalProfileStore.KEY_BYTES_CAP) {
                notice = "Key file too large (max 1 MiB)."
                return@rememberLauncherForActivityResult
            }
            val head = String(bytes.copyOfRange(0, bytes.size.coerceAtMost(64)), Charsets.US_ASCII)
            if (head.startsWith("-----BEGIN") || head.startsWith("PuTTY-Gen-Key")) {
                pickedKey = (uri.lastPathSegment ?: "key") to bytes
                storedKeySummary = ""
            } else {
                notice = "Not a recognized private key file."
            }
        } catch (_: Exception) {
            notice = "Could not read the key file."
        }
    }
    // Re-attach to a live session (tab switch / rotation):
    var connected by remember { mutableStateOf(SshTerminal.alive) }
    val webState = remember { TerminalWebViewState() }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    // ── Batch 4: gateway-side PTY bridge ──
    // connSource: "ssh" (device-side SSH, batches 1-3) or "gateway"
    // (plugin /api/mobile/pty/ws — a shell ON the gateway host).
    var connSource by remember { mutableStateOf("ssh") }
    var gw by remember { mutableStateOf<GatewayPtyClient?>(null) }

    // Every byte the keyboard produces goes to whichever PTY owns the
    // screen. Gateway output is already raw; SSH is a text stream.
    fun sendInputBytes(bytes: ByteArray) {
        val gwClient = gw
        if (gwClient != null && gwClient.isOpen) {
            gwClient.send(bytes)
            return
        }
        val s = SshTerminal.session ?: return
        try {
            s.outputStream.write(bytes)
            s.outputStream.flush()
        } catch (_: Exception) {
        }
    }

    // Gateway connect: no host/port/user form — the credentials come
    // from the configured server (Settings). One live shell per
    // process; output streams straight into the terminal WebView.
    fun connectGateway() {
        val creds = HermesApiService.staticPtyCredentials(context)
        if (creds == null || creds.first.isBlank()) {
            error = "Set the server connection in Settings first."
            return
        }
        val (base, key) = creds
        gw?.close()                            // defensive: no orphan session
        // The WebView reports real cols/rows once laid out; before that
        // (fresh launch) use the device estimate.
        val (cols, rows) = webState.geometry() ?: ptyGeometry(context)
        val client = GatewayPtyClient(base, key)
        gw = client
        running = true
        client.connect(cols, rows, object : GatewayPtyClient.Callback {
            override fun onOpen() {
                running = false
                SshTerminal.closeAll()          // one source at a time
                lastHost = ""
                lastPort = 0
                lastUser = ""
                hadSession = true
                connected = true
                webState.clear()
                webState.focus()
                error = ""
            }

            override fun onOut(bytes: ByteArray) {
                if (!client.isOpen) return      // closed under us
                if (gw != client) return        // superseded: frames die here
                // Raw bytes: xterm decodes UTF-8 across frame boundaries.
                webState.writeBytes(bytes)
            }

            override fun onExit(code: Int) {
                // Ownership guard: a late close/exit frame from a REPLACED
                // client (user reconnected fast) must not clobber the
                // session that now owns the [gw] slot.
                if (gw != client) return
                gw = null
                connected = false
                running = false
                error = "Shell exited ($code)."
            }

            override fun onErr(message: String) {
                if (gw != client) return
                gw = null
                connected = false
                running = false
                error = message
            }
        })
    }

    val trust = remember { TrustPrompt() }

    // A Connect that was in flight when the composition was disposed
    // (tab switch / rotation) cancelled its coroutine; the guard and
    // the sshj call may still settle in the background, but the UI
    // flag must not stay stuck: on re-entry clear it unless a live
    // session now owns the screen.
    LaunchedEffect(Unit) { if (running && !SshTerminal.alive) running = false }

    // The reader thread appends off-UI; poll the holder while connected.
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        // Gateway sessions stream straight into the WebView on the main
        // looper (no SshTerminal, no poll); a source switch mid-
        // screen must not let the SSH loss-branch narrate a gateway
        // drop as "Connection lost".
        if (gw != null) return@LaunchedEffect
        var lastLen = 0
        var lastTrim = -1L
        while (SshTerminal.alive) {
            val snap = SshTerminal.snapshot()
            val trim = SshTerminal.trimmed
            if (snap.length != lastLen || trim != lastTrim) {
                if (trim != lastTrim || snap.length < lastLen) {
                    // The ring trimmed its head; a VT cannot diff that,
                    // so restart the screen from the retained tail.
                    webState.clear()
                    webState.writeText(snap)
                } else {
                    webState.writeText(snap.substring(lastLen))
                }
                lastLen = snap.length
                lastTrim = trim
            }
            delay(150)
        }
        // The connection died underneath us (network drop / remote close):
        // prefill the re-connect form so Connect becomes a one-tap
        // re-establish, and persist the tail. An intentional Disconnect
        // sets closedByUser — that is handled by its button, not here.
        connected = false
        if (!SshTerminal.closedByUser) {
            host = lastHost
            port = lastPort.toString()
            user = lastUser
            val prof = TerminalProfile(
                name = TerminalProfile.nameFor(lastHost, lastPort, lastUser),
                host = lastHost, port = lastPort, user = lastUser
            )
            pass = TerminalProfileStore.storedPassword(context, prof) ?: pass
            val lostKauth = TerminalProfileStore.storedKeyAuth(context, prof)
            keyPass = lostKauth?.passphrase ?: keyPass
            storedKeySummary =
                if (lostKauth != null) "Stored key (${lostKauth.keyBytes.size} bytes)"
                else storedKeySummary
            hadSession = true
            error = "Connection lost — tap Connect to re-establish."
            TerminalProfileStore.saveRestore(context, lastHost, lastPort, lastUser, SshTerminal.snapshot())
            restored = TerminalProfileStore.restoreRecord(context)
        }
    }

    // Debounced crash-restore tail: a process death between the
    // disconnect/loss saves would otherwise leave no record. A 2s
    // throttle keeps SharedPreferences writes off the hot path.
    var lastTailSave by remember { mutableLongStateOf(0L) }
    LaunchedEffect(connected) {
        while (SshTerminal.alive) {
            delay(2_000)
            if (!SshTerminal.alive) break
            val now = System.currentTimeMillis()
            if (now - lastTailSave > 2_000) {
                lastTailSave = now
                TerminalProfileStore.saveRestore(
                    context, lastHost, lastPort, lastUser, SshTerminal.snapshot()
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 0.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                if (connected && gw != null) "Terminal (Gateway)" else "Terminal (SSH/PTY)",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(12.dp))
        if (!connected) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                // ── Batch 4: connection source — device-side SSH, or the
                // gateway's own shell over the authenticated API bridge.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (connSource == "ssh")
                                    MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                1.dp,
                                if (connSource == "ssh")
                                    MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                            )
                            .clickable { connSource = "ssh" },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SSH", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (connSource == "gateway")
                                    MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                1.dp,
                                if (connSource == "gateway")
                                    MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                            )
                            .clickable { connSource = "gateway" },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Gateway", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                // Shared by the form's port field AND the Connect button's
                // enable rule (which lives outside the source-conditional
                // blocks below).
                val portNum = port.trim().toIntOrNull()
                val resolvedPort = portNum ?: 22            // empty → 22
                val portValid = resolvedPort in 1..65535
                if (connSource == "gateway") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            "Opens a shell on the gateway host over the " +
                                "authenticated API (same server as your chat " +
                                "settings). No SSH needed. Resize happens live " +
                                "— rotate to change size.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                } else {
                // ── Saved profiles: tap = fill form, long-press = delete ──
                val profileVersionVar = remember { mutableIntStateOf(0) }
                var profileVersion by profileVersionVar
                val profileList = remember(profileVersion) {
                    TerminalProfileStore.profiles(context)
                }
                if (profileList.isNotEmpty()) {
                    FlowChipRow(profiles = profileList,
                        onPick = { p ->
                            host = p.host
                            port = p.port.toString()
                            user = p.user
                            authMode = p.authMode
                            pass = TerminalProfileStore.storedPassword(context, p) ?: ""
                            val kauth =
                                if (p.authMode == TerminalProfile.AUTH_KEY)
                                    TerminalProfileStore.storedKeyAuth(context, p) else null
                            keyPass = kauth?.passphrase ?: ""
                            storedKeySummary =
                                if (kauth != null) "Stored key (${kauth.keyBytes.size} bytes)"
                                else ""
                            pickedKey = null
                            notice = ""
                        },
                        onDelete = { p ->
                            TerminalProfileStore.deleteProfile(context, p)
                            notice = "Profile removed."
                            // Bump so the remember{} above re-reads storage.
                            ++profileVersion
                        })
                    Spacer(Modifier.height(8.dp))
                }

                Row {
                    OutlinedTextField(
                        host, { host = it }, label = { Text("Host") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(84.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(user, { user = it }, label = { Text("Username") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                // ── Auth method: password / public key ──
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (authMode == TerminalProfile.AUTH_PASSWORD)
                                    MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                1.dp,
                                if (authMode == TerminalProfile.AUTH_PASSWORD)
                                    MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                            )
                            .clickable { authMode = TerminalProfile.AUTH_PASSWORD },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Password", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (authMode == TerminalProfile.AUTH_KEY)
                                    MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                1.dp,
                                if (authMode == TerminalProfile.AUTH_KEY)
                                    MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                            )
                            .clickable { authMode = TerminalProfile.AUTH_KEY },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Key", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (authMode == TerminalProfile.AUTH_PASSWORD) {
                    OutlinedTextField(pass, { pass = it }, label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                } else {
                    val picked = pickedKey
                    val keyAvailable =
                        picked != null || storedKeySummary.isNotEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { openKeyDoc.launch(arrayOf("application/octet-stream")) },
                            modifier = Modifier.weight(1f)) {
                            Text(if (picked?.first != null) "Key: ${picked.first}" else "Pick key file")
                        }
                    }
                    if (storedKeySummary.isNotEmpty() && picked == null) {
                        Text(storedKeySummary, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(keyPass, { keyPass = it },
                        label = { Text("Key passphrase (if encrypted)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    if (!keyAvailable) {
                        Text("Pick the private key file (or save it to a profile first).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }

                if (portNum != null && !portValid) {
                    Spacer(Modifier.height(4.dp))
                    Text("Port must be 1–65535.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            val profile = TerminalProfile(
                                name = TerminalProfile.nameFor(host, resolvedPort, user),
                                host = host, port = resolvedPort, user = user,
                                authMode = authMode
                            )
                            TerminalProfileStore.saveProfile(
                                context,
                                profile,
                                if (authMode == TerminalProfile.AUTH_PASSWORD)
                                    (if (pass.isBlank()) null else pass) else null
                            )
                            if (authMode == TerminalProfile.AUTH_PASSWORD) {
                                // Switched to (or stayed on) password auth:
                                // any key material for these coordinates
                                // goes with the mode, not after it.
                                TerminalProfileStore.clearKeyAuth(context, profile)
                            }
                            var msg = if (authMode == TerminalProfile.AUTH_PASSWORD)
                                (if (pass.isBlank()) "Profile saved."
                                else "Profile saved — password encrypted.")
                                else "Profile saved."
                            if (authMode == TerminalProfile.AUTH_KEY) {
                                // Orphan cleanup, symmetric with the
                                // pw-branch's clearKeyAuth: switching a
                                // profile to key auth removes its stored
                                // password, not just shadows it.
                                TerminalProfileStore.clearPassword(context, profile)
                                val key = pickedKey?.second
                                    ?: TerminalProfileStore.storedKeyAuth(context, profile)
                                        ?.keyBytes
                                if (key != null) {
                                    try {
                                        TerminalProfileStore.saveKeyAuth(
                                            context, profile, key,
                                            keyPass.ifBlank {
                                                TerminalProfileStore.storedKeyAuth(context, profile)
                                                    ?.passphrase
                                            }
                                        )
                                        msg += " Key encrypted."
                                    } catch (e: IllegalArgumentException) {
                                        msg = "Profile saved, but key not stored: ${e.message}."
                                    }
                                }
                            }
                            notice = msg
                            // Bump so the chip row appears / updates.
                            ++profileVersion
                        },
                        enabled = host.isNotBlank() && user.isNotBlank()
                    ) {
                        Text("Save profile")
                    }
                }
                } // end ssh form (gateway shows only the info card above)

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (connSource == "gateway") {
                            connectGateway()
                            return@Button
                        }
                        if (!SshTerminal.reserveConnect()) {
                            // A connect is still settling in the background
                            // (cancelled coroutine + sshj call on the IO pool).
                            error = "A connection attempt is still in progress — " +
                                "wait for it to finish, or try again shortly."
                            return@Button
                        }
                        running = true
                        error = ""
                        scope.launch {
                            try {
                                val targetPort = port.toIntOrNull() ?: 22
                                if (targetPort !in 1..65535) {
                                    error = "Port out of range (1–65535)."
                                    return@launch
                                }
                                // Key auth: a freshly picked file wins;
                                // otherwise fall back to the encrypted key
                                // stored for these exact coordinates.
                                val keyAuth = if (authMode == TerminalProfile.AUTH_KEY) {
                                    pickedKey?.second?.let {
                                        TerminalProfileStore.KeyAuth(
                                            it, keyPass.ifBlank { null }
                                        )
                                    } ?: run {
                                        val prof = TerminalProfile(
                                            TerminalProfile.nameFor(host, targetPort, user),
                                            host, targetPort, user,
                                            TerminalProfile.AUTH_KEY
                                        )
                                        val stored =
                                            TerminalProfileStore.storedKeyAuth(context, prof)
                                        if (stored != null) {
                                            keyPass = stored.passphrase ?: ""
                                            storedKeySummary =
                                                "Stored key (${stored.keyBytes.size} bytes)"
                                        }
                                        stored
                                    }
                                } else {
                                    null
                                }
                                if (authMode == TerminalProfile.AUTH_KEY && keyAuth == null) {
                                    error = "No key available — pick the key file first."
                                    return@launch
                                }
                                SshTerminal.closeAll()
                                webState.clear()
                                webState.focus()
                                val res = withContext(Dispatchers.IO) {
                                    connectPty(host, targetPort, user, pass, context, trust,
                                        keyAuth)
                                }
                                if (res.first != null) {
                                    lastHost = host
                                    lastPort = targetPort
                                    lastUser = user
                                    hadSession = true
                                    connected = true
                                    // New session: the restored-tail record
                                    // belongs to the dead session.
                                    TerminalProfileStore.clearRestore(context)
                                    restored = null
                                } else {
                                    error = res.second
                                }
                            } finally {
                                // Runs on cancellation too (tab switch): the guard
                                // clears so a later tap isn't refused forever.
                                SshTerminal.releaseConnect()
                                running = false
                            }
                        }
                    },
                    enabled = !running &&
                        (if (connSource == "gateway") true
                         else host.isNotBlank() && user.isNotBlank() && portValid),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (running) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(if (hadSession) "Reconnect" else "Connect")
                }
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (notice.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = notice,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // Restored tail from a previous SSH session (crash /
                // relaunch): shown for context, clearly labeled, never
                // re-fed to a shell — it is display-only history. SSH
                // only: gateway shells leave no restore record.
                if (connSource == "ssh") {
                    val tail = restored?.tail
                    if (!tail.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("(restored tail from last session)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val tailStyled = remember(tail) {
                            if (TerminalAnsi.needsParsing(tail)) TerminalAnsi.render(tail)
                            else AnnotatedString(tail)
                        }
                        Text(
                            text = tailStyled,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 8,
                            modifier = Modifier
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                    }
                }
            }
        } else {
            // Real VT surface: xterm.js owns the screen grid, scrollback,
            // alternate screen and selection. The panel carries find/copy;
            // typing goes straight to the PTY.
            TerminalPanel(
                state = webState,
                sendInput = { sendInputBytes(it) },
                onResize = { cols, rows -> gw?.takeIf { it.isOpen }?.resize(cols, rows) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            // ── Raw-key row: drive interactive programs (less, vim,
            // sudo -i) that phone keyboards cannot produce. Sent straight
            // to the PTY; the remote shell owns semantics.
            fun sendRaw(bytes: IntArray) {
                sendInputBytes(bytes.map { it.toByte() }.toByteArray())
                webState.focus()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TextButton(onClick = { sendRaw(intArrayOf(0x03)) }, modifier = Modifier.weight(1f)) {
                    Text("Ctrl-C", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { sendRaw(intArrayOf(0x1b)) }, modifier = Modifier.weight(1f)) {
                    Text("Esc", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { sendRaw(intArrayOf(0x09)) }, modifier = Modifier.weight(1f)) {
                    Text("Tab", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { sendRaw(intArrayOf(0x1b, 0x5b, 0x41)) }, modifier = Modifier.weight(1f)) {
                    Text("Up", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { sendRaw(intArrayOf(0x1b, 0x5b, 0x42)) }, modifier = Modifier.weight(1f)) {
                    Text("Down", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = {
                val gwClient = gw
                if (gwClient != null) {
                    // Gateway session: no SSH tail to persist; just end it.
                    gw = null
                    gwClient.close()
                    SshTerminal.closeAll()
                    connected = false
                    hadSession = true
                    error = ""
                    return@Button
                }
                // Save the tail BEFORE closeAll wipes the buffer.
                TerminalProfileStore.saveRestore(context, lastHost, lastPort, lastUser, SshTerminal.snapshot())
                restored = TerminalProfileStore.restoreRecord(context)
                SshTerminal.closeAll()
                connected = false
                hadSession = false
                error = ""
            }, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
        }
    }

    // First-connection host-key trust prompt (sshj "ask" semantics):
    // shown on the transport thread's request, blocking that thread
    // until answered (90 s timeout = reject).
    if (trust.showing.value) {
        AlertDialog(
            onDismissRequest = { trust.respond(false) },
            title = { Text("Trust this host?") },
            text = {
                Column {
                    Text("First connection to ${trust.host.value}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(trust.fingerprint.value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The fingerprint is stored on this device. If this host's key ever changes, the connection will be refused (possible man-in-the-middle).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { trust.respond(true) }) { Text("Trust") }
            },
            dismissButton = {
                TextButton(onClick = { trust.respond(false) }) { Text("Reject") }
            }
        )
    }
}
