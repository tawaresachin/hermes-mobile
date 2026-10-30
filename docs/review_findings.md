# Hermes Mobile Implementation Review

This review covers accuracy, security, performance, testability, scalability, and clean code practices.

## Findings

### 1. Code Quality: Silent Exception Swallowing Across the Codebase

**Severity:** Medium — observability / maintainability
**Files:** `HermesRepository.kt`, `HermesApiService.kt`, `RunController.kt`, and ~100 others

**Issue:** 103 sites across `app/src/main/java` catch `Exception` with an empty body (`catch (_: Exception) {}`) or return a silent default, discarding the exception entirely. Examples:

- `HermesRepository.kt:871` — `catch (_: Exception) { }` in `getUsageStats()`
- `HermesRepository.kt:463` — `catch (_: Exception) { }` in `applyServerResponse()`
- `HermesRepository.kt:501` — `catch (_: Exception) { return false }` in `pollServerResponse()`
- `HermesApiService.kt` — 35 sites, including `healthCheck`, `listSkills`, `fetchServerSessions`, `fetchRunStatus`, `subscribeRunEvents`, etc.

When these fail in production (transient network drops, gateway timeouts, JSON parse errors on a new plugin version), the failure is invisible: the UI shows stale data, an empty list, or a "no answer" bubble with no record of *why*. The app already has a working `DiagLog` facility (`DiagLog.e("TAG", "msg", throwable)`) that is used elsewhere (e.g. `SecurePrefs`, `HermesApp` crash handler), so the fix is mechanical and low-risk.

**Proposed fix:**
```kotlin
// Before
} catch (_: Exception) { }

// After
} catch (e: kotlinx.coroutines.CancellationException) { throw e }
catch (e: Exception) {
    com.hermes.mobile.DiagLog.e("REPO", "Operation failed: ${e.javaClass.simpleName}: ${e.message}")
}
```

For methods that must return a safe default, log before returning:
```kotlin
} catch (e: Exception) {
    com.hermes.mobile.DiagLog.w("API", "fetchRunStatus failed for $runId: ${e.message}")
    return null
}
```

`DiagLog`'s signature is `e(tag: String, msg: String)` — no `Throwable` overload — so the exception class and message are embedded in the string. The existing `e`/`w`/`i` helpers already handle the level filtering and file append, so this is a drop-in change.

**Scope note:** This is a mechanical, additive change — no behavior changes. It should be applied to the core files first (`HermesRepository.kt`, `HermesApiService.kt`, `RunController.kt`) and extended to the remaining ~60 sites over time.

## Summary

The codebase is clean and follows strong modern Android practices: Hilt dependency injection, Compose + StateFlow UI, durability-focused offline-first sync, and a sophisticated process-death-robust turn engine on the gateway's `/v1/runs` API. The security posture for cleartext/public URL blocking is excellent (`isTrustedServerHost` is well-tested with an instrumented test suite). The 10-second sync throttle in `SessionsViewModel` correctly serializes re-entry. The API key masking falls back to `"•".repeat(8)` for non-`hermes-` keys, so no plaintext is ever shown.

The one actionable improvement is strengthening production observability by routing silent exception catches through `DiagLog`.