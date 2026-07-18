package be.mygod.vpnhotspot.proxy

internal const val DOWNSTREAM_ADDRESS_POLICY = "smallest-routable-ipv4"

/** Non-fatal diagnostics produced while normalizing a desired snapshot. */
data class ProxyNormalizationReport(
    val droppedDownstreams: List<Dropped> = emptyList(),
    val droppedClients: List<Dropped> = emptyList(),
    val downstreamAddressChoices: List<AddressChoice> = emptyList(),
) {
    data class Dropped(
        val identity: String,
        val reason: Reason,
    )

    data class AddressChoice(
        val interfaceName: String,
        val candidates: List<String>,
        val chosen: String?,
        val policy: String,
    )

    enum class Reason {
        INVALID_INTERFACE_NAME,
        INVALID_MAC,
        NO_ROUTABLE_IPV4,
        NON_ROUTABLE_IPV4_FILTERED,
    }

    val isClean: Boolean get() = droppedDownstreams.isEmpty() && droppedClients.isEmpty()
}

data class NormalizationOutput(
    val state: DesiredProxyState,
    val report: ProxyNormalizationReport,
)
