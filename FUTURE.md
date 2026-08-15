# Hermes Assistant — Development Notes

Status of previously-deferred features:

- **#4 Offline transcription queue** — ✅ IMPLEMENTED (v1.4.0)
- **#5 Message history view** — ✅ IMPLEMENTED (v1.4.0)
- **Foreground service** — ✅ IMPLEMENTED (v1.6.8)

---

## 4. Offline transcription queue (send big dump on reconnect) — IMPLEMENTED

When the phone has no connectivity to the server (Tailscale down, server
off, airplane mode), the app switches to **Vosk offline dictation**: the
bundled Vosk model transcribes the full phrase locally (instead of Google
STT, which needs network), and the text is appended to a persistent queue
(`filesDir/pending_queue.json`).

On reconnect (`onOpen`), the queue is flushed one-by-one over the existing
WebSocket — each message gets its own Hermes reply, attributed in history.

**How it works:**
- `isConnected == false` → tap / wake word → `startOfflineDictation()`
- Vosk `onResult` in dictation mode → `chatHistory.enqueue(text)` →
  badge shows "N messages queued — will send when connected"
- `flushQueueIfAny()` on `onOpen` → `sendNextQueued()` → each send is
  paced by the server's `audio_end` message
- A 15s watchdog returns to wake-word mode if nothing is said
- Failed sends are re-queued at the front (`requeue()`)

**Known limitation:** the bundled `vosk-model-small-en-us-0.15` is tuned
for wake-word accuracy; dictation quality on longer sentences is mediocre.
Swap in `vosk-model-en-us-0.22` (full model, ~1.8GB unpacked) for real
dictation accuracy — decide if the APK size cost is worth it.

**Relevant files:** `ChatHistoryStore.kt` (queue + persistence),
`MainActivity.kt` (`startOfflineDictation`, `handleDictatedText`,
`flushQueueIfAny`, `sendNextQueued`).

---

## 5. Message history view (last N sent/received) — IMPLEMENTED

The top half of the screen is now a scrollable conversation log showing
the last 50 messages (user / hermes / notify), auto-scrolling to the
newest. Messages persist to `filesDir/chat_history.json` across restarts.

**How it works:**
- `ChatHistoryStore` — ring buffer (max 50) + JSON persistence
- Bubbles: user = blue right-aligned, hermes = slate left-aligned,
  notify = centered outlined; queued messages show an amber "⏳ queued
  (offline)" marker
- Hook points: STT `onResults` (user), WS `type=text` (hermes),
  `handleNotify` (notify), `enqueue` (queued offline)

**Relevant files:** `ChatHistoryStore.kt`, `MainActivity.kt`
(`renderHistory`, `addBubble`), `activity_main.xml` (top-half history).

---

## Nice-to-have backlog

- **Per-session notification muting:** pick which session IDs/cwds should
  NOT produce phone alerts (mirror of the `SELF_CWD_PREFIXES` filter in
  `notify_hermes.py`, but user-controlled).
- **Larger Vosk model for dictation:** see #4 known limitation above.
- **Batch endpoint option:** the queue currently flushes one-by-one over
  the WS; a `POST /chat/batch` endpoint on the server would let it send
  `{"messages": [...]}` in a single Hermes call instead.
- **Real security on the relay:** auth (API token / mTLS) before exposing
  port 8000 beyond the tailnet. Currently NOT enabled — see README.
