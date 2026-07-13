# Track K — concrete root-daemon RPC transport

Status: **implemented and verified**

## Result

Proxy firewall commands now travel over the app's existing framed Wire protocol to the real
root `vpnhotspotd` process and return typed `ProxyFirewallAck` replies.

Track B had already added the protocol fields, daemon dispatch and authoritative firewall
handler. Track K completes the Android request path and lifecycle.

## Android transport

`DaemonController.proxyFirewall(command)`:

- wraps the command in the existing `ClientEnvelope.proxy_firewall` field;
- allocates the normal non-zero `call_id`;
- sends through `DaemonIpc.writeFrame`;
- uses the existing one-shot pending-call map;
- decodes the correlated `ReplyFrame.proxy_firewall` payload;
- propagates daemon errors, cancellation and transport closure instead of fabricating success.

`RootProxyFirewallRpc` is the concrete `ProxyFirewallRpc` adapter. Existing `IOException`
instances are preserved; other root/session failures are wrapped as `IOException`, which
activates Track B's sanitation null gate and Track G's health-clear behavior.

## Transport lease

The root controller now exposes an idempotent, reference-counted `DaemonController.Lease`.
A proxy service lease keeps one daemon session/generation alive even when no request is
currently in flight. The connection shuts down only after both pending calls and leases reach
zero.

Unexpected transport loss still fails every pending call. A subsequent request can reconnect,
while the same logical lease continues to prevent intentional idle shutdown until feature stop.

## Service integration

`ProxyOnlyService`:

1. acquires a lease after explicit foreground activation;
2. performs deny-first Track G bootstrap;
3. holds the lease through controller and cleanup operations;
4. cancels bootstrap and releases the lease immediately after successful feature stop;
5. clears acknowledged daemon health on release.

No root transport is opened merely because the persisted enable bit is true or the screen is
bound after process restart.

## Tests

`RootProxyFirewallRpcTest` verifies:

- successful acknowledgements are returned unchanged;
- existing `IOException` identity is preserved;
- other failures become transport `IOException` with their cause attached.

The Rust proxy-firewall tests continue to verify serialized dispatch, authoritative identity,
stale-token zero-mutation behavior and deny-first sanitation.

## Verification

Rust check/tests/clippy and the Android/JVM suite passed on executable head
`a5fe4dd574faba2a6b29d5e55e72af5526798631`.

## Remaining device evidence

A rooted physical-device test must still kill/restart the real daemon during an active runtime
and verify the controller observes the new acknowledgement identity, closes the old backend and
re-sanitizes before any new listener is admitted.
