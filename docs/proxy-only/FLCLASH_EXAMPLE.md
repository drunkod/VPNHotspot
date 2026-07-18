# FlClash selective VPN and WARP example

This example assumes VPN Hotspot runs in `PROXY_ONLY` mode and displays:

```text
Host: 192.168.43.1
Port: 10808
Username: generated-user
Password: generated-password
UDP: enabled
```

Replace placeholders with values shown by the Android application.

## Traffic model

```text
Applications matched by SELECTIVE-VPN
  -> PhoneVPN or WARP-via-PhoneVPN

Everything else
  -> DIRECT
  -> ordinary Android system tethering
  -> carrier/physical network
```

`DIRECT` is only truly direct when VPN Hotspot does not enable its existing full VPN-routing session for that downstream interface.

## Complete example

```yaml
mixed-port: 7890
allow-lan: false
mode: rule
log-level: info
ipv6: false

find-process-mode: always

external-controller: 127.0.0.1:9090
secret: ""

profile:
  store-selected: true

tun:
  enable: true
  stack: system
  dns-hijack:
    - any:53
    - tcp://any:53
  auto-route: true
  auto-detect-interface: true

dns:
  enable: true
  ipv6: false
  enhanced-mode: fake-ip
  fake-ip-range: 198.18.0.1/16

  fake-ip-filter:
    - "*.lan"
    - "localhost"
    - "+.local"

  # Fast direct resolver for normal laptop traffic and proxy endpoint bootstrap.
  default-nameserver:
    - 1.1.1.1
    - 8.8.8.8

  nameserver:
    - "https://1.1.1.1/dns-query#DIRECT"
    - "https://8.8.8.8/dns-query#DIRECT"

  proxy-server-nameserver:
    - 1.1.1.1
    - 8.8.8.8

proxies:
  - name: PhoneVPN
    type: socks5
    server: 192.168.43.1
    port: 10808
    username: generated-user
    password: generated-password
    udp: true

  - name: WARP-via-PhoneVPN
    type: wireguard

    # Values from a valid WARP WireGuard profile.
    server: "<WARP_ENDPOINT_HOST_OR_IP>"
    port: <WARP_ENDPOINT_PORT>
    ip: "<WARP_INTERFACE_IPV4_WITHOUT_/32>"
    private-key: "<WARP_PRIVATE_KEY>"
    public-key: "<CLOUDFLARE_PUBLIC_KEY>"

    allowed-ips:
      - "0.0.0.0/0"

    udp: true
    mtu: 1280

    # The WARP transport itself is sent through the phone SOCKS5 server.
    dialer-proxy: PhoneVPN

    remote-dns-resolve: true
    dns:
      - 1.1.1.1
      - 1.0.0.1

    # Add only when present/required by the source profile.
    # persistent-keepalive: 25
    # reserved: [0, 0, 0]

proxy-groups:
  - name: SELECTIVE-VPN
    type: select
    proxies:
      - WARP-via-PhoneVPN
      - PhoneVPN
      - DIRECT

rules:
  # Local and tethering networks must remain reachable directly.
  - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve
  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve
  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve
  - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve

  # Large updates that should avoid the slower VPN path.
  - DOMAIN,dl.google.com,DIRECT
  - DOMAIN,npmjs.org,DIRECT
  - DOMAIN,registry.npmjs.org,DIRECT
  - DOMAIN,static.rust-lang.org,DIRECT
  - DOMAIN-SUFFIX,rust-lang.org,DIRECT

  # Browsers.
  - PROCESS-NAME,com.apple.WebKit.Networking,SELECTIVE-VPN
  - PROCESS-NAME,Safari,SELECTIVE-VPN
  - PROCESS-NAME-REGEX,(?i)google chrome,SELECTIVE-VPN
  - PROCESS-NAME-REGEX,(?i)google chrome helper,SELECTIVE-VPN
  - PROCESS-NAME,firefox,SELECTIVE-VPN
  - PROCESS-NAME-WILDCARD,*Arc*,SELECTIVE-VPN

  # Helium.
  - PROCESS-NAME,Helium,SELECTIVE-VPN
  - PROCESS-NAME,Helium Helper,SELECTIVE-VPN
  - PROCESS-NAME,Helium Helper (Renderer),SELECTIVE-VPN
  - PROCESS-PATH-REGEX,/Applications/Helium\.app/.*,SELECTIVE-VPN

  # Messaging.
  - PROCESS-NAME-REGEX,(?i)telegram,SELECTIVE-VPN
  - PROCESS-NAME-REGEX,(?i)whatsapp,SELECTIVE-VPN
  - PROCESS-NAME-REGEX,(?i)discord,SELECTIVE-VPN
  - PROCESS-NAME-REGEX,(?i)slack,SELECTIVE-VPN
  - PROCESS-NAME-REGEX,(?i)signal,SELECTIVE-VPN

  # Developer tools.
  - PROCESS-NAME,curl,SELECTIVE-VPN
  - PROCESS-NAME,wget,SELECTIVE-VPN
  - PROCESS-NAME,git,SELECTIVE-VPN
  - PROCESS-NAME,node,SELECTIVE-VPN
  - PROCESS-NAME,npm,SELECTIVE-VPN
  - PROCESS-NAME,ssh,SELECTIVE-VPN
  - PROCESS-NAME-REGEX,(?i)code helper,SELECTIVE-VPN
  - PROCESS-NAME-WILDCARD,*Code*,SELECTIVE-VPN

  # Antigravity.
  - PROCESS-NAME,Antigravity,SELECTIVE-VPN
  - PROCESS-NAME,Antigravity Helper,SELECTIVE-VPN
  - PROCESS-NAME,Antigravity Helper (Renderer),SELECTIVE-VPN
  - PROCESS-NAME,language_server,SELECTIVE-VPN
  - PROCESS-PATH-REGEX,/Applications/Antigravity\.app/.*,SELECTIVE-VPN

  # Kitty.
  - PROCESS-NAME,kitty,SELECTIVE-VPN
  - PROCESS-NAME,kitten,SELECTIVE-VPN
  - PROCESS-PATH-REGEX,(?i).*(kitty|kitten)\.app/Contents/MacOS/(kitty|kitten),SELECTIVE-VPN
  - PROCESS-PATH-REGEX,/nix/store/.*/kitty.*/Contents/MacOS/.*,SELECTIVE-VPN

  # Media.
  - PROCESS-NAME-REGEX,(?i)spotify,SELECTIVE-VPN

  # Everything not listed above uses ordinary direct tethering.
  - MATCH,DIRECT
```

## Mode selection

### `SELECTIVE-VPN -> PhoneVPN`

```text
Selected application
  -> SOCKS5 inside VPN Hotspot
  -> phone VPN
  -> Internet
```

Expected public IP: phone VPN exit IP.

### `SELECTIVE-VPN -> WARP-via-PhoneVPN`

```text
Selected application
  -> WARP WireGuard in FlClash
  -> SOCKS5 inside VPN Hotspot
  -> phone VPN
  -> Cloudflare WARP
  -> Internet
```

Expected public IP: Cloudflare WARP IP.

### `SELECTIVE-VPN -> DIRECT`

Selected applications temporarily bypass both SOCKS5 and WARP and use ordinary Android tethering.

Expected public IP: carrier/physical network IP.

## Validation

Direct path:

```bash
python3 -c 'import urllib.request; print(urllib.request.urlopen("https://ifconfig.me").read().decode())'
```

Phone VPN through SOCKS5:

```bash
curl --socks5-hostname generated-user:generated-password@192.168.43.1:10808 \
  https://ifconfig.me
```

WARP through the selected FlClash route:

```bash
curl https://www.cloudflare.com/cdn-cgi/trace
```

Expected trace contains:

```text
warp=on
```

or:

```text
warp=plus
```

## Troubleshooting

### `PhoneVPN` works but `WARP-via-PhoneVPN` times out

Likely causes:

- SOCKS5 `UDP ASSOCIATE` is not implemented or disabled;
- the native UDP socket was not bound to the VPN network;
- the phone VPN blocks the WARP endpoint/port;
- FlClash did not select the intended proxy group;
- an MTU problem affects WireGuard.

Check native UDP association counters and FlClash logs. `udp: true` in YAML does not prove the server side supports UDP.

### All laptop applications still show the VPN IP

The Android downstream is still using the existing full VPN-routing mode. Select `PROXY_ONLY` and verify ordinary system tethering remains the direct path.

### Proxy stops when the VPN reconnects

Expected temporarily. The implementation must fail closed, discard the old network generation and restart only after a new VPN `Network` is available.

### DNS appears outside the VPN for proxied applications

Use SOCKS hostname resolution and verify the Android proxy resolves domain-form requests with the selected VPN `Network`. The server must not use a process-default DNS resolver in fail-closed mode.

## Credential handling

- Never commit real SOCKS5 credentials.
- Never commit a WARP private key.
- Do not paste the generated complete configuration into public issues or logs.
- Regenerate credentials after sharing them accidentally.
