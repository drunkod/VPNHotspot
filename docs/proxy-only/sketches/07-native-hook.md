# Step 7 — Testable Hev network hook

Task: one narrow socket-prepare seam that Android backs with `android_setsocknetwork()`
and host CI backs with an injected fake, so hook coverage on every socket path is
enforced by tests, not by manual audit.
Maps to: Implementation plan Phase 0 (host-CI seam) and Phase 1 (native build).
Depends on: nothing in Kotlin; pairs with the pinned Hev fork.
Consumed by: `HevProxyBackend` ([Step 3](03-service-and-backend-ownership.md)).

```c
typedef int (*vpnhotspot_network_bind_fn)(uint64_t network, int fd, void *opaque);

typedef struct {
    uint64_t network_handle;
    int fail_closed;
    vpnhotspot_network_bind_fn bind_fn;
    void *bind_opaque;
} vpnhotspot_network_state_t;

int vpnhotspot_prepare_outbound_socket(
    int fd,
    int family,
    int type,
    const vpnhotspot_network_state_t *state)
{
    if (state == NULL || state->network_handle == 0 || state->bind_fn == NULL)
        return state != NULL && state->fail_closed ? -ENONET : 0;
    return state->bind_fn(state->network_handle, fd, state->bind_opaque);
}
```

Android uses `android_setsocknetwork()`. Host CI injects a fake callback and asserts ordering on every socket path.
