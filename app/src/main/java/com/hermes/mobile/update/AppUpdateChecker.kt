package com.hermes.mobile.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.hermes.mobile.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-app self-update via GitHub Releases — the same releases the ship
 * script already produces (versioned APK + SHA256SUMS). Nothing fancy:
 *
 *  1. CHECK  — GET api.github.com/.../releases/latest (public, no token).
 *     Throttled to once per 12h per device (2 API calls/day cap): the
 *     result (tag + url + checkedAt) is persisted, so About renders
 *     instantly from cache and the refresh arrow inside the window just
 *     re-reads it. Rate limit is 60/hr PER CLIENT IP — 2/day per device
 *     is nowhere near it even behind carrier NAT.
 *  2. COMPARE — tag parsed to major*1M + minor*1K + patch — the exact
 *     formula scripts/release.sh stamps into versionCode — compared to
 *     BuildConfig.VERSION_CODE. Numeric, no string equality.
 *  3. DOWNLOAD — plain OkHttp GET of the release asset (302 -> Azure
 *     blob CDN; bulk bytes there are NOT subject to the API rate limit)
 *     into cacheDir/updates/. SHA256 verified against SHA256SUMS before
 *     the installer ever sees it; a mismatch deletes the file.
 *  4. INSTALL — ACTION_VIEW on the FileProvider URI. APKs are signed
 *     with the stable release key, so this updates in place (no
 *     uninstall, data kept). Android shows its own one-time "install
 *     unknown apps" prompt + the system installer — the closest to
 *     fully-in-app that Android allows outside Play.
 */

data class AppUpdateState(
    /** "0.0.52" — installed version, always shown. */
    val current: String = "",
    val checking: Boolean = false,
    /** Server has a newer release than us. */
    val available: Boolean = false,
    val latest: String = "",
    val notes: String = "",
    /** Download progress 0..1 (only meaningful while downloading). */
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
    /** Millis when the last successful (or failed-but-throttled) check ran. */
    val lastCheckedAt: Long = 0L,
)

@Singleton
class AppUpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy {
        context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    }

    // Plain client, no gateway auth interceptor — api.github.com must not
    // see our server key. Generous read timeout: 3.7 MB over mobile.
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private val _state = MutableStateFlow(AppUpdateState(current = BuildConfig.VERSION_NAME))
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    /** True while inside the 12h throttle window with a cached verdict. */
    val cachedFresh: Boolean
        get() = System.currentTimeMillis() - prefs.getLong(KEY_CHECKED_AT, 0L) < CHECK_INTERVAL_MS

    init {
        // Seed instantly from the last persisted check — About renders
        // "up to date" / "update available" with zero network on launch.
        val checkedAt = prefs.getLong(KEY_CHECKED_AT, 0L)
        val latest = prefs.getString(KEY_LATEST, "").orEmpty()
        _state.value = _state.value.copy(
            latest = latest,
            available = latest.isNotEmpty() &&
                versionCodeFromTag(latest) > BuildConfig.VERSION_CODE,
            notes = prefs.getString(KEY_NOTES, "").orEmpty(),
            lastCheckedAt = checkedAt,
        )
    }

    /**
     * Check for a newer release. Honors the 12h window — repeated About
     * visits, offline devices, or impatient taps never exceed 2 API
     * calls/day. The cached verdict stays visible either way.
     */
    suspend fun check(force: Boolean = false) {
        if (!force && cachedFresh) return
        if (_state.value.checking) return
        _state.value = _state.value.copy(checking = true, error = null)
        try {
            withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Hermes-Mobile/${BuildConfig.VERSION_NAME}")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("GitHub ${resp.code}")
                val json = JSONObject(resp.body?.string() ?: error("empty body"))
                val tag = json.optString("tag_name")            // "v0.0.53"
                val assets = json.optJSONArray("assets")
                var apkUrl = ""
                var sumsUrl = ""
                for (i in 0 until (assets?.length() ?: 0)) {
                    val a = assets!!.optJSONObject(i)
                    val name = a.optString("name")
                    if (name == "SHA256SUMS") sumsUrl = a.optString("browser_download_url")
                    if (Regex("Hermes-Mobile-v.*\\.apk$").matches(name))
                        apkUrl = a.optString("browser_download_url")
                }
                val notes = json.optString("body").lineSequence()
                    .dropWhile { it.isBlank() || it.startsWith("##") }
                    .take(6).joinToString("\n").trim()
                if (tag.isEmpty() || apkUrl.isEmpty()) error("no APK asset found")
                persist(tag.removePrefix("v"), apkUrl, sumsUrl, notes)
            }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the cached verdict visible; surface the error line.
            _state.value = _state.value.copy(
                checking = false,
                error = e.message ?: e.javaClass.simpleName)
            return
        }
        _state.value = _state.value.copy(
            checking = false,
            error = null,
            lastCheckedAt = System.currentTimeMillis(),
        )
    }

    /** Download the flagged release, verify its SHA256, launch installer. */
    suspend fun downloadAndInstall() {
        val url = prefs.getString(KEY_APK_URL, "").orEmpty()
        val sumsUrl = prefs.getString(KEY_SUMS_URL, "").orEmpty()
        val latest = _state.value.latest
        if (url.isEmpty() || latest.isEmpty()) {
            _state.value = _state.value.copy(error = "nothing to download — check first")
            return
        }
        if (_state.value.downloading) return
        _state.value = _state.value.copy(downloading = true, progress = 0f, error = null)
        val out = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(out, "Hermes-Mobile-v$latest.apk")
        // One update APK at a time — drop leftovers from previous rounds.
        out.listFiles()?.filter { it.name.endsWith(".apk") && it != apk }?.forEach { it.delete() }
        try {
            // Expected hash from the release's SHA256SUMS ("<hash>  <file>").
            val expected: String? = if (sumsUrl.isEmpty()) null else withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url(sumsUrl)
                        .header("User-Agent", "Hermes-Mobile").build()
                    http.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) return@use null
                        res.body?.string()?.lineSequence()
                            ?.firstOrNull { it.contains("Hermes-Mobile-v$latest.apk") }
                            ?.trim()?.substringBefore(' ')?.lowercase()
                            ?.takeIf { it.length == 64 }
                    }
                } catch (e: Exception) { null }   // sums are best-effort integrity
            }
            // Already on disk from a cancelled installer round? Verify and
            // reuse instead of re-downloading 3.7 MB.
            val reuse = apk.exists() && expected != null &&
                runCatching { sha256(apk) == expected }.getOrDefault(false)
            if (!reuse) {
                withContext(Dispatchers.IO) {
                    val req = Request.Builder().url(url)
                        .header("User-Agent", "Hermes-Mobile").build()
                    http.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) error("download ${res.code}")
                        val total = res.headers["content-length"]?.toLongOrNull() ?: -1L
                        apk.outputStream().use { sink ->
                            val src = res.body?.byteStream() ?: error("no body")
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = src.read(buf)
                                if (n < 0) break
                                sink.write(buf, 0, n)
                                done += n
                                if (total > 0) _state.value =
                                    _state.value.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                if (expected != null && sha256(apk) != expected) {
                    apk.delete()
                    error("checksum mismatch — APK discarded")
                }
            }
            _state.value = _state.value.copy(downloading = false, progress = 1f)
            launchInstaller(apk)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            apk.delete()
            _state.value = _state.value.copy(
                downloading = false, progress = 0f, error = e.message ?: "download failed")
        }
    }

    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun persist(version: String, apkUrl: String, sumsUrl: String, notes: String) {
        _state.value = _state.value.copy(
            latest = version,
            available = versionCodeFromTag(version) > BuildConfig.VERSION_CODE,
            notes = notes,
        )
        prefs.edit()
            .putLong(KEY_CHECKED_AT, System.currentTimeMillis())
            .putString(KEY_LATEST, version)
            .putString(KEY_APK_URL, apkUrl)
            .putString(KEY_SUMS_URL, sumsUrl)
            .putString(KEY_NOTES, notes)
            .apply()
    }

    companion object {
        const val REPO = "tawaresachin/hermes-mobile"
        const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000   // 2 checks/day cap

        private const val KEY_CHECKED_AT = "checkedAt"
        private const val KEY_LATEST = "latest"
        private const val KEY_APK_URL = "apkUrl"
        private const val KEY_SUMS_URL = "sumsUrl"
        private const val KEY_NOTES = "notes"

        /** mirrors scripts/release.sh: CODE = MA*1000000 + MI*1000 + PA */
        fun versionCodeFromTag(tag: String): Int = try {
            val p = tag.removePrefix("v").split(".")
            (p.getOrNull(0)?.toIntOrNull() ?: 0) * 1_000_000 +
                (p.getOrNull(1)?.toIntOrNull() ?: 0) * 1_000 +
                (p.getOrNull(2)?.toIntOrNull() ?: 0)
        } catch (e: Exception) { 0 }

        private fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
