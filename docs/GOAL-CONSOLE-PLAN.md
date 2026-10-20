# Goal console integration plan

Task: `apk-console-plan`. Planner owns this document and `~/codex-runtime/apk-goal-console/evidence/planner.json`. This is a source review and implementation directive; it does not establish a build, installation, reachable deployment, live capture, or executed user workflow.

Baseline: `~/.codex/agents/console/CONTRACT.md`, version 1. Root reports all five assignments acknowledged and running. Existing services and provider choices remain independent: executor33995, native4500, hub8788, coordinator8791, voice8790 and proxy pool8080.

## Recommendation and identity

Use the proposed Python stdlib console bridge at `http://127.0.0.1:8792`. It gives the APK access to existing Termux worker controllers and hub contracts without moving their ownership into Android. Keep its operational store under `~/codex-runtime/goal-console`; keep implementation evidence under `~/codex-runtime/apk-goal-console/evidence`.

| Identity | Meaning and authoritative observation |
| --- | --- |
| Original CEO thread | `01a14cf9-ea44-7340-85b3-ea642b48f457`; select from goal-core state. Root has verified `thread/goal/get` for it through the live tracker worker. Preserve this UUID. |
| Persisted CEO goal | Native goal objective, status, budget and usage. A successful worker RPC establishes a fresh goal observation; it does not establish ownership of the CEO's live turn. |
| Specialist | One saved native UUID per role from `goal-team status --live`; its controller, native process, availability goal and assigned task are separate fields. |
| Executor session | APK `SessionEntity.sessionId` for a particular exec-server endpoint; associates shell processes, output and workspace operations. It is not a CEO or specialist thread. |
| Native4500 | An independent WebSocket app-server. It does not own the active CEO CLI turn. Neither a successful connection nor queue insertion proves delivery into that turn. |

Keep executor URL settings separate from console URL settings. Switching an executor to a remote sandbox must not change the phone-local console/voice endpoints or replace the CEO identity.

## UI and file ownership

| Owner | Scope and deliverable |
| --- | --- |
| Coder | `com/example/console/**`, console tests, `ui/MainScreen.kt`, necessary `MainActivity.kt` integration. Default landing is Console; retain the existing six executor tabs and their behavior. |
| Debugger | `agents/console/{server.py,store.py,test_console.py,README.md}`. Implement bridge, durable intents/events, exact adapters and fixture tests. |
| Browser | `com/example/voice/**`, voice tests and `evidence/voice-integration.md`. Publish the actual `VoiceConsole` signature there; coder owns its integration into MainScreen. |
| Tracker | `evidence/{build-plan.json,tracker.md}`. CI/source/signing continuity plan and correlated task observations. |
| Root | Shared contract decisions, bridge supervisor/startup, integration, authoritative build, installation and acceptance. |
| Planner | This plan, planner evidence and own compact handoff. No implementation duplication or edits to other owners' files. |

The console must show:

1. CEO objective, exact native status and usage, observation source/time, and explicit pause/resume actions. Goal state and runtime state stay separate.
2. Five specialist rows with actual role/thread, controller/native state, goal, active task and result/evidence. Missing controllers show unavailable or cached observations.
3. Assignment form with role, immutable task ID, scope, acceptance, instruction and dependencies. Resolve actual owner from the saved role mapping. Show intent, dispatch, ACK and result as separate observations.
4. Hub ledger for the original goal, task detail, dependencies, blockers, next action and evidence. Native mailbox records remain separately inspectable.
5. Connections with configured versus observed availability, exact backend schema, real test/add/remove results and optional local favorite metadata.
6. Paged durable events with full expandable content and a persisted cursor. Refresh/recreation reads state; it does not repeat submissions.
7. Voice screen using the existing voice lab and showing source URL, binding, actual page capture state, candidates and provider errors. Opening a screen starts no microphone.

## Existing adapter routes

These routes are present in inspected source; reachability was not tested by this assignment.

| Source | Existing operation |
| --- | --- |
| `agents/team/cli.py:control,statuses,main` | Unix controller `status` with `live:true`; `notify`; `native` with method/params; `restart`; `shutdown`. CLI: `goal-team status --live`, `send ROLE --id ID --file FILE`, `results --id ID`, `events --after N`, `native ROLE METHOD --json JSON`, explicit `up/restart/stop`. |
| Worker native adapter | `goal-team native tracker thread/goal/get --json '{"threadId":"CEO_UUID"}'` reads the original goal without resuming it. `worker.py` uses `params.setdefault("threadId", self.thread)`, so an explicit CEO ID survives. |
| Hub8788 | `GET /health`; `GET /v1/tasks/schema`; `GET /v1/tasks[?parentGoalId=UUID&after=N&limit=N]`; `GET /v1/tasks/summary?parentGoalId=UUID`; `GET /v1/tasks/ID`; `GET /v1/tasks/ID/events?after=N&limit=N`; `POST /v1/tasks`; `PATCH /v1/tasks/ID`; `POST /v1/tasks/ID/transition`. Updates/transitions require `expectedRevision`. |
| Hub connections | `GET /v1/backends` is configuration; `GET /v1/capabilities` supplies discovery observations; `PUT /v1/backends/ID` configures one backend; `POST /v1/backends/ID/test` tests it; `DELETE /v1/backends/ID` removes its registration. |
| Hub native adapter | `GET /v1/native/protocol?documents=1` exposes the inspected protocol snapshot; `GET /v1/native/status` reports the independent native adapter. Generic native RPC jobs exist, but are not a route into the active CEO CLI turn. |
| Voice8790 | `GET /api/health`, `/api/state`, `/api/candidates`, `/api/inbox?after=N`, `/api/wait?after=N&timeout=S&include_candidates=true&candidate_after=N`; `POST /api/ack {"eventId":ID}`; `POST /api/candidates/accept {"utteranceId":ID}`; `POST /api/speak {"text":TEXT}`; `POST /api/events`; `GET /api/audio/ID.wav`. |
| Voice transcription | `POST /api/transcribe`: WAV request bytes, `Content-Type: audio/wav`, `X-Utterance-Id`, and JSON-valued `X-Capture-Metadata`. Keep existing endpointing, diagnostics and provider choices. |
| Voice binding | `session.py status/bind/unbind` is a local CLI, not an existing HTTP route. `control.py next --thread-id UUID` verifies the enabled matching binding and baseline before reading. Binding does not start capture or a model. |

The existing native schema documents `thread/goal/get {threadId}` and `thread/goal/set {threadId,status?,objective?,tokenBudget?,origin?}`. `origin` is `user` or `automatic`. Status values are `active`, `paused`, `blocked`, `usageLimited`, `budgetLimited`, `complete`. For explicit console pause/resume, send only `{threadId,status,origin:"user"}` after resolving the known original/specialist identity. Omit objective/budget so a status edit does not overwrite concurrent content or accounting. Record and read back the returned goal. Root has established status reading; observable lifecycle behavior of the already-running CEO process remains a separate acceptance requirement. Do not pause the CEO during verification.

## Contract decisions required during integration

These are concrete gaps/translation details in contract version 1, not claims that the concurrent implementations are missing them.

| Gap | Required resolution and owner |
| --- | --- |
| CEO `live` ambiguity | Debugger distinguishes a fresh goal RPC, worker/controller health, and ownership/receipt by the original CLI. A fresh persisted-goal observation cannot label CEO message delivery online. Preserve source/time and partial errors. |
| Assignment versus ledger roles | Native specialties are planner/coder/debugger/tracker/browser. Hub `TASK_ROLES` accepts only director/coordinator/tracker/specialist/verifier. Use a valid hub role, generally `specialist` (tracker may use `tracker`), retain native role in the bridge mapping and actual `owner="native:UUID"`. Do not send `role:"coder"` to the hub. |
| Two durable stores | Debugger persists immutable assignment intent and native↔hub ID mapping before effects. Hub creation must precede native send. Hub creation has no automatic idempotency header: reconcile an existing fixed ID and compare assignment content after an uncertain response. A repeated identical request must return its original correlated receipt. |
| Dependencies and completion | Map native dependency IDs to existing same-goal hub nodes. Check they are done before dispatch. Native ACK can map queued→ready→running using current revisions; native done supplies evidence and moves to verifying. Root's reviewed acceptance completes the hub task. `needs_input` maps waiting with a specific blocker/next action; unknown dispatch remains explicit. Do not jump running→done: the hub rejects it. |
| Revision conflict | On hub409, refresh and expose the conflicting revision/content. Do not overwrite another writer or reinterpret a specialist result as integration acceptance. Bridge ownership of sync writes must be explicit. |
| Immutable native terminal tasks | Native done/failed/needs_input are terminal receipts for that immutable ID. A follow-up execution gets a new ID; refreshing a task or resolving a ledger blocker does not replay it. |
| Connections request shape | Freeze an exact Android/backend envelope, for example `{id,backend:{kind,baseUrl,label,enabled,options?,apiKey?,pairLocalKey?},favorite?}`. Bridge extracts the ID for `PUT /v1/backends/ID`; sends only the Backend object upstream. Hub rejects unknown `id`/`favorite` fields in its body. Favorites remain local metadata. Its accepted kinds are codex-proxy/http/codex-app-server/mcp-http/mcp-stdio. |
| Discovery versus testing | `/v1/backends` alone supplies no availability claim. Native discovery may be `not_connected`; HTTP may be `configured_not_probed`. A backend test can return HTTP200 with `ok:false`; preserve its body. Catalog/model-list testing is a connection observation, not inference or completion of an action. |
| Events | Define `after` as exclusive, wait in seconds, bounded wait and limit, and stable instance identity. Persist upstream cursors and deduplicate by source/native sequence. Advance the APK cursor only after saving/applying the page; drain `hasMore` before waiting. Key the cursor by console instance/store identity so a new empty store cannot silently strand it. |
| Timeouts/partials | Existing ViewModel health client uses a three-second timeout; team CLI controller calls can take fifteen seconds. New snapshot collection must have a bounded concurrent budget and return subsystem errors/last observed times. Give event long polling its own compatible HTTP timeout. Use IO and lifecycle cancellation. |
| Task manager scope | Current contract exposes ledger reads and assignment-driven sync. It has no generic console PATCH/transition route. Implement the agreed ledger view; label additional task-edit/cancel APIs unestablished rather than wiring buttons to guessed paths. |
| Voice observation | `/api/health` proves service health; binding proves target configuration. Neither proves a live microphone. `/v1/voice` must keep those observations separate from WebView `voiceLab.status()` and track states. |

## CEO text and voice delivery: recommended next addition

**Add a durable CEO inbox and explicit receipt-ACK/wait adapter.** Contract version 1 covers goal status/control; it currently defines no typed message delivery into the original CEO conversation. Worker `thread/goal/get` solves status. It does not make native4500 the owner of the active CLI turn. Do not start/resume a parallel CEO session to fill this gap.

Proposed routes below are **new**, pending root integration:

| New route | Contract |
| --- | --- |
| `POST /v1/ceo/inbox` | `{messageId,threadId,text,source:"text"|"voice",sourceEventId?}`. Target must be the configured original CEO. Persist immutable text/source identity before returning. Same ID/content returns the original item; conflicting content returns409. Response reports queued status and receipt identity, not native message injection. |
| `GET /v1/ceo/inbox?threadId=UUID&after=N&wait=S&limit=N` | Durable sequence page, bounded optional wait, explicit unacknowledged state. Reading never ACKs. Cursor is an observation position, not proof of receipt; retain/recover earlier unacknowledged items. |
| `GET /v1/ceo/inbox/MESSAGE_ID` | Current queued/received state and correlated receipt; APK refresh can reconcile a timed-out POST by the same immutable ID. |
| `POST /v1/ceo/inbox/MESSAGE_ID/ack` | `{threadId}` from the original consuming session. Idempotent receipt ACK only. Acceptance/execution and any task result remain separate. |

Use the console's operational store, not a new memory subsystem. Add a CLI adapter such as `goal-console inbox wait --thread-id UUID --after N --timeout S` and `goal-console inbox ack MESSAGE_ID --thread-id UUID`. The original CEO session intentionally consumes the returned tool data in its existing conversation and ACKs its exact IDs. Show “queued — CEO has not acknowledged” until that receipt. Last consumer observation and ACK are stronger evidence than a live worker's ability to read the CEO goal.

For idle operation, attach a **direct pending native inbox-wait tool to the original CEO runtime** when that runtime integration is available. A Python/CLI long-poll command is a useful tool-mediated adapter; an exec wrapper that yields and requires repeated model polling does not establish zero-inference idleness. Do not substitute another app-server process resuming the same CEO UUID. Until the original runtime consumes items, keep them queued and make that limitation visible.

Voice8790 already owns durable recognition, candidates and transcript ACKs. The console delivery journal should reference its committed `user.transcript` events using stable `(source,eventId,targetThread)` identity; retain the source ledger and diagnostics. Candidate events enter the CEO inbox only after existing explicit acceptance creates a committed transcript. Respect the existing binding and baseline; do not import historical speech on binding/recreation.

If console mirrors committed speech into its delivery journal, persist the mapping before delivery. After original-CEO receipt, record the inbox ACK and then ACK the source voice event. A source-ACK failure remains `sourceAckPending` and can be reconciled idempotently; it must not cause a second CEO delivery or loss of the source event. Reading a candidate is not acceptance. A receipt is not proof an instruction executed.

Spoken replies continue through existing `/api/speak`; generated audio and observed `playback.ended` are distinct. No additional model API or replacement provider is needed for the delivery layer. The user requested text mode for this work; no binding, microphone or TTS operation was performed by the planner.

## VoiceConsole integration

Reuse the existing loopback page rather than recreating its PCM/VAD/STT/TTS pipeline. The manifest already declares RECORD_AUDIO; the WebView still needs the explicit Android runtime grant and its WebChromeClient audio permission bridge. Preserve source URL, candidate confirmation UI, diagnostics, document UI, TTS pool selection and provider errors.

The current page exposes `globalThis.voiceLab={start,stop,commit,status,configure,prepareReload}`. Initial page setup installs click listeners and polls state; it does not call start. Browser owns the wrapper's lifecycle, explicit Start/Stop and page capture observations. Its durable `evidence/voice-integration.md` publishes `com.example.voice.VoiceConsole(url: String = DEFAULT_VOICE_URL, modifier: Modifier = Modifier)`, with default URL `http://127.0.0.1:8790/`; the note labels implementation in progress. Coder integrates that composable; no concurrent edits to MainScreen by browser.

**Existing queued-audio issue:** `public/app.js:211–220` imports every server audio item still marked `queued`; `start()` calls `pumpAudio()`. Starting capture in a new page can therefore make historical queued speech eligible. Browser must define a fresh-page playback baseline in the Android-owned adapter, preserve pending source records, and prove that recreation/visibility/Start does not replay historical speech. The existing service sources are outside browser's ownership; any necessary service change must be returned to root as a scoped dependency. Page history may remain inspectable.

Stopping capture and pausing/stopping a goal remain distinct. Existing stop can commit pending speech; the wrapper must preserve that established behavior and surface unfinished upload/playback. Navigation/recreation must release a capture it owns and must never auto-start on return. Background capture is not established by current APK foreground-service declarations.

## Integration order and acceptance

| Gate | Owner, dependency and next action | Acceptance |
| --- | --- | --- |
| 1 | Root resolves the JSON/role/state/CEO-delivery decisions above; debugger and coder align exact response shapes. | A single explicit contract; no guessed message or voice-binding route. |
| 2 | Debugger finishes console adapters/store and publishes tests; coder builds against fixture responses in parallel. | Real CLI/hub adapters; durable intent and event recovery; no inference during refresh. |
| 3 | Browser publishes actual VoiceConsole signature and stopped-state/replay checks; coder integrates it. | Existing voice pipeline usable by explicit user start; no capture or historic playback on page entry. |
| 4 | Root integrates finished files; tracker supplies authoritative CI/signing plan. | Full current source snapshot and tests build reproducibly; version/signing continuity verified without discarding concurrent edits. |
| 5 | Root installs and runs phone acceptance, keeping CEO active and mic stopped. | Source, packaged, installed, reachable and executed evidence are reported separately. |
| 6 | Root adds the proposed CEO inbox if typed/voice message delivery is in this release. | Original-thread tool consumption/ACK demonstrated; no substitute CEO and no claim of native user-message injection. |

Required bridge tests: duplicate/conflicting IDs, hub creation timeout/reconciliation, dispatch timeout/unknown outcome, immutable terminal ID, dependency/role mapping, complete revision lifecycle and409 conflicts, partial subsystem outage, existing CEO goal read without thread/start/resume/turn/start, status-only goal patch preserving objective/budget, ordered event pagination/cursor restart, exact backend schema and HTTP200/ok:false test results. Use isolated fakes/stores; never mutate the CEO goal during verification.

Required Android tests: snapshot/error parsing, distinct executor/console settings, assignment reconciliation after timeout and recreation, actual result/evidence expansion, page draining and persisted instance-specific cursor, refresh cancellation while inactive, separate runtime/goal controls, connection add/test/remove wire bodies, and stale/partial observations.

Required voice checks: page load/recreation/visibility make zero getUserMedia calls, explicit permission denial remains visible, current source page API is available, historic queued audio does not replay, candidates remain uncommitted until explicit acceptance, provider errors remain visible, and native capture labels follow observed track state. Real microphone capture is outside the stopped-mode acceptance; any later live test needs the user's explicit voice-start action.

If adding CEO inbox, also test enqueue timeout reconciliation, identical/conflicting IDs, cross-target rejection, read-without-ACK, original-thread ACK, recovery of older unacknowledged items, committed voice/candidate separation, binding baseline, two-store ACK interruption, no duplicate TTS/dispatch, and queued status when no original-runtime consumer exists.

Existing regression suites provide foundations, not evidence for the new UI: APK `ExecutorEnvironmentTest`, `CodexWebSocketClientTest` and `ExecRepositoryTest`; team `store-check.py` and fixture-mode `verify.py`; hub `tests/tasks.test.mjs` and native adapter tests; voice `test-stt-contract.mjs`, `test-document-control.py`, document/TTS suites. Tracker owns exact authoritative build commands. Planner did not execute these suites or call model/provider APIs.

## Source anchors

- APK `ui/MainScreen.kt:201–255` (six tabs); `ui/viewmodel/MainViewModel.kt:251–363` (client timeout, endpoint/connection controls); `data/db/Entities.kt` (executor sessions); `data/repository/ExecRepository.kt:20–24` (default33995); manifest (declared RECORD_AUDIO and existing service).
- `agents/team/cli.py:48–60,113–126,165–205`; `worker.py:256–290,375–389`; `store.py:64–76,307–344,346–359`.
- Hub `service/server.mjs:63–93,172–195,204–218`; `service/protocol.mjs:4`; `service/tasks.mjs:5–30,99–115,118–156`; `service/task-contract.mjs`.
- Native snapshot `adapters/schemas/ClientRequest.json` and `codex_app_server_protocol.v2.schemas.json`: ThreadGoalGetParams, ThreadGoalSetParams, ThreadGoalMutationOrigin, ThreadGoalStatus.
- Voice `server.mjs:269–365`; `control.py:155–170`; `session.py`; `public/app.js:118–164,186–245`.
- `goal-core/BLUEPRINT-DRAFT.md` preserves the user's hierarchy. The plan does not reinterpret its numerical labels or replace the user's memory design.
