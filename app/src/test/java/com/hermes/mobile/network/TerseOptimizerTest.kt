package com.hermes.mobile.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Token Optimizer decision core (TerseOptimizer).
 * These pin the policy the shipped 402-vs-320 regression motivated:
 * verbose models keep the directive, already-terse models stop paying its
 * input cost, unknown models get benefit-of-the-doubt until measured.
 */
class TerseOptimizerTest {

    private class MemoryStore : TerseOptimizer.Store {
        val map = mutableMapOf<String, String>()
        override fun read(modelId: String): String? = map[modelId]
        override fun write(modelId: String, json: String) { map[modelId] = json }
    }

    private fun runTurns(
        opt: TerseOptimizer,
        model: String,
        turns: Int,
        tokensFor: (Boolean) -> Long,
    ): List<Boolean> {
        val applied = mutableListOf<Boolean>()
        repeat(turns) {
            val a = opt.shouldApply(model, enabled = true)
            opt.observe(model, tokensFor(a), a)
            applied.add(a)
        }
        return applied
    }

    @Test fun `disabled toggle never applies`() {
        val opt = TerseOptimizer(MemoryStore())
        assertFalse(opt.shouldApply("some-model", enabled = false))
    }

    @Test fun `unknown model applies on non-probe turns`() {
        val opt = TerseOptimizer(MemoryStore())
        // turns 1..7: probe lands on the 8th, so the directive applies here
        repeat(7) { assertTrue(opt.shouldApply("m", enabled = true)) }
    }

    @Test fun `every probeEveryN-th turn probes (skips directive) until verdict`() {
        val opt = TerseOptimizer(MemoryStore(), probeEveryN = 4, probesNeeded = 3)
        val hist = runTurns(opt, "claude-class", 12) { if (it) 700 else 1200 }
        // turns 4 and 8 are the first probes (1-indexed) -> applied=false;
        // turn 12 is the third probe only if no verdict yet (there is none).
        assertFalse(hist[3]); assertFalse(hist[7])
        assertTrue(hist[0]); assertTrue(hist[1]); assertTrue(hist[2])
    }

    @Test fun `verbose model keeps the directive after probes`() {
        val opt = TerseOptimizer(MemoryStore(), probeEveryN = 4, probesNeeded = 3)
        val hist = runTurns(opt, "claude-class", 20) { if (it) 700 else 1200 }
        // 3 probes of 1200 (avg >= 500) -> verdict apply; no probes after verdict
        assertTrue(hist.takeLast(8).all { it })
    }

    @Test fun `terse model permanently skips the directive`() {
        val opt = TerseOptimizer(MemoryStore(), probeEveryN = 4, probesNeeded = 3)
        val hist = runTurns(opt, "agnes-2.5-flash", 20) { if (it) 380 else 340 }
        // three terse probes (avg 340 < 500) -> skip forever
        assertTrue(hist.takeLast(5).none { it })
    }

    @Test fun `borderline model at threshold lands on apply`() {
        val opt = TerseOptimizer(MemoryStore(), probeEveryN = 4, probesNeeded = 3)
        runTurns(opt, "m", 16) { if (it) 500 else 510 }
        assertTrue(opt.shouldApply("m", enabled = true))  // 510 >= 500 -> apply
    }

    @Test fun `blank model id always applies`() {
        val opt = TerseOptimizer(MemoryStore())
        assertTrue(opt.shouldApply("", enabled = true))
    }

    @Test fun `non-positive token reports never advance a probe`() {
        // probeEveryN=1 -> every turn probes until probesNeeded reached.
        val opt = TerseOptimizer(MemoryStore(), probeEveryN = 1, probesNeeded = 2)
        assertFalse(opt.shouldApply("m", enabled = true))  // probe 1
        opt.observe("m", 0L, false)                         // garbage -> ignored
        assertFalse(opt.shouldApply("m", enabled = true))  // still probe (probes=0)
        opt.observe("m", 900L, false)                       // real probe 1
        assertFalse(opt.shouldApply("m", enabled = true))  // probe 2 (probes=1 < 2)
        opt.observe("m", 900L, false)                       // real probe 2 -> verdict apply
        assertTrue(opt.shouldApply("m", enabled = true))    // apply from here on
    }
}
