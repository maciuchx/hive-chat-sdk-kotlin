# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
semantic versioning from 1.0 onwards.

## [Unreleased]

## [0.4.0] — 2026-08-31

### Fixed
- **Opening the chat no longer says "Connecting…".** `connect()` called
  `reconnectNow()` unconditionally, so arriving at the chat screen tore down a
  perfectly healthy socket and re-handshook it. It now leaves a live
  connection alone.

### Added
- `trackScreen()` and `updateCart()` — give agents the browsing context and
  basket they already see for website customers. Without them the agent panel
  shows only "Android app".
- **Voice messages**, opt-in via `voiceMessagesEnabled` (needs `RECORD_AUDIO`
  in your manifest). AAC-in-MP4, which the dashboard plays and WhatsApp
  accepts.
- The attach button now offers **Photo** or **File** explicitly, rather than
  one picker that buried the camera roll.

## [0.3.0] — 2026-08-31

### Fixed
- **The composer no longer floats above the keyboard.** Insets were applied
  twice — `imePadding()` on the root plus `navigationBarsPadding()` on the
  composer — stacking the keyboard's height and the navigation bar's. They are
  now applied once, as `WindowInsets.ime.union(WindowInsets.navigationBars)`,
  which takes the larger of the two.

### Added
- **Attach a photo or file.** The core could always upload; there was no way to
  ask for one. Goes through the system picker, so neither the SDK nor your app
  needs a storage permission.
- **Long-press a message to react.** Reaction chips rendered but nothing could
  add one — the same six emoji the web widget offers.
- **Help articles open in the app.** Tapping an article card previously did
  nothing unless the host passed `onOpenArticle`; there is now a built-in
  reader, matching the web widget. Pass `onOpenArticle` to override it.
- `HiveChatScreen(applyWindowInsets =)` for hosts that pad for the keyboard
  themselves.

## [0.2.0] — 2026-08-31

### Added
- `HiveChatScreen(onProductClick =)` — handle a product-card tap yourself and
  push your own native product screen. Without it the card opened its `buyUrl`
  in a browser, walking the customer out of the conversation they were having,
  which rather defeats a native chat.
- `HiveChatScreen(onOpenUrl =)` — first refusal on every link in the thread.
  Return `true` when you have handled it, `false` to let a browser open it.

## [0.1.1] — 2026-08-31

### Fixed
- **JitPack can build this now.** 0.1.0 declared no Maven publication, so
  JitPack injected its own — in Groovy, into a Kotlin DSL build file, where
  `singleVariant('release')` is a character literal. Every build died with
  "Too many characters in a character literal" before compiling any Kotlin.
  Both modules now declare their own publication, with sources jars.
- Documented the widget key in the format Hive actually issues (`hv_` + 24 hex
  characters); earlier examples showed an invented `wk_live_…` prefix.

## [0.1.0] — 2026-08-31

First release. Implements the Hive visitor protocol as documented in
[PROTOCOL.md](PROTOCOL.md), matching the Swift SDK feature for feature.

### Added
- `HiveChat` — connection and conversation state exposed as `StateFlow`s, with
  the full visitor event set: messages, typing, read receipts, reactions,
  queue position, transfers, offline handling, CSAT, device handoff.
- Socket.IO v4 client over OkHttp with ack support, exponential backoff with
  jitter, and foreground reconnection.
- `MessageContent` sealed interface covering every sentinel the server emits,
  with an `Unsupported` case so unknown future sentinels degrade rather than
  break.
- File upload with an optimistic local echo.
- Offline outbox: messages typed while disconnected are queued and flushed in
  order on reconnect.
- Help-centre search and article fetch; transcript by email.
- `hivechat-ui` — `HiveChatScreen`, a complete Compose chat screen themed from
  the merchant's own widget settings.
