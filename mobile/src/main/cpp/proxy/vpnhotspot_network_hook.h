/*
 * Step 7 — Testable Hev network hook (host-CI seam)
 * Sketch: docs/proxy-only/sketches/07-native-hook.md
 *
 * One narrow socket-prepare seam:
 *   - On Android: backed by android_setsocknetwork()
 *   - On host CI: injected fake callback asserts ordering on every socket path
 *
 * Hook failure is fatal in fail-closed mode (fail_closed != 0).
 * Host CI must prove the hook runs before every packet-producing operation.
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
 * Zero-initialise to get a no-op (pass-through) hook.
 */
typedef struct {
    /** Android network handle; 0 means "no binding required". */
    uint64_t network_handle;

    /**
     * Fail-closed mode flag.
     * When non-zero, a NULL bind_fn or a zero network_handle causes
     * vpnhotspot_prepare_outbound_socket() to return -ENONET instead of 0.
     */
    int fail_closed;

    /** Binding implementation; NULL means "skip binding". */
    vpnhotspot_network_bind_fn bind_fn;

    /** Opaque context forwarded verbatim to bind_fn. */
    void *bind_opaque;
} vpnhotspot_network_state_t;

/**
 * Prepare an outbound socket before any packet-producing operation.
 *
 * Must be called once per socket, immediately after creation and before the
 * first connect/sendto/sendmsg. Hev fork must call this on every path that
 * can produce outbound packets:
 *   - TCP connect
 *   - UDP association / relay socket
 *   - retry / fallback socket
 *   - DNS / resolver socket
 *   - address-family fallback socket
 *
 * @param fd      Socket file descriptor.
 * @param family  Address family (AF_INET, AF_INET6, …).
 * @param type    Socket type (SOCK_STREAM, SOCK_DGRAM, …).
 * @param state   Pointer to the hook state; may be NULL (treated as no-op
 *                unless fail_closed is set on a non-NULL state).
 * @return 0 on success, negative errno on failure.
 *         -ENONET  when fail_closed is set and binding is not possible.
 */
static inline int vpnhotspot_prepare_outbound_socket(
    int fd,
    int family,
    int type,
    const vpnhotspot_network_state_t *state)
{
    (void)fd;
    (void)family;
    (void)type;

    if (state == NULL || state->network_handle == 0 || state->bind_fn == NULL) {
        return (state != NULL && state->fail_closed) ? -ENONET : 0;
    }
    return state->bind_fn(state->network_handle, fd, state->bind_opaque);
}

#ifdef __cplusplus
} /* extern "C" */
#endif
