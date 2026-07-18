# Step 9 — Explicit firewall proto

Task: the daemon-side proxy-firewall configuration message. Deny state is explicit for
both address families; the UDP return policy field appears only after Step 8 evidence.
Maps to: Implementation plan Phase 6 (root proxy firewall).
Depends on: [Step 5](05-cleanup-debt-model.md) lifecycle semantics,
[Step 8](08-udp-topology.md) for `VerifiedUdpReturnPolicy`.
Daemon implementation reuses `routing/iptables.rs::IptablesRule`, its applied ledger,
`delete_repeated()` and `routing/firewall_cleanup.rs::clean()`.

```proto
message ProxyFirewallConfig {
  repeated ProxyDownstream downstreams = 1;
  uint32 tcp_port = 2;
  uint32 udp_port_range_start = 3;
  uint32 udp_port_range_end = 4;
  repeated ProxyClient allowed_clients = 5;
  uint64 generation = 6;
  bool deny_all_ipv4 = 7;
  bool deny_all_ipv6 = 8;
  optional VerifiedUdpReturnPolicy udp_return_policy = 9;
}

message ProxyDownstream {
  string interface_name = 1;
  repeated bytes ipv4_addresses = 2;
}

message ProxyClient {
  bytes mac = 1;
  repeated bytes ipv4 = 2;
}
```

`deny_all_ipv4` is explicit; an empty `allowed_clients` list is not used as a hidden deny-state flag. `VerifiedUdpReturnPolicy` is absent until Phase 0 proves exact semantics.
