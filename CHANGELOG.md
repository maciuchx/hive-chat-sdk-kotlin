# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
semantic versioning from 1.0 onwards.

## [Unreleased]

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
