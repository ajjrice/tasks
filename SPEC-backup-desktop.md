# SPEC — Desktop Backups (`backup-desktop`)

Branch: `backup-desktop` (worktree `research/tasks-org/worktrees/backup-desktop`), off `origin/main` @ `4ea72a1d26` (2026-10-26).

## Problem

The desktop app (composeApp) has **no backup/restore at all** — data lives only in the
local desktop DB (kmp `data/` module DAOs over the local database). Android ships a full
backups feature; desktop users have no safety net. Goal: port the *local* backup core to
desktop in two phases.

**Explicit acceptance priority (Alex, 2026-10-26): a backup file created on an Android
device must be restorable from the desktop pane** — the first manual test will be exactly
that cross-device restore.

## Verified facts (read 2026-10-26, from the native code in `app/`)

### Native file map
| File | Role |
|---|---|
| `app/.../compose/settings/BackupsScreen.kt` | pane UI (Compose, 4 sections) |
| `app/.../preferences/fragments/Backups.kt` | fragment host: pickers, callbacks, observers |
| `app/.../preferences/fragments/BackupsViewModel.kt` | pane state + pref reads |
| `app/.../backup/TasksJsonExporter.kt` | full-dump JSON writer (manual + auto) |
| `app/.../backup/TasksJsonImporter.kt` | two-pass streaming importer + migrations |
| `app/.../backup/BackupConstants.kt` | filenames + timestamp regex |
| `app/.../jobs/BackupWork.kt` | daily repeating work: cleanup (keep 7) + export |
| `app/.../jobs/DriveUploader.kt`, `drive/*` | Google Drive (out of scope) |
| `app/.../backup/TasksBackupAgent.kt` | Android Auto Backup (out of scope, Android-only API) |
| `app/.../dialogs/ExportTasksDialog.kt`, `ImportTasksDialog.kt` | progress + confirmation UI |
| `app/.../preferences/PreferencesViewModel.kt` | `lastBackup/lastDriveBackup/lastAndroidBackup`, `staleLocalBackup` (ts < today−2d), `staleRemoteBackup`, `usingPrivateStorage`, `scheduleBackup()` = next-midnight unique work |

### Backup JSON format (the wire format — must stay compatible both ways)
```
{ "version": <int versionCode>, "timestamp": <millis>, "data": {
    "tasks": [ { "task": {...Task...}, "dirtyVersion"?: n, "syncedVersion"?: n,
                 "alarms": [...], "geofences": [...], "tags": [...],
                 "comments": [...], "attachments": [...],
                 "vtodo"?: "...", "caldavTasks": [...] } ],
    "places": [...], "tags": [...], "filters": [...],
    "caldavAccounts": [...], "caldavCalendars": [...],
    "taskListMetadata": [...], "taskAttachments": [...],
    "intPrefs": {}, "longPrefs": {}, "stringPrefs": {}, "boolPrefs": {}, "setPrefs": {}
} }
```
- Entities are serialized with kotlinx.serialization defaults (`encodeDefaults=false`,
  null fields omitted) — same encoder family is available in kmp (`libs.kotlinx.serialization`).
- Filenames: `auto.<yyyyMMdd'T'HHmm>.json` (auto) / `user.<…>.json` (manual);
  matcher `(auto|user)\.(\d{2,4})(\d{2})(\d{2})[T-](\d{2})(\d{2})\.json`;
  cleanup matcher `auto\.\d{6,8}[T-]\d{4}\.json`; keep newest 7 (`DAYS_TO_KEEP_BACKUP`).
- Native writes to internal `backup.json` first, then copies to the backup dir.

### Import semantics (must be preserved)
- Two passes over the file: **metadata first** (places, tags, filters, caldavAccounts,
  caldavCalendars, taskListMetadata, taskAttachments, prefs), then **tasks**.
- Dedupe/remap: accounts by (type+url or type+username), calendars by uuid then by
  (account,url) — imported uuids remapped onto existing local ones.
- Tasks skipped when: uuid already exists locally, **or** any non-deleted caldavTask's
  remote task already exists locally (post-remap calendar).
- Version-gated migrations (theme colors <8.2, legacy icons, recurrence split <12.8,
  random reminders <15.10, CalDAV parent re-derivation, etc.).
- Prefs are restored with an ignore list (`p_current_version`, `p_backups_android_backup_last`,
  `p_device_install_version`).
- Result: `{taskCount, importCount, skipCount}`.

### KMP availability (checked 2026-10-26)
- `kmp` depends on `:data` (`kmp/build.gradle.kts` `implementation(projects.data)`); composeApp depends on both. All DAOs/entities (incl. `TaskDao`, `CaldavDao`, `DirtyDao`, `VtodoCache`, `RefreshBroadcaster`, `TaskSaver`, `TaskMover`) are reachable from kmp commonMain. ✓
- astrid `api` (`PermaSql` etc.) is in kmp commonMain; `Criterion`/SQL is in the `data` module. ✓
- `MainSettingsScreen` (the settings menu) is in **kmp** (`kmp/.../compose/settings/MainSettingsScreen.kt`); detail-pane routing lives in **composeApp** `App.kt` (a when-branch calling the `*Detail(...)` composables). So: menu row in kmp, pane composable in composeApp commonMain, route + Koin in composeApp. ✓
- No existing AWT/FileDialog usage in composeApp — desktopMain pickers will be new code. ✓

### App-only pieces the importer touches (decision made 2026-10-26)
| Native piece | Location | Desktop treatment |
|---|---|---|
| `lenientJson` / `JsonReader.jsonString` / `forEach` | app (`JsonReaderExtensions`, android.util.JsonReader) | Replaced by whole-file kotlinx `Json { isLenient = true; ignoreUnknownKeys = true }` + `JsonElement` navigation (spec simplification, accepted) |
| `Upgrade_15_13.canonicalized` (V=151300) | **kmp jvmCommonMain** ✓ | use directly |
| `Upgrade_15_10.migrateRandomReminder` (V=151000) | app, pure (prefs + defaultAlarms) | port to kmp backup package (small) |
| `Upgrade_14_11.fromLegacyFlags` | app, pure (flag bits → alarms) | port (small) |
| `Upgrade_14_13.VERSION`=141300, `Upgrade_13_2.VERSION`=130200, Upgrader V6_4/V12_4/V12_8/V8_2/V9_6 | app constants | replicate as consts in kmp backup package |
| `getAndroidColor` (legacy index → ARGB) | app + kmp R.color resources | port as pure index→hex table in kmp |
| `migrateLegacyIcon` (index → icon string) | app (map + `TasksIcons` consts, which are kmp) | port the map to kmp |
| `FilterCriteriaProvider.rebuildFilter` (only runs for version < 130200, i.e. 2019-era backups) | app-only, Android-heavy (Context/Hilt/astrid CriterionInstance) | **NOT ported**: guarded no-op with a warning log + documented limitation (pre-13.2 backups won't have filter SQL rebuilt; not realistic for real-world data) |

## Scope

### Phase 1 — Minimal: manual backup + restore (from the Backups pref pane)

**Goal:** a "Backups" pane on desktop with:
1. **Backup directory** row — shows current dir; click → directory picker. Default:
   `~/Documents/Tasks.org/backups` (created on first use). Stored as a plain path string
   under `p_backup_dir` (native stores a Uri string — see risks).
2. **Backup now** — exports a full dump as `user.<ts>.json` into the directory, with a
   progress dialog (non-cancellable, like native), success toast/summary with file name.
3. **Import backup** — file picker restricted to `.json` (default location = backup dir)
   → confirmation dialog stating the restore is **additive** (existing tasks skipped,
   new ones inserted; settings restored) → runs the importer → result dialog with
   `{imported, skipped, total}`.

**Non-goals (Phase 1):** Google Drive, Android Auto Backup, automatic backups,
staleness warnings, old-file cleanup, any `app/` (Android) changes.

**Acceptance criteria (Phase 1):**
- A1. Pane reachable from desktop Settings; directory row shows + changes the dir.
- A2. Backup now produces a well-formed `user.<ts>.json` matching the format above.
- A3. **Cross-device: a backup created on an Android device restores on desktop** —
  metadata lands, new tasks import, existing skipped, counts shown, app stable after.
  (Alex's explicit first test.)
- A4. Round-trip test in-repo: export from a seeded DB → import into a fresh DB →
  equivalent state (tasks, tags, filters, places, caldav accounts/calendars, prefs).
- A5. Forward compatibility: unknown JSON keys are skipped with a log (native behaviour),
  so newer Android versions can write files desktop can still ingest.
- A6. Filtered `:composeApp:desktopTest` green; Codacy 0 new issues (see codacy-triage).

### Phase 2 — Automatic backups (opportunistic)

**Constraint (Alex):** the desktop app is not resident. There is no WorkManager
equivalent to run "at midnight". So auto-backup is **opportunistic catch-up**:

- On app start (and on foreground return to the main screen), the supervisor checks
  `p_backups_enabled` (default true) and `p_last_backup` (millis; unset = never):
  - `now − lastBackup ≥ 24h` (or never) → run an `auto.<ts>.json` export in the
    background (non-blocking, `NonCancellable`, failure logged + surfaced once),
    set `p_last_backup` on success.
  - Then run the cleanup: keep the newest 7 `auto.*.json` in the backup dir
    (native `DAYS_TO_KEEP_BACKUP` semantics).
- Pane gains: **Automatic backups** switch, a **last backup** summary line
  (`last_backup`, `last_backup_never` strings), and a staleness warning — reuse the
  native rule: `stale = lastBackup < today−2d` (local only; the Drive/Android
  "remote" dimension doesn't exist on desktop), shown amber unless
  `p_backups_ignore_warnings` is on (Advanced → Ignore warnings switch, like native).
- `p_last_backup` uses the **same pref key** as Android, so a machine running both can
  share the staleness state (harmless either way since each side writes the same millis).

**Non-goals (Phase 2):** resident daemon/launchd agent (documented limitation: no backup
happens while the app is closed — the next start catches up), Drive upload,
Android Auto Backup.

**Acceptance criteria (Phase 2):**
- P1. With `p_last_backup` older than 24h (test: override the pref), next app start
  produces an `auto.*.json` and updates `p_last_backup`.
- P2. Switch off ⇒ no auto export on start.
- P3. Stale (>2d) shows the amber warning; ignore-warnings hides it.
- P4. Cleanup keeps exactly the 7 newest `auto.*.json`; `user.*` files untouched.
- P5. Unit tests for the scheduling decision + cleanup (pure functions over fake
  clocks/file listings).

## Approach

### Phase 1
**kmp — new `org.tasks.backup` (shared):**
- `BackupFileFormat.kt` — filename build/parse + matchers (port of `BackupConstants`,
  minus the DocumentFile/Drive `File` overloads).
- `LocalBackupExporter.kt` — ctor-injected DAOs (same set as native exporter),
  writes the JSON to a `Path`/`OutputStream` in the backup dir. kotlinx.serialization
  `Json` (defaults off) for entities; hand-written object scaffolding for the
  top-level shape, exactly as native does.
- `LocalBackupImporter.kt` — reads the whole file (desktop-scale; native's streaming
  `JsonReader` has no common equivalent — whole-file lenient parse is the accepted
  simplification; the two-pass structure and all skip/remap/migration logic is
  preserved 1:1), `ImportResult { taskCount, importCount, skipCount }`.
- `DesktopBackupsViewModel.kt` (kmp, like the other pane VMs) — dir path, in-flight
  state, last-import result; actions: `setDirectory(path)`, `backupNow()`,
  `importFrom(path)`. No Android types.

**composeApp:**
- `BackupsDetail.kt` pane composable reusing the existing settings card/row primitives.
  Entry row in the settings screen (the pane-scaffold from PR #4799 is **not** on
  `main` yet — navigate per whatever mechanism exists on main at implementation time;
  rebase/tidy after #4799 lands if the scaffold changes).
- Desktop pickers via `desktopMain` (AWT `FileDialog` directory/file modes — native
  macOS chrome; or Compose `DirectoryPicker` if it suffices). Progress dialog for
  Backup now; result dialog for Import.
- Koin module wiring; kmp string resources (`backup_directory`, `backup_BAc_export`,
  `backup_BAc_import`, `last_backup*`, … — reuse native keys where the text matches).

### Phase 2
- kmp: `BackupScheduler.kt` — pure decision function
  `fun shouldRunAutoBackup(lastBackupMs: Long?, now: Long, enabled: Boolean): Boolean`
  + cleanup function over a file listing; wired from desktop start/foreground in
  `main.kt`/main screen VM. Pane additions as above.

## Risks / open questions

1. **Serialization defaulting.** Native emits entities with `encodeDefaults=false` and
   omits nulls. The desktop exporter must match so Android can read desktop files;
   the desktop importer must be lenient the other way (unknown keys skipped). Validate
   by diffing a desktop-exported file against an Android-exported one over the same DB
   (key set, not bytes).
2. **`p_backup_dir` key semantics** — Android stores a Uri string, desktop a path.
   If an Android backup file is *restored* onto desktop, its `stringPrefs` include
   `p_backup_dir` as an Android Uri → **desktop importer's prefs ignore list must
   include `p_backup_dir`** (and any other platform-specific keys discovered during the
   cross-device test).
3. **`Upgrade_*` migration classes** may live in `app/` — verify and port to kmp the
   ones the importer references (only those below common versions actually matter for
   recent backups, but the code paths must compile).
4. **Pane navigation scaffold** depends on PR #4799 (open). Decision: build against
   main's current navigation; reconcile after #4799 merges.
5. ~~**Backup dir default**~~ **DECIDED (Alex, 2026-10-26): visible directory**
   `~/Documents/Tasks.org/backups` (kmp `PlatformConfiguration`/user-docs dir on all
   three desktop OSes, created on first use). Alex considered `Tasks-org`/`Tasks_org`
   over a (mistaken) Windows dot-in-name worry — `.` is legal in Windows dir names
   (reserved: `< > : " / \ | ? *`); `Tasks.org` kept to match the app name.
6. **Version code** — desktop writes its own `TasksBuildConfig.VERSION_CODE` into
   `version`; the importer already treats it as an opaque gate. Fine as long as desktop
   version codes are not accidentally in the same range as Android's in a misleading way
   (check actual values at implementation).

## Verification plan

- Unit (kmp): filename parse/build; format key-parity (export → parse → compare key
  sets); importer skip/remap/migrations over seeded fixtures (including a fixture
  **recorded from a real Android export** — the A3 test file); scheduler + cleanup (P2).
- Desktop (desktopTest, filtered): pane renders, dir row, backup-now flow with a temp
  dir, import flow with a fixture file. Full-suite hang gotcha: always filter.
- Live (after asking): build via `tools/build-macos.sh` from this worktree
  (`TASKS_ORG_REPO=.../worktrees/backup-desktop`), swap bench, Alex runs the
  Android-backup restore (A3).
- Codacy after each push: 0 new issues (codacy-triage skill).

## Decision log
| Date | Decision | Rationale |
|---|---|---|
| 2026-10-26 | Two phases: manual core, then opportunistic auto | Alex's split; app not resident |
| 2026-10-26 | kmp implementation, zero `app/` changes | native engine is app+Android-bound; shared `data/` module makes kmp viable |
| 2026-10-26 | Whole-file lenient parse instead of streaming JsonReader | no common streaming reader; desktop file sizes fine |
| 2026-10-26 | Desktop exporter/importer are *new* classes, not edits of native ones | keeps native untouched, DI contexts differ |
| 2026-10-26 | Same pref keys (`p_backups_enabled`, `p_last_backup`, `p_backups_ignore_warnings`), platform-aware ignore list for `p_backup_dir` on import | shared staleness state across devices |
| 2026-10-26 | Pure migration helpers (colors, icons, legacy flags, 15.10 random reminder) ported to kmp backup package; `rebuildFilter` (only <13.2) left as guarded no-op | 13.2 = 2019-era backups; porting astrid CriterionInstance machinery is disproportionate; documented limitation |

## Status
- Draft (2026-10-26). Awaiting Alex's review, then Phase 1 implementation.
