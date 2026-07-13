# Track K — concrete root-process `ProxyFirewallRpc` transport

**Why this exists:** `ProxyFirewallRpc` is only an interface. The daemon enforcement
(Track B) and the health composition (Track G) are both wired to it, but nothing carries
a `ProxyFirewallCommand` to the real root `vpnhotspotd` process or returns the
`ProxyFirewallAck`. Until this lands, even a fully wired service+UI (Tracks H–J) can only
reach `WaitingForVpn`/`FailClosed` — the firewall side never actually runs.

The app already has a framed, Wire-based request/reply channel to the daemon in
`root/daemon/DaemonController.kt` (`DaemonEnvelope`/`ClientEnvelope`, `DaemonIpc.readFrame/
writeFrame`, `call_id` correlation). This track adds a proxy-firewall request frame and a
thin `RootProxyFirewallRpc` on top of that channel.

**Files:**

```text
mobile/src/main/proto/daemon.proto                                   (add request payload)
mobile/src/main/rust/vpnhotspotd/src/control.rs                      (dispatch request → proxy_firewall::handle)
mobile/src/main/java/be/mygod/vpnhotspot/root/daemon/DaemonController.kt (send/await proxy command)
mobile/src/main/java/be/mygod/vpnhotspot/proxy/RootProxyFirewallRpc.kt (new — the ProxyFirewallRpc impl)
mobile/src/test/java/be/mygod/vpnhotspot/proxy/RootProxyFirewallRpcTest.kt (new)
```

---

## Step K1 — carry the command on the request envelope

Track B added the **reply** side (`ReplyFrame.payload = ProxyFirewall(ProxyFirewallAck)`,
see Rust `control/proxy_firewall.rs::reply_frame`). Add the matching **request** payload to
`ClientEnvelope` in `daemon.proto`:

```proto
// daemon.proto — request side
message ClientEnvelope {
  oneof frame {
    // ...existing request frames...
    ProxyFirewallCall proxy_firewall = N;   // NEW (pick the next free field number)
  }
}

message ProxyFirewallCall {
  uint64 call_id = 1;
  vpnhotspot.proxy.ProxyFirewallCommand command = 2;
}
```

Confirm the actual `ClientEnvelope`/frame naming in the current `daemon.proto` — Track B's
reply frame is the template. Reuse the existing `call_id` convention
(`frame.call_id.readCallId()` in `DaemonController`).

---

## Step K2 — daemon dispatch

In the Rust control loop (`control.rs`), decode a `ProxyFirewallCall`, route it through the
already-serialized `proxy_firewall::handle(&state, command)` (which holds the state mutex
across validation+mutation — Track B), and reply with `reply_frame(call_id, ack)`:

```rust
// control.rs — inside the request match
Some(client_envelope::Frame::ProxyFirewall(call)) => {
    let ack = proxy_firewall::handle(&proxy_state, call.command.unwrap_or_default()).await;
    write_frame(&mut output, proxy_firewall::reply_frame(call.call_id, ack)).await?;
}
```

`proxy_state: Mutex<ProxyFirewall<AndroidProxyFirewall>>` is booted once at daemon start
(`proxy_firewall::boot`, Track B) and lives for the daemon session — its `session_id` is
the crash-persistent identity the whole protocol depends on.

---

## Step K3 — Kotlin send/await

`DaemonController` already correlates replies by `call_id` (lines ~279–345). Add a suspend
method that sends a `ProxyFirewallCommand` and awaits the correlated `ProxyFirewallAck`,
reusing whatever pending-call map the controller uses for other request/reply commands:

```kotlin
// DaemonController.kt
suspend fun proxyFirewall(command: ProxyFirewallCommand): ProxyFirewallAck {
    val id = nextCallId()
    val deferred = CompletableDeferred<ProxyFirewallAck>()
    pendingProxyCalls[id] = deferred          // completed in the reply dispatch loop
    try {
        DaemonIpc.writeFrame(
            requireOutput(),
            ClientEnvelope.ADAPTER.encode(
                ClientEnvelope(proxy_firewall = ProxyFirewallCall(call_id = id.toCallId(), command = command)),
            ),
        )
        return deferred.await()
    } finally {
        pendingProxyCalls.remove(id)
    }
}
```

In the existing reply-decode loop, add a branch that completes `pendingProxyCalls[id]` when
a `ReplyFrame.payload` is `ProxyFirewall(ack)`. Transport death (pipe closed / process
gone) must complete outstanding deferreds **exceptionally with `IOException`** — this is
what makes the higher layers behave:

- Track B's `DaemonProxyFirewallClient` maps a sanitation `IOException` to `null` (fail-closed);
- Track G's `TrackingProxyFirewallRpc` clears health/generation on `IOException`.

So do **not** swallow transport errors here.

---

## Step K4 — the `ProxyFirewallRpc` implementation

Thin adapter that runs the command as a root/daemon call. It must surface transport failure
as `IOException` (not a fabricated ack).

```kotlin
package be.mygod.vpnhotspot.proxy

import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallAck
import be.mygod.vpnhotspot.proxy.proto.ProxyFirewallCommand
import be.mygod.vpnhotspot.root.RootManager
import java.io.IOException

/**
 * Sends proxy-firewall commands to the root vpnhotspotd daemon over the existing
 * DaemonController request/reply channel. Never fabricates an acknowledgement:
 * a broken transport throws IOException so the Track B null-gate and Track G
 * disconnect handling engage.
 */
class RootProxyFirewallRpc : ProxyFirewallRpc {
    override suspend fun execute(command: ProxyFirewallCommand): ProxyFirewallAck =
        try {
            RootManager.use { daemonController(it).proxyFirewall(command) }
        } catch (e: IOException) {
            throw e
        } catch (e: RemoteException) {
            // Cross-process death → treat as transport IOException for the layers above.
            throw IOException("proxy firewall RPC transport failed", e)
        }
}
```

> Adapt `RootManager.use { … }` and how you obtain the `DaemonController` to the app's
> real accessors (see how `Routing`/`DaemonController` are used elsewhere). The essential
> contract: success → real ack; transport/process failure → `IOException`.

---

## Step K5 — wire into the service

In `ProxyOnlyService` (Track I) replace the placeholder with `RootProxyFirewallRpc()`, and
on daemon channel loss call `composition.transportDisconnected()` (Track G) so health/
generation clear immediately and the controller fails closed. The Track G method that
overlays acknowledged health onto the desired-state flow is `desiredStates(source)` /
`Flow<DesiredProxyState>.withAcknowledgedDaemonState(...)` — use the real name.

---

## Step K6 — tests

- **Kotlin unit:** a fake `DaemonController` returning canned acks proves `RootProxyFirewallRpc`
  maps OK/STALE/INVALID/IO_ERROR through unchanged, and that a thrown transport error
  surfaces as `IOException` (not a fabricated ack).
- **Rust:** a control-loop test that a `ProxyFirewallCall` is dispatched to
  `proxy_firewall::handle` and the reply carries the daemon's authoritative identity
  (reuse Track B's `ProxyFirewall` tests; add one control-dispatch test).
- **Round-trip (instrumented, later):** with a real daemon, sanitize→start→replace→stop and
  assert `session_id`/`epoch` echo through.

## Acceptance criteria

- A proxy-firewall command travels app → daemon → `proxy_firewall::handle` → ack → app.
- Transport failure raises `IOException` end to end (verified by test), engaging the
  fail-closed null-gate and health-clear paths.
- `ProxyDaemonComposition` bootstrap succeeds against the real transport and publishes a
  non-zero acknowledged `(sessionId, generation)`.
- Rust `cargo test` + `./gradlew :mobile:test` green.

## Dependencies

- Requires Track B (daemon handler + reply frame) — present.
- Consumed by Track I (service constructs `RootProxyFirewallRpc`).
- Does **not** provide the SOCKS5 data path — that's the Hev/JNI backend track (still open).
  With Track K, the firewall control plane is real; traffic still needs the backend.
