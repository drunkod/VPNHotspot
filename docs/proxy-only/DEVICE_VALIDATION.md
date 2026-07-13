# Proxy-only rooted-device validation runbook

Status: **required release evidence; not yet executed**

This is the operational companion to [`TEST_PLAN.md`](TEST_PLAN.md). The broader test plan defines
all security properties; this runbook defines the first repeatable rooted-device pass, the artifacts
to retain, and objective pass/fail criteria.

The first pass is intentionally narrow:

- one rooted Android phone;
- one real Android VPN provider/configuration;
- Wi-Fi or USB system tethering;
- one allowed laptop client and, where available, one non-allowed client;
- the APK built from the PR's current executable source head.

A passing first run does not complete the full Android-version/provider matrix, but it is the first
hardware gate before the PR can leave draft.

## 1. Evidence package

Create one private evidence directory before testing:

```bash
export PACKAGE=be.mygod.vpnhotspot
export SOURCE_SHA='<executable-source-sha>'
export RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-${SOURCE_SHA:0:12}"
export EVIDENCE="$PWD/proxy-device-validation/$RUN_ID"
mkdir -p "$EVIDENCE"/{phone,client,captures,screenshots,notes}
```

Do not publish packet captures without review. SOCKS5 username/password authentication is visible in
plaintext on the tethering link. Rotate proxy credentials after the test and treat the evidence
package as sensitive.

Record the following in `notes/environment.md`:

```text
source SHA:
APK SHA-256:
phone model:
Android version/build:
kernel:
root implementation/version:
VPN provider and protocol:
VPN include/exclude policy:
tethering type:
tethering interface:
physical uplink interface:
VPN interface or provider capture source:
allowed client OS/MAC/IPv4:
second client OS/MAC/IPv4:
proxy TCP port:
proxy UDP range:
UTC start/end:
tester:
```

## 2. Prerequisites and baseline collection

Required host tools:

- `adb`;
- `curl` with SOCKS5 support;
- Python 3.9 or newer for the repository UDP probe;
- Wireshark or `tcpdump` for capture review.

Required phone capabilities:

- working root through `su`;
- `tcpdump` available on the phone or an equivalent rooted capture method;
- a real VPN visible as Android `TRANSPORT_VPN`;
- system tethering with a connected laptop.

Verify root and collect the immutable baseline:

```bash
adb wait-for-device
adb shell su -c id | tee "$EVIDENCE/phone/root-id.txt"
adb shell getprop > "$EVIDENCE/phone/getprop.txt"
adb shell uname -a > "$EVIDENCE/phone/uname.txt"
adb shell dumpsys package "$PACKAGE" > "$EVIDENCE/phone/package.txt"
adb shell dumpsys connectivity > "$EVIDENCE/phone/connectivity-before.txt"
adb shell ip -br address > "$EVIDENCE/phone/ip-address-before.txt"
adb shell ip route show table all > "$EVIDENCE/phone/routes-before.txt"
adb shell ip rule show > "$EVIDENCE/phone/rules-before.txt"
adb shell su -c 'command -v tcpdump || true' > "$EVIDENCE/phone/tcpdump-path.txt"
adb shell su -c 'iptables-save 2>&1 || true' > "$EVIDENCE/phone/iptables-before.txt"
adb shell su -c 'ip6tables-save 2>&1 || true' > "$EVIDENCE/phone/ip6tables-before.txt"
adb shell su -c 'nft list ruleset 2>&1 || true' > "$EVIDENCE/phone/nft-before.txt"
```

Start a clean log capture before enabling the feature:

```bash
adb logcat -c
adb logcat -v threadtime > "$EVIDENCE/phone/logcat.txt" &
export LOGCAT_PID=$!
```

Install the exact APK and record its digest:

```bash
sha256sum path/to/mobile-arm64-v8a-debug.apk | tee "$EVIDENCE/notes/apk-sha256.txt"
adb install -r path/to/mobile-arm64-v8a-debug.apk | tee "$EVIDENCE/phone/install.txt"
```

### Baseline pass criteria

- `su -c id` reports UID 0.
- The installed package corresponds to the recorded APK digest and source SHA.
- Exactly one intended VPN is active before proxy startup.
- Tethering works normally while Proxy-only is disabled.
- Interface names are recorded rather than assumed.

Stop the run immediately if the package, source SHA, VPN, or root state is ambiguous.

## 3. Runtime variables

After enabling tethering and identifying interfaces, set:

```bash
export PHONE_TETHER_IP='<phone IPv4 address reachable by laptop>'
export TCP_PORT='<Proxy screen TCP port>'
export UDP_START='<Proxy screen UDP range start>'
export UDP_END='<Proxy screen UDP range end>'
export PROXY_USER='<Proxy screen username>'
export PROXY_PASS='<Proxy screen password>'
export TETHER_IFACE='<phone tethering interface>'
export VPN_IFACE='<phone VPN/tunnel interface if capturable>'
export UPLINK_IFACE='<phone physical Wi-Fi or cellular interface>'
```

On the laptop, record:

```bash
ip address > "$EVIDENCE/client/ip-address.txt" 2>&1 || ipconfig /all > "$EVIDENCE/client/ip-address.txt"
ip route > "$EVIDENCE/client/routes.txt" 2>&1 || route print > "$EVIDENCE/client/routes.txt"
```

## 4. First-pass execution order

Run the tests in this order so failures remain diagnosable:

1. DV-01 foreground activation and deny-first startup;
2. DV-02 authenticated TCP CONNECT;
3. DV-03 authentication rejection;
4. DV-04 UDP ASSOCIATE;
5. DV-05 ingress and client containment;
6. DV-06 packet proof of VPN-only egress;
7. DV-07 VPN loss and recovery;
8. DV-08 DNS blackhole behavior;
9. DV-09 real daemon restart and Track G recovery;
10. DV-10 process death and explicit Resume;
11. DV-11 repeated lifecycle resource baseline;
12. DV-12 UI, notification, and configuration QA.

For every test, save the command output, screenshots/video, relevant log timestamps, and firewall/capture
snapshots. Mark the result as `PASS`, `FAIL`, or `BLOCKED`; do not convert missing evidence into a pass.

## 5. DV-01 — foreground activation and deny-first startup

1. Open the app and the **Proxy** destination.
2. Configure the recorded TCP port and UDP range.
3. Enable Proxy-only from the foreground UI.
4. Approve root/notification prompts as applicable.
5. Observe states through startup until `Running` or a typed blocked state appears.
6. Capture the screen and foreground notification.
7. Snapshot listeners, daemon identity storage, and firewall state:

```bash
adb shell pidof "$PACKAGE" > "$EVIDENCE/phone/app-pid-running.txt"
adb shell su -c 'pidof vpnhotspotd || true' > "$EVIDENCE/phone/daemon-pid-running.txt"
adb shell su -c 'ss -lntup 2>&1 || netstat -lntup 2>&1' > "$EVIDENCE/phone/listeners-running.txt"
adb shell su -c 'iptables-save 2>&1 || true' > "$EVIDENCE/phone/iptables-running.txt"
adb shell su -c 'ip6tables-save 2>&1 || true' > "$EVIDENCE/phone/ip6tables-running.txt"
adb shell su -c 'nft list ruleset 2>&1 || true' > "$EVIDENCE/phone/nft-running.txt"
adb shell su -c \
  'ls -l /data/local/tmp/vpnhotspotd 2>&1; xxd /data/local/tmp/vpnhotspotd/proxy-firewall-session 2>&1 || true' \
  > "$EVIDENCE/phone/daemon-session-running.txt"
```

### Pass criteria

- Startup requires the foreground user action; persisted configuration alone is not sufficient.
- The service has a persistent foreground notification while enabled.
- The listener is present only after root sanitation and required probes complete.
- IPv4 admission rules require downstream interface, client IPv4, and client MAC.
- Per-downstream/global rejects and explicit IPv6 rejects are present.
- No broad allow from the VPN or physical uplink interface exists.

## 6. DV-02 — authenticated TCP CONNECT

From the allowed laptop client:

```bash
curl --fail --show-error --silent \
  --connect-timeout 10 --max-time 30 \
  --proxy "socks5h://${PROXY_USER}:${PROXY_PASS}@${PHONE_TETHER_IP}:${TCP_PORT}" \
  https://ifconfig.me/ip \
  | tee "$EVIDENCE/client/tcp-connect-public-ip.txt"
```

Repeat against a small keep-alive response and an interactive/streaming endpoint available to the
test environment. Save timings and output.

### Pass criteria

- The request succeeds through the proxy.
- The observed public IP is the VPN exit, not the phone's direct/carrier exit.
- Small responses arrive promptly rather than waiting for 8 KiB or remote close.
- Closing either side eventually removes the active connection from backend stats/listeners.

## 7. DV-03 — authentication rejection

Run with missing and incorrect credentials:

```bash
set +e
curl --verbose --connect-timeout 10 --max-time 20 \
  --proxy "socks5h://${PHONE_TETHER_IP}:${TCP_PORT}" \
  https://example.com/ \
  > "$EVIDENCE/client/no-auth.stdout" 2> "$EVIDENCE/client/no-auth.stderr"
echo $? > "$EVIDENCE/client/no-auth.exit"

curl --verbose --connect-timeout 10 --max-time 20 \
  --proxy "socks5h://wrong:wrong@${PHONE_TETHER_IP}:${TCP_PORT}" \
  https://example.com/ \
  > "$EVIDENCE/client/wrong-auth.stdout" 2> "$EVIDENCE/client/wrong-auth.stderr"
echo $? > "$EVIDENCE/client/wrong-auth.exit"
set -e
```

### Pass criteria

- Both commands fail before remote content is returned.
- No unauthenticated CONNECT or UDP association is opened.
- Repeated failed authentication does not crash or stop the foreground service.

## 8. DV-04 — UDP ASSOCIATE

Use the standard-library probe included in the repository:

```bash
python3 tools/proxy_device_validation/socks5_udp_probe.py \
  --proxy-host "$PHONE_TETHER_IP" \
  --proxy-port "$TCP_PORT" \
  --username "$PROXY_USER" \
  --password "$PROXY_PASS" \
  --dns-server 1.1.1.1 \
  --dns-name example.com \
  | tee "$EVIDENCE/client/udp-associate.txt"
```

The probe authenticates, creates UDP ASSOCIATE, keeps the TCP control connection open, sends one DNS
query through the returned relay, validates the SOCKS5 UDP envelope, and checks the DNS transaction
ID and response bit.

### Pass criteria

- The returned relay address is reachable and is not an unusable wildcard.
- The relay port is within `$UDP_START..$UDP_END`.
- A valid DNS response is returned from the requested remote address/port.
- Closing the TCP control connection closes the association.
- A datagram from another client source or an unsolicited remote endpoint is not relayed.

## 9. DV-05 — ingress and client containment

Where a second tethered client is available:

1. Confirm the allowed client succeeds.
2. Remove/block the second client in the app or ensure it is absent from the exact ACL.
3. Attempt TCP and UDP access from the second client.
4. If the test network permits controlled address changes, try reusing the allowed IPv4 from a
   different MAC without disrupting the allowed client.
5. Scan the listener TCP port and UDP range over IPv6.

### Pass criteria

- Only the exact downstream-interface + IPv4 + MAC tuple is admitted.
- Wrong MAC, wrong source IP, wrong interface, and IPv6 are rejected.
- Loopback, physical uplink, and VPN interfaces cannot reach the wildcard listener through the
  kernel policy.

## 10. DV-06 — packet proof of VPN-only egress

Start simultaneous captures before repeating DV-02 and DV-04. Example rooted-phone commands:

```bash
adb shell su -c \
  "nohup tcpdump -i $TETHER_IFACE -U -s 0 -w /data/local/tmp/proxy-tether.pcap >/dev/null 2>&1 & echo \$!" \
  > "$EVIDENCE/phone/tcpdump-tether.pid"
adb shell su -c \
  "nohup tcpdump -i $UPLINK_IFACE -U -s 0 -w /data/local/tmp/proxy-uplink.pcap >/dev/null 2>&1 & echo \$!" \
  > "$EVIDENCE/phone/tcpdump-uplink.pid"
```

If the VPN interface is visible to `tcpdump`, capture it too:

```bash
adb shell su -c \
  "nohup tcpdump -i $VPN_IFACE -U -s 0 -w /data/local/tmp/proxy-vpn.pcap >/dev/null 2>&1 & echo \$!" \
  > "$EVIDENCE/phone/tcpdump-vpn.pid"
```

Stop captures after the requests, then pull them:

```bash
for name in tether uplink vpn; do
  pid_file="$EVIDENCE/phone/tcpdump-${name}.pid"
  if test -s "$pid_file"; then adb shell su -c "kill $(tr -d '\r' < "$pid_file") 2>/dev/null || true"; fi
done
sleep 2
adb pull /data/local/tmp/proxy-tether.pcap "$EVIDENCE/captures/" || true
adb pull /data/local/tmp/proxy-uplink.pcap "$EVIDENCE/captures/" || true
adb pull /data/local/tmp/proxy-vpn.pcap "$EVIDENCE/captures/" || true
```

### Interpretation rule

Encrypted outer VPN packets on the physical uplink are expected. A failure is direct cleartext
traffic from the app to the tested remote destination or resolver outside the VPN tunnel—for example,
direct TCP/443 to the test site's resolved IP or direct UDP/53 to `1.1.1.1` on the physical uplink.

### Pass criteria

- Client SOCKS traffic is visible on the tethering interface.
- The corresponding remote traffic is visible inside the VPN/provider path.
- The physical uplink contains only the VPN tunnel's expected outer traffic for those requests.
- No process-default or physical DNS request for the SOCKS domain is observed.

Public-IP equality alone is not sufficient evidence.

## 11. DV-07 — VPN loss and recovery

While TCP traffic is active and UDP ASSOCIATE is open:

1. Disconnect or disable the selected VPN without disabling tethering.
2. Observe UI state, listener, firewall snapshots, and existing client sessions.
3. Attempt new TCP and UDP requests.
4. Restore exactly one usable VPN and observe recovery.

### Pass criteria

- Existing listener/sessions are closed or made unreachable promptly.
- New TCP/UDP requests fail closed; no direct physical fallback occurs.
- Firewall state returns to deny/sanitized containment.
- Recovery performs new probes and sanitation before publishing `Running` again.

## 12. DV-08 — DNS blackhole behavior

Blackhole DNS only inside the selected VPN/provider where possible; do not disable the whole VPN.
Run several concurrent domain CONNECT attempts and retain timings.

Known implementation boundary: each caller has a five-second deadline and at most two blocking
`Network.getAllByName` calls execute concurrently. The caller times out, but Android's blocking
resolver worker may remain occupied until the platform resolver itself returns.

### Pass criteria

- Requests fail closed around their caller deadlines.
- Resolver concurrency remains bounded; no unbounded thread/FD growth occurs.
- Existing IP-literal traffic remains within the intended VPN boundary.
- No fallback DNS packet appears on the physical uplink.
- The service recovers after VPN DNS becomes healthy without process restart.

Record worker recovery time; slow recovery caused by non-interruptible platform DNS is a known
hardening item, not permission to accept a leak.

## 13. DV-09 — real daemon restart and Track G recovery

With the proxy `Running` and captures active:

```bash
adb shell su -c 'pidof vpnhotspotd' > "$EVIDENCE/phone/daemon-pid-before-kill.txt"
adb shell su -c 'iptables-save 2>&1 || true' > "$EVIDENCE/phone/iptables-before-daemon-kill.txt"
adb shell su -c 'pkill -TERM vpnhotspotd || kill $(pidof vpnhotspotd)' \
  > "$EVIDENCE/phone/daemon-kill.txt" 2>&1 || true
```

Immediately attempt new TCP/UDP requests and poll:

```bash
for second in $(seq 0 30); do
  printf '%s ' "$second"
  adb shell su -c 'pidof vpnhotspotd || true'
  adb shell su -c "ss -lnt 2>/dev/null | grep -F ':${TCP_PORT} ' || true"
  sleep 1
done > "$EVIDENCE/phone/daemon-recovery-poll.txt"
```

After recovery, collect:

```bash
adb shell su -c 'pidof vpnhotspotd || true' > "$EVIDENCE/phone/daemon-pid-after-recovery.txt"
adb shell su -c 'iptables-save 2>&1 || true' > "$EVIDENCE/phone/iptables-after-recovery.txt"
adb shell su -c \
  'ls -l /data/local/tmp/vpnhotspotd 2>&1; xxd /data/local/tmp/vpnhotspotd/proxy-firewall-session 2>&1 || true' \
  > "$EVIDENCE/phone/daemon-session-after-recovery.txt"
```

### Pass criteria

- Daemon loss immediately invalidates acknowledged health and generation.
- A late reply from the old transport cannot restore a healthy state.
- The old runtime is closed or unreachable while daemon identity is unavailable.
- No handle mutation is sent using the stale session/epoch.
- Recovery obtains a new authoritative acknowledgement and performs deny-first sanitation before
  any new allow state becomes reachable.
- TCP and UDP work again only after the new runtime reaches `Running`.

This test is the physical evidence boundary for Tracks G and K.

## 14. DV-10 — process death and explicit Resume

1. Leave Proxy-only enabled.
2. Record current app/daemon/listener state.
3. Force-stop or kill the app process:

```bash
adb shell am force-stop "$PACKAGE"
```

4. Without opening the app, confirm no proxy foreground service/listener/root lease is resurrected
   solely from persisted `enabled=true`.
5. Open the app and confirm `ActivationRequired`.
6. Tap **Resume** and confirm a new foreground activation and complete sanitation/probe sequence.

### Pass criteria

- Persisted enable state never acts as a reusable activation grant.
- No background FGS start is attempted after process death.
- Resume is explicit and produces a new working runtime.

## 15. DV-11 — repeated lifecycle resource baseline

Capture a baseline while enabled but idle:

```bash
sample_process() {
  label="$1"
  pid="$(adb shell pidof "$PACKAGE" | tr -d '\r')"
  {
    echo "label=$label"
    echo "pid=$pid"
    adb shell su -c "printf 'fds='; ls /proc/$pid/fd | wc -l"
    adb shell su -c "printf 'tasks='; ls /proc/$pid/task | wc -l"
    adb shell su -c "grep -E '^(Threads|VmRSS|VmSize):' /proc/$pid/status"
    adb shell su -c "ss -antup 2>&1 | grep '$pid' || true"
  } | tee "$EVIDENCE/phone/resources-${label}.txt"
}

sample_process baseline
```

Perform at least 20 complete enable → TCP/UDP request → disable cycles through the UI. Sample after
cycles 5, 10, 15, and 20 once the app has been idle for the same settling period.

### Pass criteria

- FD and task counts return near baseline and do not grow monotonically with cycles.
- No stale listener, UDP relay, daemon lease, or cleanup job remains after disable.
- No unresolved cleanup debt remains silently hidden.
- Any bounded cache growth is explained and stabilizes.

A consistent upward trend is a failure even if the proxy still functions.

## 16. DV-12 — UI, notification, and configuration QA

Record screenshots or screen video for:

- disabled;
- activation required after process death;
- waiting for VPN;
- waiting for tethering;
- running with endpoint and credentials;
- fail-closed/cleanup-degraded state;
- notification and Resume action;
- credential copy and rotation;
- phone portrait/landscape;
- one large-screen or tablet layout where available.

Change TCP port, UDP enable/range, credentials, VPN, and tethered clients while running.

### Pass criteria

- Material changes perform ordered restart/replacement without a broad allow window.
- Credentials are not written to logcat.
- Notification permission denial on Android 13+ remains actionable and does not produce an invisible,
  uncontrolled service.
- Clipboard and screen text match the active runtime configuration.

## 17. Final collection and sign-off

Collect final snapshots:

```bash
adb shell dumpsys connectivity > "$EVIDENCE/phone/connectivity-after.txt"
adb shell su -c 'iptables-save 2>&1 || true' > "$EVIDENCE/phone/iptables-after.txt"
adb shell su -c 'ip6tables-save 2>&1 || true' > "$EVIDENCE/phone/ip6tables-after.txt"
adb shell su -c 'nft list ruleset 2>&1 || true' > "$EVIDENCE/phone/nft-after.txt"
adb bugreport "$EVIDENCE/phone/bugreport.zip" || true
kill "$LOGCAT_PID" 2>/dev/null || true
sha256sum "$EVIDENCE"/captures/* "$EVIDENCE"/phone/* 2>/dev/null \
  > "$EVIDENCE/notes/artifact-sha256.txt" || true
```

Rotate the proxy credentials after collection.

Complete this table in `notes/results.md`:

| ID | Result | Evidence paths | Notes/issue |
| --- | --- | --- | --- |
| DV-01 activation/startup |  |  |  |
| DV-02 TCP CONNECT |  |  |  |
| DV-03 auth rejection |  |  |  |
| DV-04 UDP ASSOCIATE |  |  |  |
| DV-05 ingress containment |  |  |  |
| DV-06 VPN-only packet proof |  |  |  |
| DV-07 VPN loss |  |  |  |
| DV-08 DNS blackhole |  |  |  |
| DV-09 daemon restart / Track G |  |  |  |
| DV-10 process death / Resume |  |  |  |
| DV-11 resource lifecycle |  |  |  |
| DV-12 UI / notification |  |  |  |

The first device pass is accepted only when DV-01 through DV-10 pass with retained evidence and DV-11
shows no monotonic resource leak. DV-12 may contain documented platform/layout follow-ups, but any
foreground-service policy failure remains blocking.
