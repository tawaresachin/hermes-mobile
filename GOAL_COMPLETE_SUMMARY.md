# GOAL COMPLETE — Production Readiness Review, Kanban Feature

## Summary

The Kanban feature was fully implemented, tested, and the release was built. All acceptance criteria met.

## Deliverables

### Mobile App (v0.0.45 — built from commit 38d4825)

| Artifact | Path |
|----------|------|
| Release APK | `app/build/outputs/apk/release/Hermes-Mobile-v0.1.35.apk` (14.4 MB) |
| Release Bundle (AAB) | `app/build/outputs/bundle/release/app-release.aab` (17.8 MB) |

### Plugin (v0.0.45)

| Artifact | Path in hermes-mobile-plugin repo |
|----------|-----------------------------------|
| Source | `src/hermes_mobile_plugin/kanban_routes.py` |
| Registration | `src/hermes_mobile_plugin/__init__.py` (lines 111, 140) |
| Tests | `tests/test_kanban_routes.py` (7 tests, all passing) |
| Documentation | `README.md` (route table updated) |
| Changelog | `CHANGES.md` (0.0.45 entry) |

## Implementation Details

### 1. Settings Toggle for "Kanban Mode"

- **SettingsScreen.kt**: Added "Kanban" section with toggle and subtitle showing mode status (`dispatch_in_gateway` running/off/stale)
- **SettingsViewModel.kt**: Added `kanban` state, `loadKanbanMode()`, `setKanbanMode()` with network round-trip
- **KanbanModels.kt**: `parseKanbanMode()` parser for API response

### 2. Swarm Mode Removed from Chat Screen

- **ChatScreen.kt**: Removed `_swarmEnabled`, `setSwarmEnabled()`, and the Hive icon UI block
- **TurnText.kt**: Removed `SWARM_DIRECTIVE` constant and `buildInstructions(swarm)` parameter
- **RunController.kt**: Removed swarm argument from `buildInstructions()` call
- **HermesApiService.kt**: Removed `isSwarmForSession()` / `saveSwarmForSession()`
- **HermesRepository.kt**: Removed swarm wrapper methods
- **RunEventCodecTest.kt**: Removed `swarmDirectiveShape()` test

### 3. Dynamic Auto-Sync with Agent's Kanban Mode

- **KanbanViewModel.kt**: Polls mode every 10s via `loadKanbanMode(silent=true)`; syncs `KanbanWatcherService` on change
- **Kanban routes**: `PUT /api/mobile/kanban/mode` returns `restart_required` when dispatcher toggle flips (gateway reads at boot)

### 4. Dedicated Kanban Screen (created when no agent kanban)

| File | Purpose |
|------|---------|
| `KanbanModels.kt` | Immutable data classes: `KanbanTaskCard`, `KanbanColumn`, `KanbanBoard`, `KanbanMode`, `KanbanTaskDetail`, `KanbanComment`, `KanbanRun`, `KanbanEventLine` |
| `KanbanViewModel.kt` | StateFlow, mode ops, board load, task CRUD, comment, action (unblock/approve/archive/retry/reassign), event cursor |
| `KanbanScreen.kt` | Full UI: board columns (horizontal scroll), task cards (expandable), FAB create, modal dialogs, bottom sheet detail |
| `KanbanWatcherService.kt` | Background polling (20s), notifies on `blocked`, `needs_input`, `review_requested`, `completed`, `gave_up`, `crashed`, `timed_out` |

### 5. Server-Side Plugin Bridge (hermes-mobile-plugin)

| Route | Method | Purpose |
|-------|--------|---------|
| `/api/mobile/kanban/mode` | GET/PUT | Read/write Kanban mode, returns `restart_required` |
| `/api/mobile/kanban/board` | GET | Full board with columns/tasks/progress |
| `/api/mobile/kanban/boards` | GET | List board slugs |
| `/api/mobile/kanban/assignees` | GET | List profile names |
| `/api/mobile/kanban/tasks` | POST | Create task |
| `/api/mobile/kanban/tasks/{id}` | GET | Task detail + comments + events + runs |
| `/api/mobile/kanban/tasks/{id}/comments` | POST | Post comment |
| `/api/mobile/kanban/tasks/{id}/actions` | POST | unblock/retry/approve/archive/reassign |
| `/api/mobile/kanban/events` | GET | Cursor feed for notifications |

## Test Results

### Mobile Unit Tests
```
:app:testDebugUnitTest — BUILD SUCCESSFUL in 11s
```

### Plugin Route Tests
```
tests/test_kanban_routes.py: 7 passed, 7 warnings, 0.7s
tests/test_version_consistency.py: passed
All plugin tests: 184 passed, 24 warnings
```

### Visual QA (Mac.apk runtime)

| Feature | Status | Evidence |
|---------|--------|----------|
| Settings Kanban toggle | PASS | kanban_08_settings_kanban.png |
| New task dialog | PASS | kanban_04_create.png |
| Task detail sheet | PASS | kanban_03_detail.png |
| Board columns displayed | PASS | kanban_02_board.png |
| No Swarm icon in Chat | PASS | kanban_09_chat_header.png |

## Files Changed (mobile app)

- **Added**: 4 new files
  - `KanbanModels.kt`, `KanbanScreen.kt`, `KanbanViewModel.kt`, `KanbanWatcherService.kt`
  - `KanbanModelsTest.kt`
- **Modified**: 12 files (Navigation, Settings, Repository, API, Chat, TurnText, RunController, Manifest, etc.)
- **Removed**: `SWARM_DIRECTIVE` and related tests/ui

## Files Changed (plugin)

- **Added**: `kanban_routes.py`, `tests/test_kanban_routes.py`
- **Modified**: `__init__.py` (register routes), `README.md`, `CHANGES.md`, `plugin.yaml`, `pyproject.toml`

---

**Ready for release via: `.review/scripts/release.sh --tag v0.0.45 --message "Kanban feature: Settings toggle, dedicated screen, notification integration, swarm removed"`**