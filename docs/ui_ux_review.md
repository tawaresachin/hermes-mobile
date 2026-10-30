# Hermes Mobile — UI/UX Implementation & Experience Review

## Executive Summary

Hermes Mobile presents a cohesive, high-performance messaging interface inspired heavily by Telegram's design tokens and interaction patterns (inverted `LazyColumn`, swipe-to-action gestures, in-app media preview, compact composer).

To reach **production-grade polish, high intuitiveness, and professional elegance**, this review identifies key UI/UX opportunities across visual consistency, touch ergonomics, layout hierarchy, and micro-interactions.

---

## 1. Core Visual & UX Strengths

| Strength | Evidence |
|---|---|
| **Telegram-Inspired Chat Mechanics** | Inverted `LazyColumn` (`reverseLayout = true`) prevents scroll-jank during streaming; smooth swipe-to-reply/delete gestures; distinct chat bubbles with dark/light adaptivity (`UserBubbleLight`/`UserBubbleDark`). |
| **Durable Run & Status Awareness** | Session list shows `working`/`done`/`error`/`needs input` badges (Cursor-style); context meter (`X / Y tokens`) visible in header. |
| **Rich Media & Attachment Integration** | File preview sheet for Markdown, HTML, PDF, CSV, JSON, Code, XLSX, DOCX, PPTX; markup editor for inline image drawing. |
| **Voice Screen Visuals** | Neon sphere orb with luminous rim, plasma wisps, Siri-style rings. |

---

## 2. Key Opportunities (Verified from Source)

### A. Visual Consistency — Hardcoded Colors Outside Theme
**Files:** `MessageBubble.kt`, `ApprovalCard.kt`, `MarkdownTable.kt`, `SessionsScreen.kt`, `VoiceScreen.kt`

| File:Line | Hardcoded Value | Semantic Meaning | Should Use |
|---|---|---|---|
| `MessageBubble.kt:137` | `Color(0xFF000000)` | User bubble text (light) | `MaterialTheme.colorScheme.onSurface` |
| `MessageBubble.kt:613` | `Color(0xFF4FC3F7)` | Read receipt blue | `HermesPrimary` or custom token |
| `ApprovalCard.kt:39` | `Color(0xFFE5A100)` | Warning/approval border | `WarningAmber` |
| `MarkdownTable.kt:37-94` | 7 hex values (`0xFF2B3A4A`, `0xFFEEF2F7`, etc.) | Table backgrounds/headers | Theme tokens (`surfaceVariant`, `onSurfaceVariant`) |
| `SessionsScreen.kt:811-838` | 5 hex values (`0xFFB44DFF`, `0xFF34A853`, etc.) | Status badges (working/done/error) | Semantic tokens (`SuccessGreen`, `ErrorRed`, custom `InfoPurple`) |
| `VoiceScreen.kt:609-612` | `0xFF05050D`, `0xFF0A0E20` | Voice orb canvas | Theme-aware tokens |

**Fix:** Centralize all into `Color.kt` / `MaterialTheme.colorScheme`.

### B. Touch Ergonomics — Sub-48dp Interactive Targets
**File:** `MessageBubble.kt:320`

```kotlin
IconButton(
    modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(top = 3.dp, end = 3.dp)
        .size(18.dp)  // ← 18dp ≪ 48dp minimum
) { ... }
```

**Found:** Multiple `IconButton`/`FilledIconButton` instances at 18–24dp without expanded touch bounds in:
- `MessageBubble.kt` (message menu)
- `SettingsProviders.kt` (edit/delete/default star buttons)
- `ChatScreen.kt` (selection mode toolbar buttons)

**Fix:** Wrap in `Modifier.minimumInteractiveComponentSize()` or use standard Material3 `IconButton` padding (default provides 48dp target when using proper modifier chains).

### C. Settings Screen — Monolithic Composable
**File:** `SettingsScreen.kt` (923 lines, ~5,165 lines across 7 settings files)

Single scrollable column containing: Connection, Account, Preferences (font slider, toggles), Server (jobs+skills), Providers (CRUD modal), Usage, About/Version.

**Fix:** Extract into modular `SettingsSection` composables with navigation to sub-screens (e.g., `ModelProvidersScreen`, `ServerManagementScreen`).

### D. Chat Header — Overloaded Subtitle Stack
**File:** `ChatScreen.kt` header (lines ~1510–1585) renders:
1. Model name (primary)
2. Streaming status ("thinking…") or provider slug
3. Token usage line ("12.3k / 200k tokens")
4. Swarm indicator (icon button)

**Fix:** Two-line layout: `Model Name` + single status line; move provider/token/swarm into an expandable details chip.

### E. Message Action Sheet — Missing Group Dividers
**File:** `MessageActions.kt:47–99` (`MessageActionSheet`)

Actions render as a flat list: Select → Copy → Reply → Edit → Forward → Regenerate → Fork → Delete. No visual separation between *content actions* (Copy/Reply/Forward) and *destructive/structural actions* (Delete/Regenerate/Fork).

**Fix:** Add `HorizontalDivider` with 8dp padding between groups.

### F. Code Block Copy — No Feedback
**File:** `MarkdownText.kt` — no copy button implementation exists for fenced code blocks.

**Fix:** Add copy icon in code block header with haptic tick + transient "Copied!" toast.

---

## 3. Screen-by-Screen Summary

| Screen | Key Gaps | Priority |
|---|---|---|
| **Home** | No inline search in recent sessions; swipe-delete lacks label until committed | Low |
| **Chat** | Tool result truncation wraps poorly on small screens; code copy no feedback; header clutter | **High** |
| **Sessions** | Diagonal swipe can flash wrong gradient; no batch select | Medium |
| **Voice** | No live transcript overlay toggle | Medium |
| **Settings** | Monolithic scroll; Provider modal nested inside | **High** |

---

## 4. Proposed Action Plan

| Area | Current | Proposed | Effort |
|---|---|---|---|
| **Theme Design Tokens** | ~15 scattered hex colors | Centralize in `Color.kt`, reference via `colorScheme` | Low |
| **Touch Targets** | 18–24dp bare IconButtons | `Modifier.minimumInteractiveComponentSize()` | Low |
| **Settings Refactor** | 900+ line single composable | Modular sections + sub-routes | Medium |
| **Chat Header** | 4 stacked subtitle lines | 2 lines + expandable chip | Low |
| **Message Actions** | Flat list | Grouped with dividers | Low |
| **Code Copy** | No copy button | Icon + haptic + toast | Low |

---

## Conclusion

Hermes Mobile is **functionally complete and performant**. The gaps are polish-layer: visual token hygiene, touch-target compliance, and component modularization. Addressing these will bring the app to a **top-tier, production-ready mobile client** without architectural changes.

---

*Reviewed against source: `app/src/main/java/com/hermes/mobile/ui/screens/**/*.kt`, `Color.kt`, `Theme.kt`, `MessageActions.kt`, `MarkdownText.kt`.*