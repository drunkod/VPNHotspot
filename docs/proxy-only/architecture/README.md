# Proxy-only architecture diagrams

This directory contains GitHub-rendered Mermaid architecture companions for the proxy-only
implementation tracks. The diagrams document the implemented boundaries; they do not replace the
rooted-device and packet evidence required for release.

## Available diagrams

- [Track L — Kotlin SOCKS5 data plane](TRACK-L-kotlin-socks5-architecture.md)
  - app/root/kernel system context and trust boundaries;
  - activation, sanitation and firewall allow sequence;
  - TCP CONNECT request and relay sequence;
  - UDP ASSOCIATE request, endpoint validation and teardown sequence;
  - runtime and cleanup state model;
  - bounded DNS cancellation boundary;
  - failure-containment matrix; and
  - rooted-device packet and lifecycle evidence map.

The implementation status and track index remain in
[`../tracks/00-STATUS.md`](../tracks/00-STATUS.md).
