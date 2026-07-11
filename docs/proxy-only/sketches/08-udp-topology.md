# Step 8 — UDP topology observations

Task: Phase 0 evidence collection for Hev's UDP socket topology. The firewall
return-path rule stays undefined until this report is correlated with packet capture
and conntrack evidence (round-2 review, amendment 1).
Maps to: Implementation plan Phase 0 (UDP discovery).
Depends on: [Step 7](07-native-hook.md) instrumentation.
Consumed by: [Step 9](09-firewall-proto.md) (`VerifiedUdpReturnPolicy`).

```kotlin
enum class UdpSocketRole {
    CLIENT_RELAY,
    INTERNET_FACING,
    SHARED_RELAY_AND_INTERNET,
}

data class UdpSocketObservation(
    val associationId: Long,
    val fd: Int,
    val role: UdpSocketRole,
    val localAddress: InetSocketAddress,
    val remoteAddress: InetSocketAddress?,
    val boundNetworkHandle: Long?,
)

data class UdpTopologyReport(
    val observations: List<UdpSocketObservation>,
    val returnedBindAddresses: List<InetSocketAddress>,
    val replyIngressInterfaces: Set<String>,
    val replyConntrackStates: Set<String>,
)
```

The firewall return rule remains absent until this report is correlated with packet capture and conntrack evidence.
