use std::io;
use std::net::Ipv4Addr;

use vpnhotspotd::proxy_firewall::{KernelFirewall, KernelFuture};
use vpnhotspotd::shared::proto::proxy::ProxyFirewallConfig;

use crate::firewall::{self, IptablesTarget};

const IPV4_CHAIN: &str = "vpnhotspot_proxy4";
const IPV6_CHAIN: &str = "vpnhotspot_proxy6";

#[derive(Default)]
pub(crate) struct AndroidProxyFirewall;

impl KernelFirewall for AndroidProxyFirewall {
    fn sanitize<'a>(
        &'a mut self,
        containment: &'a ProxyFirewallConfig,
    ) -> KernelFuture<'a, ()> {
        Box::pin(async move { apply_config(containment).await })
    }

    fn start<'a>(
        &'a mut self,
        _handle_id: u64,
        config: &'a ProxyFirewallConfig,
    ) -> KernelFuture<'a, ()> {
        Box::pin(async move { apply_config(config).await })
    }

    fn replace<'a>(
        &'a mut self,
        _handle_id: u64,
        config: &'a ProxyFirewallConfig,
    ) -> KernelFuture<'a, ()> {
        Box::pin(async move { apply_config(config).await })
    }

    fn deny<'a>(
        &'a mut self,
        _handle_id: u64,
        config: &'a ProxyFirewallConfig,
    ) -> KernelFuture<'a, ()> {
        Box::pin(async move { apply_config(config).await })
    }

    fn stop<'a>(&'a mut self, _handle_id: u64) -> KernelFuture<'a, ()> {
        Box::pin(async move { clean_proxy_chains().await })
    }
}

pub(crate) async fn clean_proxy_chains() -> io::Result<()> {
    let ipv4 = clean_chain(IptablesTarget::Ipv4, IPV4_CHAIN).await;
    let ipv6 = clean_chain(IptablesTarget::Ipv6, IPV6_CHAIN).await;
    match (ipv4, ipv6) {
        (Err(first), _) => Err(first),
        (_, Err(second)) => Err(second),
        _ => Ok(()),
    }
}

async fn clean_chain(target: IptablesTarget, chain: &'static str) -> io::Result<()> {
    let delete_jump = firewall::restore_input(
        "filter",
        &[firewall::restore_line(
            "-D",
            "INPUT",
            &["-j".to_owned(), chain.to_owned()],
        )?],
    );
    loop {
        if !firewall::restore_status(target, &delete_jump).await? {
            break;
        }
    }

    let flush_delete = format!("*filter\n:{chain} - [0:0]\n-X {chain}\nCOMMIT\n");
    firewall::restore(target, &flush_delete).await
}

async fn apply_config(config: &ProxyFirewallConfig) -> io::Result<()> {
    // Check-only commands do not mutate kernel state. Under the daemon's proxy
    // mutex this determines whether chain creation and jump insertion must be
    // included in the same restore transaction as the new rules.
    let ipv4_jump = firewall::rule_exists(
        IptablesTarget::Ipv4,
        "filter",
        "INPUT",
        &["-j", IPV4_CHAIN],
    )
    .await?;
    let ipv6_jump = firewall::rule_exists(
        IptablesTarget::Ipv6,
        "filter",
        "INPUT",
        &["-j", IPV6_CHAIN],
    )
    .await?;

    let ipv4 = render_ipv4(config, !ipv4_jump)?;
    let ipv6 = render_ipv6(config, !ipv6_jump)?;

    if config.deny_all_ipv4 {
        // During sanitation/deny, contain the primary IPv4 exposure first.
        firewall::restore(IptablesTarget::Ipv4, &ipv4).await?;
        firewall::restore(IptablesTarget::Ipv6, &ipv6).await
    } else {
        // During allow transition, establish IPv6 rejection before IPv4 opens.
        firewall::restore(IptablesTarget::Ipv6, &ipv6).await?;
        firewall::restore(IptablesTarget::Ipv4, &ipv4).await
    }
}

fn render_ipv4(config: &ProxyFirewallConfig, install_jump: bool) -> io::Result<String> {
    let mut lines = vec![format!(":{IPV4_CHAIN} - [0:0]")];

    if !config.deny_all_ipv4 {
        for downstream in &config.downstreams {
            for client in &config.allowed_clients {
                let mac = format_mac(&client.mac)?;
                for address in &client.ipv4 {
                    let source = format_ipv4(address)?;
                    push_accept_rule(
                        &mut lines,
                        IPV4_CHAIN,
                        &downstream.interface_name,
                        &source,
                        &mac,
                        "tcp",
                        config.tcp_port,
                        None,
                    )?;
                    if udp_enabled(config) {
                        push_accept_rule(
                            &mut lines,
                            IPV4_CHAIN,
                            &downstream.interface_name,
                            &source,
                            &mac,
                            "udp",
                            config.udp_port_range_start,
                            Some(config.udp_port_range_end),
                        )?;
                    }
                }
            }
        }
    }

    for downstream in &config.downstreams {
        push_reject_rule(
            &mut lines,
            IPV4_CHAIN,
            &downstream.interface_name,
            "tcp",
            config.tcp_port,
            None,
        )?;
        if udp_enabled(config) {
            push_reject_rule(
                &mut lines,
                IPV4_CHAIN,
                &downstream.interface_name,
                "udp",
                config.udp_port_range_start,
                Some(config.udp_port_range_end),
            )?;
        }
    }

    // The listener is intentionally bound to 0.0.0.0 so multiple tethering
    // interfaces can share one backend. Allowed downstream rules above must be
    // the only path to these ports; reject every other interface before RETURN.
    push_global_reject_rule(
        &mut lines,
        IPV4_CHAIN,
        "tcp",
        config.tcp_port,
        None,
    )?;
    if udp_enabled(config) {
        push_global_reject_rule(
            &mut lines,
            IPV4_CHAIN,
            "udp",
            config.udp_port_range_start,
            Some(config.udp_port_range_end),
        )?;
    }

    lines.push(firewall::restore_line(
        "-A",
        IPV4_CHAIN,
        &["-j".to_owned(), "RETURN".to_owned()],
    )?);
    if install_jump {
        lines.push(firewall::restore_line(
            "-I",
            "INPUT",
            &["-j".to_owned(), IPV4_CHAIN.to_owned()],
        )?);
    }
    Ok(firewall::restore_input("filter", &lines))
}

fn render_ipv6(config: &ProxyFirewallConfig, install_jump: bool) -> io::Result<String> {
    let mut lines = vec![format!(":{IPV6_CHAIN} - [0:0]")];

    // There is intentionally no IPv6 client allow model in Phase 0. Every
    // downstream listener/relay port is rejected for IPv6, even after IPv4 ACL
    // activation, preventing an address-family bypass.
    for downstream in &config.downstreams {
        push_reject_rule(
            &mut lines,
            IPV6_CHAIN,
            &downstream.interface_name,
            "tcp",
            config.tcp_port,
            None,
        )?;
        if udp_enabled(config) {
            push_reject_rule(
                &mut lines,
                IPV6_CHAIN,
                &downstream.interface_name,
                "udp",
                config.udp_port_range_start,
                Some(config.udp_port_range_end),
            )?;
        }
    }
    push_global_reject_rule(
        &mut lines,
        IPV6_CHAIN,
        "tcp",
        config.tcp_port,
        None,
    )?;
    if udp_enabled(config) {
        push_global_reject_rule(
            &mut lines,
            IPV6_CHAIN,
            "udp",
            config.udp_port_range_start,
            Some(config.udp_port_range_end),
        )?;
    }
    lines.push(firewall::restore_line(
        "-A",
        IPV6_CHAIN,
        &["-j".to_owned(), "RETURN".to_owned()],
    )?);
    if install_jump {
        lines.push(firewall::restore_line(
            "-I",
            "INPUT",
            &["-j".to_owned(), IPV6_CHAIN.to_owned()],
        )?);
    }
    Ok(firewall::restore_input("filter", &lines))
}

#[allow(clippy::too_many_arguments)]
fn push_accept_rule(
    lines: &mut Vec<String>,
    chain: &str,
    interface: &str,
    source: &str,
    mac: &str,
    protocol: &str,
    port_start: u32,
    port_end: Option<u32>,
) -> io::Result<()> {
    lines.push(firewall::restore_line(
        "-A",
        chain,
        &[
            "-i".to_owned(),
            interface.to_owned(),
            "-s".to_owned(),
            source.to_owned(),
            "-m".to_owned(),
            "mac".to_owned(),
            "--mac-source".to_owned(),
            mac.to_owned(),
            "-p".to_owned(),
            protocol.to_owned(),
            "--dport".to_owned(),
            port_spec(port_start, port_end),
            "-j".to_owned(),
            "ACCEPT".to_owned(),
        ],
    )?);
    Ok(())
}

fn push_reject_rule(
    lines: &mut Vec<String>,
    chain: &str,
    interface: &str,
    protocol: &str,
    port_start: u32,
    port_end: Option<u32>,
) -> io::Result<()> {
    lines.push(firewall::restore_line(
        "-A",
        chain,
        &[
            "-i".to_owned(),
            interface.to_owned(),
            "-p".to_owned(),
            protocol.to_owned(),
            "--dport".to_owned(),
            port_spec(port_start, port_end),
            "-j".to_owned(),
            "REJECT".to_owned(),
        ],
    )?);
    Ok(())
}

fn push_global_reject_rule(
    lines: &mut Vec<String>,
    chain: &str,
    protocol: &str,
    port_start: u32,
    port_end: Option<u32>,
) -> io::Result<()> {
    lines.push(firewall::restore_line(
        "-A",
        chain,
        &[
            "-p".to_owned(),
            protocol.to_owned(),
            "--dport".to_owned(),
            port_spec(port_start, port_end),
            "-j".to_owned(),
            "REJECT".to_owned(),
        ],
    )?);
    Ok(())
}

fn udp_enabled(config: &ProxyFirewallConfig) -> bool {
    config.udp_port_range_start != 0 || config.udp_port_range_end != 0
}

fn port_spec(start: u32, end: Option<u32>) -> String {
    match end {
        Some(end) if end != start => format!("{start}:{end}"),
        _ => start.to_string(),
    }
}

fn format_ipv4(bytes: &[u8]) -> io::Result<String> {
    let octets: [u8; 4] = bytes.try_into().map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidInput, "IPv4 address must contain 4 bytes")
    })?;
    Ok(Ipv4Addr::from(octets).to_string())
}

fn format_mac(bytes: &[u8]) -> io::Result<String> {
    let mac: [u8; 6] = bytes.try_into().map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidInput, "MAC address must contain 6 bytes")
    })?;
    Ok(mac
        .iter()
        .map(|byte| format!("{byte:02X}"))
        .collect::<Vec<_>>()
        .join(":"))
}

#[cfg(test)]
mod tests {
    use vpnhotspotd::shared::proto::proxy::{ProxyClient, ProxyDownstream};

    use super::*;

    fn config(deny: bool) -> ProxyFirewallConfig {
        ProxyFirewallConfig {
            downstreams: vec![ProxyDownstream {
                interface_name: "wlan0".to_owned(),
                ipv4_addresses: vec![vec![192, 168, 43, 1]],
            }],
            tcp_port: 1080,
            udp_port_range_start: 20_000,
            udp_port_range_end: 20_100,
            allowed_clients: vec![ProxyClient {
                mac: vec![0x02, 0, 0, 0, 0, 1],
                ipv4: vec![vec![192, 168, 43, 2]],
            }],
            generation: 1,
            deny_all_ipv4: deny,
            deny_all_ipv6: true,
            udp_return_policy: None,
        }
    }

    #[test]
    fn ipv4_allow_requires_interface_ip_and_mac_and_rejects_every_other_interface() {
        let rendered = render_ipv4(&config(false), true).unwrap();
        assert!(rendered.contains("-i wlan0 -s 192.168.43.2 -m mac --mac-source 02:00:00:00:00:01 -p tcp --dport 1080 -j ACCEPT"));
        assert!(rendered.contains("-i wlan0 -p tcp --dport 1080 -j REJECT"));
        assert!(rendered.contains("-i wlan0 -p udp --dport 20000:20100 -j REJECT"));
        assert!(rendered.contains("-A vpnhotspot_proxy4 -p tcp --dport 1080 -j REJECT"));
        assert!(rendered.contains("-A vpnhotspot_proxy4 -p udp --dport 20000:20100 -j REJECT"));
    }

    #[test]
    fn deny_first_contains_no_accept_rule() {
        let rendered = render_ipv4(&config(true), true).unwrap();
        assert!(!rendered.contains("-j ACCEPT"));
        assert!(rendered.contains("-j REJECT"));
    }

    #[test]
    fn ipv6_always_rejects_listener_and_udp_range_globally() {
        let rendered = render_ipv6(&config(false), true).unwrap();
        assert!(!rendered.contains("-j ACCEPT"));
        assert!(rendered.contains("-A vpnhotspot_proxy6 -p tcp --dport 1080 -j REJECT"));
        assert!(rendered.contains("-A vpnhotspot_proxy6 -p udp --dport 20000:20100 -j REJECT"));
    }
}
