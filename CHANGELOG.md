# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
semantic versioning from 1.0 onwards.

## [Unreleased]

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
