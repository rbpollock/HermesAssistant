# HermesAssistant Compose UI — State & Journey Audit

> Date: 2026-08-17 · Target: v1.9.2 (Compose surface as launcher)
> Method: read every UI-relevant file (AssistantScreen.kt, AssistantViewModel.kt,
> VoiceInput.kt, AudioPlayer.kt, RelayClient.kt, ChatHistoryStore.kt,
> HermesForegroundService.kt, ListeningOrb.kt, SettingsScreen.kt), enumerated
> every reachable state × visible message, generated 9 user journeys, and
> cross-referenced. Every finding below was verified in code, not inferred.

---

## 1. State inventory

### 1.1 Sheet geometry (3 states)
| Value | Anchor | Content shown |
|---|---|---|
| PEEK | 16% of screen | Drag handle + header (chevron/chips/gear) + orb + status + **speak button + text field** (see F2) |
| HALF | 50% | Same as PEEK, more room |
| FULL | 94% | + session header + message list (LazyColumn) |

### 1.2 Orb states (4 declared, 2 reachable — see F1)
| OrbState | Mapped from statusState | Actually reachable? |
|---|---|---|
| IDLE | anything else | YES (most common) |
| LISTENING | StatusRingView.State.LISTENING | **NO — never set anywhere** |
| THINKING | State.THINKING | YES ("You:…", "Thinking...", "Sending queued…") |
| SPEAKING | State.SPEAKING | YES ("Speaking...") |

### 1.3 Status line (statusText) — every string that can appear
Source → string → statusState:
- relay.onConnected → "Connected to server" → CONNECTED
- relay.onDisconnected → "Connection closed — reconnecting..." / "WS Error: … — retrying..." → IDLE
- relay.onStatus → "Thinking..." / "Sent to live session" / "Speaking..." (server frames) → THINKING
- relay malformed frame → "⚠ WS frame: …" → THINKING (via onStatus)
- voice.onError → "$message. Try again." → IDLE
- voice.onThinking → "Thinking..." → THINKING
- sendUserMessage → "You: $userText" → THINKING
- sendUserMessage offline → "Offline — message queued" → IDLE
- handleDictatedText → "Offline — queued: $clean" → CONNECTED (odd — see F7)
- handleAssistantReply → "Hermes: $message" → CONNECTED
- onReplyReady → "Hermes: $replyText" → CONNECTED
- startWakeWord → 'Listening for "Hey Hermes"' → IDLE
- selectSession → "Replying to: $title" / "Reply target cleared — daily phone chat" → CONNECTED
- sendNextQueued → "Sending queued: …" / "All queued messages sent" / "Reconnect lost — message re-queued" → THINKING/CONNECTED/IDLE
- audio.onStatus → "Response ready — tap to play" → CONNECTED
- onAudioEnd/onSpeakingStarted → "Speaking..." → SPEAKING
- settings (via setStatus) → "Allow installs…", "Downloading update...", install result → CONNECTED/THINKING

### 1.4 Sub-line (subTextLabel) — 3 states
1. "Tap to speak · wake word: \"Hey Hermes\"" (default)
2. "Reply goes to: $replySessionTitle — tap to speak" (reply target armed)
3. "$n messages queued — will send when connected" (offline queue non-empty)
Priority: reply target > queue > default. **Both reply AND queue can be true simultaneously; queue count is silently hidden when a target is armed (F9).**

### 1.5 Speak button label — 2 states
- "LISTENING FOR WAKE WORD" (idle)
- "TAP TO CANCEL" (STT or dictation active)
**No distinct label for "parked response available" (F6).**

### 1.6 Session header (FULL only)
- "Replying to: <title>" (target armed) / "No session targeted — Daily phone chat" (not)

### 1.7 Message bubbles — 4 visual variants
user (blue #16233D, right) · user+queued (amber #3B2F1A, right, ⏳) · hermes (slate #1E293B, left) · notify (centered, outlined, blue text) · user+injected (green ✓ suffix)

### 1.8 Chips — selected (filled) vs unselected (outlined), session-colored

---

## 2. Findings (verified)

### F1 — CRITICAL: the LISTENING orb state is dead code
No code path calls `setStatusInternal(_, State.LISTENING)`. `onListening()` (VoiceInput) only plays a chime; `onStateChanged(STT/DICTATION)` only flips the button label and `voiceActive`. So while the mic is open, the orb stays **IDLE grey**, and the `rmsLevel` fed by `onRmsChanged` is computed but never displayed (ListeningOrb only draws amplitude rings in LISTENING state). The brief's core requirement — "listening-state visual … reuse amplitude data … don't fake it" — is not met: the amplitude data is collected and thrown away, and the user gets no green/audio-reactive listening state.
**Fix:** on state→STT/DICTATION set status "Listening..." with State.LISTENING; on cancel/error/results return to wake-word status. Remove the double-purpose `voiceActive` in favor of statusState as the single source of truth for the orb.

### F2 — CRITICAL: PEEK state overflows — the sheet crams full content into 16%
At PEEK (16% of screen ≈ 120dp on a 750dp screen), the sheet renders: drag handle + header row + orb + status + subtext + speak button + text field. The orb Box is `weight(1f)` and shrinks, but the fixed elements (handle ~15dp, header ~48dp, button 52dp, text field ~56dp, spacers) alone exceed 120dp. Result: text field/button clipped or orb crushed to nothing; the "at rest" state is not minimal, it's broken-cramped.
**Fix:** PEEK should show a slim strip (drag handle + compact status + speak button only — mirroring the legacy collapsed strip that this replaced); text field and full orb only from HALF up.

### F3 — CRITICAL: message-bubble tap is a no-op in Compose
`MessageList(onSelectMessage = {})` — the legacy tap-to-target-session behavior (selectSessionFromMessage) is not wired. Bubbles are `Surface(onClick = …)` so they *look* tappable but do nothing.
**Fix:** wire onSelectMessage → viewModel.selectSessionFromMessage(m) (and refresh chips).

### F4 — HIGH: notification tap-through uses the RAW title, not the stripped one
`handleTargetSessionIntent` does `replySessionTitle = title` (e.g. "Hermes finished · Solar System Trivia") while every other path strips via `sessionTitleFromNotify` (→ "Solar System Trivia"). The header/sub-line show "Replying to: Hermes finished · …" and the appended notify bubble reads "Hermes finished · Solar System Trivia — <question>". Also `sessionStore.upsert` strips the title but `replySessionTitle` keeps the raw one — so the chip (stripped) and the header (raw) disagree.
**Fix:** strip before assigning: `replySessionTitle = sessionTitleFromNotify(title, sessionId)`.

### F5 — HIGH: status text is overwritten before the user can read it
Several sequences set a meaningful status then immediately clobber it:
- `onError` sets "$message. Try again." then calls `startWakeWord()` which sets 'Listening for "Hey Hermes"' — the error is invisible (F5a).
- `handleDictatedText` sets "Offline — queued: $clean" then `startWakeWord()` — queued confirmation invisible (F5b).
- `sendUserMessage` sets "You: $text" then the server's `{"type":"status","message":"Thinking..."}` arrives — fine (intended), but then `onAudioEnd` sets "Speaking..." and `onQueueEmpty` may immediately schedule auto-listen which sets "You: …" again — the "Hermes: $reply" bubble/status can be skipped over so fast the user never sees the reply line.
**Fix:** (a) error path should set the error status AFTER returning to wake word, or show the error in sub-line; (b) queued confirmation should live in subTextLabel (it does — but only via pushState, which handleDictatedText calls before startWakeWord — verify ordering); (c) treat the reply status as the settle state (don't immediately overwrite with auto-listen "You: …").

### F6 — HIGH: no visible "parked response" state on the button
When BT-only + no headset, `playAudio` parks the file and sets status "Response ready — tap to play", but the speak button still says "LISTENING FOR WAKE WORD". The user must read the status line to know a tap plays a parked reply. Journey 5 ("No connected Bluetooth device") is only half-communicated.
**Fix:** expose `pendingAudio` in UiState and label the button "TAP TO PLAY" when parked.

### F7 — MEDIUM: "Offline — queued: …" reports CONNECTED state
`handleDictatedText` sets statusState CONNECTED for an offline condition; orb renders IDLE anyway (mapping), but the semantic state is wrong and would break if the mapping tightens. Also `updateQueueBadgeText`/`subTextLabel` is correct — the status is just redundant.

### F8 — MEDIUM: two sources of truth for "is listening" → inconsistent orb
`voiceActive` (from VoiceInput) drives auto-raise; `statusState` drives the orb. They disagree during the 300ms settle and after errors. E.g. `onError` → statusState IDLE but `voiceActive` may still be true until onStateChanged fires. Simplest: single `voiceActive`-derived statusState for all mic states.

### F9 — MEDIUM: queue count hidden when a reply target is armed
`subTextLabel` prioritizes reply badge over queue badge; with 2 queued messages AND a target session, the user sees only "Reply goes to: …" and never the pending count. The chips/queue are both true states that should coexist.
**Fix:** combine: "Reply goes to: X · 2 queued".

### F10 — MEDIUM: session header only visible at FULL
Journey 3 (session-switch confirmation) demands a glanceable affordance. The chips ARE visible at HALF (selected = filled), but the "Replying to: X" header only appears in FULL. At HALF the user must notice chip fill; the sub-line "Reply goes to: X" helps but is 12sp gray.
**Fix:** show the session header at HALF too (it's cheap), or make chip selection state much more prominent.

### F15 — MEDIUM: typed/STT message offline → DOUBLE bubble
`sendUserMessage` appends the user bubble to history (line 339), then when `relay.send` fails it calls `chatHistory.enqueue(userText)` — and `enqueue()` does `append(entry)` again (queued=true). Result: the same message appears TWICE in history (one normal, one ⏳ queued). The dictation path is fine (handleDictatedText → enqueue directly, no prior append); only the typed/STT path double-appends.
**Fix:** in the offline branch, don't re-append — enqueue only, or replace the existing bubble's queued flag instead.

### F16 — LOW: offline dictation has no visible "dictating" feedback beyond the button
Vosk dictation happens in the service; `onRmsChanged` never fires for it, so even with F1 fixed, dictation shows a static green orb (no amplitude). Acceptable (honest — no amplitude data for that path), but should NOT fake a waveform. Document this.

### F12 — LOW: `onSelectMessage` not the only dead affordance — "queued" bubble tap also dead
Same fix as F3 (single callback).

### F13 — LOW: settings status line is transient & off-ViewModel
SettingsScreen keeps its own `status` state; a server change calls `reconfigureServer()` but the "Connected to server" confirmation lands in the ViewModel status — invisible behind the settings screen. On back, the user sees the new status. Acceptable, but the SAVE button gives no immediate feedback beyond the local "Saved: …" line.

### F14 — LOW: update-check thread hops are duplicated inline
SettingsScreen creates a `Handler(mainLooper)` per button press — fine functionally, but the check/install flows should live in the ViewModel for consistency with everything else (and so the sheet reflects "Downloading…").

---

## 3. User journeys × state cross-reference

Legend: ✅ consistent · ⚠️ inconsistent (finding) · ❌ broken

### J1 — Cold invocation, single session (locked → wake word → sheet rises → reply → idle)
1. Locked phone, app installed, service running → persistent FGS notification "Hermes Assistant is running / Listening for "Hey Hermes"" ✅
2. "Hey Hermes" → chime ✅ → overlay (if draw-over) or `launchMainActivity` → Compose sheet ✅
3. Sheet rises; `beginListening()` → Google STT; orb stays IDLE grey ⚠️ **F1**; speak label "TAP TO CANCEL" ✅
4. Phrase → "You: …" THINKING → server "Thinking..." → audio streams → "Speaking..." ✅
5. Sheet settles to idle — **auto-listen fires after 2.5s and re-opens STT** ("You: …" only after speech); if user stays quiet, STT times out → error → back to wake word ⚠️ **F5a** (error invisible)
6. Cleanup: sheet stays at HALF after the turn (auto-raise only lowers never) ⚠️ acceptable, note in F15

### J2 — Notification-driven targeted reply (background session asks → shade reply → body tap → chip pre-selected)
1. Background session ends / clarify → notify → system notification with Reply action ✅
2. Shade reply → NotificationReplyReceiver → HTTP /chat/message → history reload → bubble with ✓ ✅
3. Tap notification body → AssistantComposeActivity with target extras → `handleTargetSessionIntent` → chip pre-selected ✅ **but header shows RAW title** ⚠️ **F4**
4. Next voice input routes to that session ✅
5. After reply, target is cleared only if `relay.send` succeeds — if offline, target stays armed while message queues ⚠️ minor

### J3 — Session-switch mid-conversation (two chips → tap different → confirm → send)
1. Two chips visible ✅
2. Tap chip B → chip B fills, chip A outlines ✅; sub-line "Reply goes to: B" ✅
3. **At HALF: no header confirmation; at FULL: header appears** ⚠️ **F10**
4. Tap a history bubble to switch target → **nothing happens** ❌ **F3**

### J4 — Offline/degraded network (Tailscale drops → keep talking → queue → reconnect → flush)
1. WS fails → status "WS Error: … — retrying..." IDLE ✅ (relay auto-reconnect)
2. Tap speak → `beginListening` → `voice.startListening` (Google STT needs network → likely ERROR_NETWORK) → onError → `startOfflineDictation()` ✅ (silent switch — see F5a note)
3. Speak → Vosk → handleDictatedText → "Offline — queued: …" then immediately startWakeWord ⚠️ **F5b** (confirmation lost) — but bubble appears with ⏳ and sub-line "1 message queued" ✅
4. Connection returns → onConnected → flushQueueIfAny → "Sending queued: …" → "All queued messages sent" ✅
5. Typed message while offline → sendUserMessage → "Offline — message queued" + enqueue → **bubble appears TWICE** ❌ **F15**

### J5 — No Bluetooth device (reply arrives, autoplay suppressed → tappable)
1. Reply audio arrives → `playAudio` → BT-only + no headset → parked; status "Response ready — tap to play" ✅
2. **Speak button still says "LISTENING FOR WAKE WORD"** ⚠️ **F6**
3. Tap speak → `onSpeakButtonPressed` → pendingAudio branch → plays ✅ (works, but the button didn't say so)

### J6 — Cold app start after reboot (FGS reasserts; no broken first frame)
1. Reboot → FGS not running → user taps icon → Compose sheet ✅ (launcher) → VM init starts FGS ✅ (v1.9.1 fix)
2. First frame: status "Ready" IDLE grey, no messages, sheet PEEK (cramped) ⚠️ **F2**; then beginListening auto-starts STT ✅
3. No crash/empty state ✅

### J7 — Server reconfiguration (gear → change host/port → reconnect)
1. Gear → Compose SettingsScreen ✅
2. Change host/port → SAVE → ServerConfig.save → onServerChanged → reconfigureServer (cancel + reconnect) ✅
3. Status "Saved: http://…" (settings-local) ✅; reconnect result "Connected to server" lands in ViewModel status (hidden behind settings) ⚠️ **F13**
4. Back → sheet shows new connection ✅

### J8 — Wake-word HTTP reply path (service speaks; sheet refreshes)
1. Wake word → dictation in service → POST /chat/message → reply → ACTION_REPLY_READY → onReplyReady → history reload + status "Hermes: …" ✅
2. Sheet (if open) reflects bubble + status; service TTS speaks ✅
3. **Orb state during the HTTP wait: IDLE grey** (status was "Listening for wake word" → then CONNECTED) ⚠️ consistent with F1

### J9 — Injected live session (message typed into a live tmux/screen session)
1. Send to live session → server returns "Sent to live session" status + text frame + injected notify ✅
2. `event=="injected"` → markInjected → green ✓ on the user bubble ✅
3. Status "Hermes: …" from text frame; the session's real reply arrives later as a notify ✅

---

## 4. Cross-reference inconsistencies (state vs journey)

| # | States disagree / sequence lost | Fix |
|---|---|---|
| C1 | Orb state machine has an unreachable LISTENING state; `voiceActive` and `statusState` both claim to mean "listening" | single source: statusState from VoiceInput transitions; drop `voiceActive` from the orb path |
| C2 | PEEK claims to be "at rest" but renders full content | slim strip at PEEK |
| C3 | Bubble says "tap to target session" (Surface onClick) but does nothing | wire the callback |
| C4 | Notification title raw vs stripped in two places (header + bubble) | strip at intake |
| C5 | Error/queued confirmations written then immediately overwritten | settle statuses AFTER wake-word restart; confirmations in sub-line |
| C6 | Queue count hidden by reply badge | combine |
| C7 | Parked-response state exists in AudioPlayer but not in UiState | add pendingAudio to UiState → button label |
| C8 | Header confirmation only in FULL | show at HALF or strengthen chip selected state |
| C9 | Settings feedback split across two status stores | move settings flows into ViewModel |

---

## 5. Suggested implementation order (severity)

1. **F1** (orb listening state — the flagship visual is dead)
2. **F2** (PEEK layout)
3. **F3** (message tap wiring)
4. **F4** (title strip)
5. **F15** (double bubble)
6. **F5** (status ordering — error/queued visibility)
7. **F6** (parked-response button)
8. **F10** (half-state session confirmation)
9. **F9/C6** (badge combine)
10. F7, F8, F12–F14, F16 polish

---

## 6. Open design questions (need Robbie's call)

- Q1: After a turn settles, should the sheet auto-lower from HALF back to PEEK, or stay at HALF (showing the reply/status)? (Brief says "settles to idle" — currently it stays wherever it is.)
- Q2: At PEEK, keep a compact speak button (like legacy strip) or make PEEK purely informational (status + drag up)?
- Q3: Should session targeting auto-arm on ANY notify (current behavior) or only on question/approval kinds? (Auto-arming on every "Hermes finished" means the next voice message silently routes to that session — good for clarify, surprising after a routine response.)
- Q4: Should the message list be visible at HALF (Jetchat always shows it) or only FULL (current)?
