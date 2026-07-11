/*
 * Step 7 — Testable Hev network hook (host-CI seam)
 * Sketch: docs/proxy-only/sketches/07-native-hook.md
 *
 * Fix #11: production path rejects NULL state, zero handle and NULL callback
 * so a missed state propagation is fatal rather than silently fail-open.
 * Tests that need a pass-through mode must construct an explicit no-op state
 * (network_handle=0, fail_closed=0) and call vpnhotspot_prepare_outbound_socket_passthrough().
 */

#pragma once

#include <errno.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Callback type for binding a socket FD to a network.
 *
 * @param network   Network handle (android.net.Network#networkHandle).
 * @param fd        The socket file descriptor to bind.
 * @param opaque    Caller-supplied context pointer.
 * @return 0 on success, negative errno on failure.
 */
typedef int (*vpnhotspot_network_bind_fn)(uint64_t network, int fd, void *opaque);

/**
 * Immutable state carried into Hev worker threads.
 *
 * Production callers MUST NOT zero-initialise this struct and pass it to
 * vpnhotspot_prepare_outbound_socket() — that is fail-open. Use the
 * VPNHOTSPOT_NETWORK_STATE_INIT macro or the explicit constructor.
 *
 * Tests that require a pass-through mode must use vpnhotspot_prepare_outbound_socket_passthrough().
 */
typedef struct {
    /** Android network handle; must be non-zero in production. */
    uint64_t network_handle;

    /**
     * Fail-closed mode flag.
     * Production callers MUST set this to 1. A zero value is accepted only
     * in host-CI pass-through mode via the dedicated passthrough function.
     */
    int fail_closed;

    /** Binding implementation; must be non-NULL in production. */
    vpnhotspot_network_bind_fn bind_fn;

    /** Opaque context forwarded verbatim to bind_fn. */
    void *bind_opaque;
} vpnhotspot_network_state_t;

/**
 * Prepare an outbound socket before any packet-producing operation (production).
 *
 * Fix #11: rejects NULL state and any missing required field (-ENONET) so
 * missed state propagation is fatal, not silently fail-open.
 *
 * Must be called once per socket immediately after creation and before the
 * first connect/sendto/sendmsg. The Hev fork must call this on EVERY path:
 *   - TCP connect
 *   - UDP association / relay socket
 *   - retry / fallback socket
 *   - DNS / resolver socket
 *   - address-family fallback socket
 *
 * @param fd      Socket file descriptor.
 * @param family  Address family (AF_INET, AF_INET6, …).
 * @param type    Socket type (SOCK_STREAM, SOCK_DGRAM, …).
 * @param state   Hook state — must not be NULL, network_handle must be non-zero,
 *                bind_fn must be non-NULL, and fail_closed must be 1.
 * @return 0 on success, negative errno on failure.
 *         -ENONET  if any required field is missing (always fail-closed).
 *         -EINVAL  if state is NULL.
 */
static inline int vpnhotspot_prepare_outbound_socket(
    int fd,
    int family,
    int type,
    const vpnhotspot_network_state_t *state)
{
    (void)family;
    (void)type;

    /* Fix #11: NULL state is always rejected — there is no fail-open production path. */
    if (state == NULL) return -EINVAL;

    /* Missing handle or callback in fail-closed mode → hard reject. */
    if (state->network_handle == 0 || state->bind_fn == NULL) {
        return state->fail_closed ? -ENONET : -EINVAL;
    }

    return state->bind_fn(state->network_handle, fd, state->bind_opaque);
}

/**
 * Pass-through variant for host-CI and test environments ONLY.
 *
 * This function explicitly does NOT bind the socket to any network.
 * It must never be called from production fail-closed code paths.
 * Host-CI tests must assert that this function is NEVER invoked on a
 * code path that can produce real packets.
 *
 * @return Always 0.
 */
static inline int vpnhotspot_prepare_outbound_socket_passthrough(
    int fd,
    int family,
    int type)
{
    (void)fd;
    (void)family;
    (void)type;
    return 0;
}

#ifdef __cplusplus
} /* extern "C" */
#endif
