package com.hermes.mobile.ui.screens.terminal

import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.ui.platform.LocalContext
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
    user: String,
    pass: String,
    context: Context,
    trust: TrustPrompt
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
        ssh.connect(host, 22)
        ssh.authPassword(user, pass)
        val session = ssh.startSession()
        // 120x40 xterm-256color: phone columns default to 80, which
        // wraps long commands; 0/0 width+height = "server picks".
        // EMPTY (not null) modes map: SSHJ 0.38's PTYMode.encode
        // iterates modes.entrySet() without a null check — a null
        // modes argument throws NPE and the shell never opens.
        session.allocatePTY("xterm-256color", 120, 40, 0, 0,
            java.util.Collections.emptyMap())
        val shell = session.startShell()
        val readerDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = Thread {
            val buf = ByteArray(4096)
            try {
                while (true) {
                    val n = shell.inputStream.read(buf)
                    if (n < 0) break
                    SshTerminal.append(String(buf, 0, n, Charsets.UTF_8))
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

@Composable
fun TerminalScreen(paddingValues: PaddingValues = PaddingValues(0.dp)) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    // Re-attach to a live session (tab switch / rotation):
    var connected by remember { mutableStateOf(SshTerminal.alive) }
    var output by remember {
        mutableStateOf(if (SshTerminal.alive) SshTerminal.snapshot() else "")
    }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val trust = remember { TrustPrompt() }
    val outputScroll = rememberScrollState()
    // Previous known bottom of the output box — the auto-follow gap is
    // measured against it, because by the time this effect runs
    // [outputScroll.maxValue] has ALREADY grown with the new content.
    var prevMax by remember { mutableStateOf(0f) }

    // A Connect that was in flight when the composition was disposed
    // (tab switch / rotation) cancelled its coroutine; the guard and
    // the sshj call may still settle in the background, but the UI
    // flag must not stay stuck: on re-entry clear it unless a live
    // session now owns the screen.
    LaunchedEffect(Unit) { if (running && !SshTerminal.alive) running = false }

    // Fresh attach (tab switch / reconnect): the remembered scroll
    // position may sit past the new content's bottom; start at the top
    // so bottom-follow works from the first tick.
    LaunchedEffect(connected) { if (connected) { outputScroll.scrollTo(0); prevMax = 0f } }

    // The reader thread appends off-UI; poll the holder while connected.
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        var lastLen = -1
        while (SshTerminal.alive) {
            delay(150)
            if (SshTerminal.length != lastLen) {
                lastLen = SshTerminal.length
                output = SshTerminal.snapshot()
            }
        }
        // The connection died underneath us (network drop / remote close):
        // fall back to the form with the reason visible, not a dead
        // "connected" panel that swallows every Send. An intentional
        // Disconnect sets closedByUser — that is not "lost".
        connected = false
        if (!SshTerminal.closedByUser) error = "Connection lost"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Terminal (SSH/PTY)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        if (!connected) {
            OutlinedTextField(host, { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
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
                            SshTerminal.closeAll()
                            output = ""
                            val res = withContext(Dispatchers.IO) {
                                connectPty(host, user, pass, context, trust)
                            }
                            if (res.first != null) {
                                connected = true
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
                enabled = !running && host.isNotBlank() && user.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (running) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Connect")
            }
            if (error.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().height(300.dp)
            ) {
                Text(
                    text = output.ifBlank { "(connected)" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(outputScroll)
                        .padding(8.dp)
                )
            }
            // Console behavior: follow the bottom on new data — but don't
            // yank the user who scrolled up to read older output (value > 0
            // far from the bottom). Scrolling back to the bottom resumes.
            LaunchedEffect(output) {
                val atBottom = outputScroll.value == 0 ||
                    prevMax - outputScroll.value.toFloat() < 200f
                if (atBottom) outputScroll.scrollTo(outputScroll.maxValue)
                prevMax = outputScroll.maxValue.toFloat()
            }
            Spacer(Modifier.height(8.dp))
            fun send() {
                // The PTY echoes input back through the reader; no
                // local append here (that would double-print).
                val s = SshTerminal.session ?: return
                try {
                    val out = s.outputStream
                    out.write((input + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                } catch (_: Exception) {
                    return
                }
                input = ""
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, label = { Text("Input") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onGo = { send() }
                    ),
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { send() }) { Text("Send") }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                SshTerminal.closeAll()
                connected = false
                output = ""
                input = ""
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
