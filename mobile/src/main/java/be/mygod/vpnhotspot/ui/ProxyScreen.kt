package be.mygod.vpnhotspot.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.mygod.vpnhotspot.R
import be.mygod.vpnhotspot.proxy.FailClosedReason
import be.mygod.vpnhotspot.proxy.ProxyCredentials
import be.mygod.vpnhotspot.proxy.ProxyOnlyPreferences
import be.mygod.vpnhotspot.proxy.ProxyOnlyService
import be.mygod.vpnhotspot.proxy.ProxyOnlyState
import be.mygod.vpnhotspot.proxy.proxySettingsFlow
import kotlinx.coroutines.launch

@Composable
fun ProxyScreen(
    binder: ProxyOnlyService.Binder?,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fallbackSettings by remember { proxySettingsFlow() }
        .collectAsStateWithLifecycle(initialValue = ProxyOnlyPreferences.current())
    val settings = binder?.settings?.collectAsStateWithLifecycle()?.value ?: fallbackSettings
    val state = binder?.state?.collectAsStateWithLifecycle()?.value
        ?: if (settings.enabled) ProxyOnlyState.ActivationRequired else ProxyOnlyState.Disabled
    val credentials = binder?.credentials?.collectAsStateWithLifecycle()?.value

    var tcpPort by remember { mutableStateOf(settings.tcpPort.toString()) }
    var udpStart by remember { mutableStateOf(settings.udpPortRange.first.toString()) }
    var udpEnd by remember { mutableStateOf(settings.udpPortRange.last.toString()) }
    LaunchedEffect(settings.tcpPort, settings.udpPortRange) {
        tcpPort = settings.tcpPort.toString()
        udpStart = settings.udpPortRange.first.toString()
        udpEnd = settings.udpPortRange.last.toString()
    }

    fun action(action: String) {
        when (action) {
            ProxyOnlyService.ACTION_ENABLE -> binder?.enable() ?: ProxyOnlyService.request(context, action)
            ProxyOnlyService.ACTION_RESUME -> binder?.resume() ?: ProxyOnlyService.request(context, action)
            ProxyOnlyService.ACTION_DISABLE -> binder?.disable() ?: ProxyOnlyService.request(context, action)
            ProxyOnlyService.ACTION_ROTATE_CREDENTIALS ->
                binder?.rotateCredentials() ?: ProxyOnlyService.request(context, action)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.proxy_enable)) },
                    supportingContent = { Text(stringResource(R.string.proxy_enable_summary)) },
                    trailingContent = {
                        Switch(
                            checked = settings.enabled,
                            onCheckedChange = { checked ->
                                action(if (checked) ProxyOnlyService.ACTION_ENABLE else ProxyOnlyService.ACTION_DISABLE)
                            },
                        )
                    },
                )
                HorizontalDivider()
                ProxyStateRow(state) { action(ProxyOnlyService.ACTION_RESUME) }
            }
        }
        item {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.proxy_configuration), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = tcpPort,
                        onValueChange = { tcpPort = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.proxy_tcp_port)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.proxy_udp_enable)) },
                        supportingContent = { Text(stringResource(R.string.proxy_udp_enable_summary)) },
                        trailingContent = {
                            Switch(
                                checked = settings.udpEnabled,
                                onCheckedChange = ProxyOnlyPreferences::setUdpEnabled,
                            )
                        },
                    )
                    if (settings.udpEnabled) Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = udpStart,
                            onValueChange = { udpStart = it.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.proxy_udp_start)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = udpEnd,
                            onValueChange = { udpEnd = it.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.proxy_udp_end)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(onClick = {
                        val port = tcpPort.toIntOrNull()
                        val start = udpStart.toIntOrNull()
                        val end = udpEnd.toIntOrNull()
                        val tcpValid = port != null && port in 1..65_535
                        val udpValid = !settings.udpEnabled ||
                            (start != null && end != null && start in 1..65_535 && end in start..65_535)
                        if (!tcpValid || !udpValid) {
                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.proxy_invalid_ports)) }
                        } else {
                            ProxyOnlyPreferences.setTcpPort(checkNotNull(port))
                            if (settings.udpEnabled) {
                                ProxyOnlyPreferences.setUdpRange(checkNotNull(start)..checkNotNull(end))
                            }
                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.proxy_settings_saved)) }
                        }
                    }) {
                        Text(stringResource(R.string.wifi_save))
                    }
                }
            }
        }
        item {
            CredentialsCard(credentials, state) {
                action(ProxyOnlyService.ACTION_ROTATE_CREDENTIALS)
            }
        }
        if (state is ProxyOnlyState.Running) item {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.proxy_endpoints), style = MaterialTheme.typography.titleMedium)
                    state.endpoints.forEach { endpoint ->
                        val udp = endpoint.udpPortRange?.let { " · UDP ${it.first}-${it.last}" }.orEmpty()
                        Text(
                            "socks5://${endpoint.host}:${endpoint.tcpPort}$udp",
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProxyStateRow(state: ProxyOnlyState, onResume: () -> Unit) {
    val title = stringResource(when (state) {
        ProxyOnlyState.Disabled -> R.string.proxy_state_disabled
        ProxyOnlyState.ActivationRequired -> R.string.proxy_state_activation_required
        ProxyOnlyState.ServiceStarting -> R.string.proxy_state_starting
        ProxyOnlyState.WaitingForTethering -> R.string.proxy_state_waiting_tethering
        ProxyOnlyState.WaitingForVpn -> R.string.proxy_state_waiting_vpn
        is ProxyOnlyState.MultipleVpnCandidates -> R.string.proxy_state_multiple_vpn
        ProxyOnlyState.VpnPermissionDenied -> R.string.proxy_state_vpn_denied
        ProxyOnlyState.StartingBackend -> R.string.proxy_state_starting_backend
        is ProxyOnlyState.Running -> R.string.proxy_state_running
        is ProxyOnlyState.FailClosed -> R.string.proxy_state_fail_closed
        is ProxyOnlyState.CleanupDegraded -> R.string.proxy_state_cleanup
    })
    val detail = when (state) {
        is ProxyOnlyState.MultipleVpnCandidates -> stringResource(R.string.proxy_multiple_vpn_detail, state.count)
        is ProxyOnlyState.FailClosed -> failClosedText(state.reason)
        is ProxyOnlyState.CleanupDegraded -> stringResource(
            R.string.proxy_cleanup_detail,
            state.unresolved.size,
            state.retryAttempt,
        )
        else -> null
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (detail == null) null else {{ Text(detail) }},
        trailingContent = if (state == ProxyOnlyState.ActivationRequired) {
            { TextButton(onClick = onResume) { Text(stringResource(R.string.proxy_resume)) } }
        } else null,
    )
}

@Composable
private fun failClosedText(reason: FailClosedReason): String = when (reason) {
    FailClosedReason.RootDaemonUnavailable -> stringResource(R.string.proxy_reason_daemon)
    is FailClosedReason.BindProbeFailed -> reason.detail
    is FailClosedReason.TcpProbeFailed -> reason.detail
    is FailClosedReason.UdpProbeFailed -> reason.detail
    is FailClosedReason.DnsProbeFailed -> reason.detail
    is FailClosedReason.ListenerNotReady -> reason.detail
    is FailClosedReason.ProbeReportIncomplete -> reason.missing.joinToString()
    is FailClosedReason.StartupSanitationFailed -> reason.detail
    is FailClosedReason.InternalFailure -> reason.category
    FailClosedReason.NoReachableDownstreamAddress -> stringResource(R.string.proxy_reason_downstream_address)
}

@Composable
private fun CredentialsCard(
    credentials: ProxyCredentials?,
    state: ProxyOnlyState,
    onRotate: () -> Unit,
) {
    val context = LocalContext.current
    Card {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.proxy_credentials), style = MaterialTheme.typography.titleMedium)
            if (credentials == null) {
                Text(stringResource(R.string.proxy_credentials_unavailable))
            } else {
                Text("Username: ${credentials.username}", fontFamily = FontFamily.Monospace)
                Text("Password: ${credentials.password}", fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                            "SOCKS5 credentials",
                            "${credentials.username}\n${credentials.password}",
                        ))
                    }) { Text(stringResource(R.string.proxy_copy_credentials)) }
                    TextButton(onClick = onRotate, enabled = state != ProxyOnlyState.StartingBackend) {
                        Text(stringResource(R.string.proxy_rotate_credentials))
                    }
                }
            }
        }
    }
}
