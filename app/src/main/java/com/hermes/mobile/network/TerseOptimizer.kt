package com.hermes.mobile.network

/**
 * Pure state machine behind the "Token Optimizer" setting.
 *
 * The terse directive saves ~30% output on verbose models but costs ~80
 * INPUT tokens every turn and does nothing (or hurts) on models that
 * already answer terse. Decide per model from REAL usage frames — no
 * hardcoded model lists (user rule):
 *   - every [probeEveryN]th turn runs WITHOUT the directive (probe), until
 *     [probesNeeded] measurements exist
 *   - probe mean completion tokens >= [verboseThresholdTokens] -> verbose: apply
 *   - below -> already terse: skip forever
 *   - fewer than [probesNeeded] probes -> apply (benefit of the doubt)
 *
 * Android-free by design (no org.json — it is stubbed in JVM tests): state
 * is one tiny "k=v;k=v" line behind a [Store] seam, so this whole class is
 * real-JVM unit tested in app/src/test.
 */
class TerseOptimizer(
    private val store: Store,
    private val probeEveryN: Int = 8,
    private val probesNeeded: Int = 3,
    private val verboseThresholdTokens: Double = 500.0,
) {
    interface Store {
        fun read(modelId: String): String?
        fun write(modelId: String, json: String)
    }

    /** Prefs-backed store for production. */
    class PrefsStore(private val prefs: android.content.SharedPreferences) : Store {
        override fun read(modelId: String): String? = prefs.getString("terse_opt:$modelId", null)
        override fun write(modelId: String, json: String) {
            prefs.edit().putString("terse_opt:$modelId", json).apply()
        }
    }

    private class State(val fields: MutableMap<String, String> = mutableMapOf()) {
        var turn: Int
            get() = fields["turn"]?.toIntOrNull() ?: 0
            set(v) { fields["turn"] = v.toString() }
        var probes: Int
            get() = fields["probes"]?.toIntOrNull() ?: 0
            set(v) { fields["probes"] = v.toString() }
        var probing: Boolean
            get() = fields["probing"] == "1"
            set(v) { fields["probing"] = if (v) "1" else "0" }
        var verdict: String
            get() = fields["verdict"] ?: ""
            set(v) { fields["verdict"] = v }
        var probeAvg: Double
            get() = fields["probeAvg"]?.toDoubleOrNull() ?: 0.0
            set(v) { fields["probeAvg"] = v.toString() }
        var onAvg: Double?
            get() = fields["onAvg"]?.toDoubleOrNull()
            set(v) { if (v != null) fields["onAvg"] = v.toString() }

        fun serialize(): String = fields.entries.joinToString(";") { "${it.key}=${it.value}" }

        companion object {
            fun parse(raw: String?): State {
                val s = State()
                raw?.split(';')?.forEach { pair ->
                    val k = pair.substringBefore('=', "").trim()
                    val v = pair.substringAfter('=', "").trim()
                    if (k.isNotEmpty()) s.fields[k] = v
                }
                return s
            }
        }
    }

    /** Decide + rotate: should the directive be applied on THIS turn? */
    fun shouldApply(modelId: String, enabled: Boolean): Boolean {
        if (!enabled) return false
        if (modelId.isBlank()) return true
        val st = State.parse(store.read(modelId))
        if (st.verdict == "skip") return false
        st.turn = st.turn + 1
        val probing = st.verdict == "" && st.probes < probesNeeded &&
            st.turn % probeEveryN == 0
        st.probing = probing
        store.write(modelId, st.serialize())
        return !probing
    }

    /** Feed this turn's real completion_tokens back into the model verdict. */
    fun observe(modelId: String, completionTokens: Long, directiveApplied: Boolean) {
        if (modelId.isBlank() || completionTokens <= 0L) return
        val st = State.parse(store.read(modelId))
        if (directiveApplied) {
            val a = st.onAvg ?: completionTokens.toDouble()
            st.onAvg = a * 0.7 + completionTokens * 0.3   // EMA
        } else if (st.probing) {
            val newAvg = if (st.probes == 0) completionTokens.toDouble()
                else st.probeAvg * 0.5 + completionTokens * 0.5
            st.probes = st.probes + 1
            st.probeAvg = newAvg
            st.probing = false
            if (st.probes >= probesNeeded) {
                st.verdict = if (newAvg >= verboseThresholdTokens) "apply" else "skip"
            }
        }
        store.write(modelId, st.serialize())
    }
}
