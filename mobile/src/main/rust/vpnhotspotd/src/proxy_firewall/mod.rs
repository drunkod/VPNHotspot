mod ledger;
mod session;

use std::future::Future;
use std::io;
use std::pin::Pin;

use ledger::AppliedLedger;
pub use session::{FileSessionStore, ProxySession, SessionStore, Validation};

use crate::shared::proto::proxy::{
    proxy_firewall_ack, proxy_firewall_command, DaemonIdentity, DenyRequest,
    ProxyFirewallAck, ProxyFirewallCommand, ProxyFirewallConfig, ReplaceRequest,
    SanitizeRequest, StartRequest, StopRequest,
};

pub type KernelFuture<'a, T> = Pin<Box<dyn Future<Output = io::Result<T>> + Send + 'a>>;

/// Kernel mutation surface. The caller holds the proxy-firewall state lock for
/// the full lifetime of each future, making token validation and mutation one
/// serialized check-and-use transaction.
pub trait KernelFirewall: Send {
    fn sanitize<'a>(
        &'a mut self,
        containment: &'a ProxyFirewallConfig,
    ) -> KernelFuture<'a, ()>;
    fn start<'a>(
        &'a mut self,
        handle_id: u64,
        config: &'a ProxyFirewallConfig,
    ) -> KernelFuture<'a, ()>;
    fn replace<'a>(
        &'a mut self,
        handle_id: u64,
        config: &'a ProxyFirewallConfig,
    ) -> KernelFuture<'a, ()>;
    fn deny<'a>(
        &'a mut self,
        handle_id: u64,
        config: &'a ProxyFirewallConfig,
    ) -> KernelFuture<'a, ()>;
    fn stop<'a>(&'a mut self, handle_id: u64) -> KernelFuture<'a, ()>;
}

pub struct ProxyFirewall<K> {
    session: ProxySession,
    ledger: AppliedLedger,
    kernel: K,
}

impl<K: KernelFirewall> ProxyFirewall<K> {
    pub fn boot(store: &dyn SessionStore, kernel: K) -> io::Result<Self> {
        Ok(Self {
            session: ProxySession::boot(store)?,
            ledger: AppliedLedger::default(),
            kernel,
        })
    }

    pub fn identity(&self) -> DaemonIdentity {
        self.session.identity()
    }

    pub async fn handle(&mut self, command: ProxyFirewallCommand) -> ProxyFirewallAck {
        let Some(kind) = command.kind else {
            return self.ack(
                proxy_firewall_ack::Status::Invalid,
                0,
                "missing proxy firewall command kind",
            );
        };
        match kind {
            proxy_firewall_command::Kind::Sanitize(request) => self.sanitize(request).await,
            proxy_firewall_command::Kind::Start(request) => self.start(request).await,
            proxy_firewall_command::Kind::Replace(request) => self.replace(request).await,
            proxy_firewall_command::Kind::Deny(request) => self.deny(request).await,
            proxy_firewall_command::Kind::Stop(request) => self.stop(request).await,
        }
    }

    /// Execute daemon-wide kernel cleanup while this proxy-firewall state is
    /// exclusively borrowed, then clear the applied ledger and advance the epoch.
    /// A failed cleanup returns without changing either ledger or identity.
    pub async fn record_external_clean<F, Fut>(&mut self, clean: F) -> io::Result<()>
    where
        F: FnOnce() -> Fut,
        Fut: Future<Output = io::Result<()>>,
    {
        clean().await?;
        self.ledger.clear();
        self.session.bump_epoch()?;
        Ok(())
    }

    async fn sanitize(&mut self, request: SanitizeRequest) -> ProxyFirewallAck {
        let Some(config) = request.containment_config else {
            return self.ack(
                proxy_firewall_ack::Status::Invalid,
                0,
                "missing sanitation containment config",
            );
        };
        if let Err(detail) = validate_config(&config, true, false) {
            return self.ack(proxy_firewall_ack::Status::Invalid, 0, detail);
        }
        if !config.allowed_clients.is_empty() {
            return self.ack(
                proxy_firewall_ack::Status::Invalid,
                0,
                "sanitation containment config must not contain allowed clients",
            );
        }

        // Security ordering: kernel deny containment must succeed before the
        // ledger is cleared and before the new epoch becomes observable.
        match self.kernel.sanitize(&config).await {
            Ok(()) => {
                self.ledger.clear();
                match self.session.bump_epoch() {
                    Ok(_) => self.ack(proxy_firewall_ack::Status::Ok, 0, "sanitized"),
                    Err(error) => self.io_ack("sanitize epoch update", error),
                }
            }
            Err(error) => self.io_ack("sanitize", error),
        }
    }

    async fn start(&mut self, request: StartRequest) -> ProxyFirewallAck {
        if let Some(stale) = self.validate(request.expected_session_id, request.expected_epoch) {
            return stale;
        }
        let Some(config) = request.config else {
            return self.ack(proxy_firewall_ack::Status::Invalid, 0, "missing start config");
        };
        if let Err(detail) = validate_config(&config, true, true) {
            return self.ack(proxy_firewall_ack::Status::Invalid, 0, detail);
        }
        if !self.ledger.is_empty() {
            return self.ack(
                proxy_firewall_ack::Status::Invalid,
                0,
                "a proxy firewall runtime is already active",
            );
        }
        let handle_id = match self.ledger.allocate_handle() {
            Ok(handle_id) => handle_id,
            Err(error) => return self.io_ack("allocate handle", error),
        };
        match self.kernel.start(handle_id, &config).await {
            Ok(()) => {
                self.ledger.insert(handle_id, config);
                self.ack(proxy_firewall_ack::Status::Ok, handle_id, "started")
            }
            Err(error) => self.io_ack("start", error),
        }
    }

    async fn replace(&mut self, request: ReplaceRequest) -> ProxyFirewallAck {
        if let Some(stale) = self.validate(request.expected_session_id, request.expected_epoch) {
            return stale;
        }
        let Some(config) = request.config else {
            return self.ack(proxy_firewall_ack::Status::Invalid, 0, "missing replace config");
        };
        if let Err(detail) = validate_config(&config, false, true) {
            return self.ack(proxy_firewall_ack::Status::Invalid, 0, detail);
        }
        let Some(current) = self.ledger.get(request.handle_id) else {
            return self.ack(proxy_firewall_ack::Status::Invalid, 0, "unknown runtime handle");
        };
        if config.generation <= current.generation {
            return self.ack(
                proxy_firewall_ack::Status::Invalid,
                0,
                "replacement generation is not newer than the applied generation",
            );
        }
        match self.kernel.replace(request.handle_id, &config).await {
            Ok(()) => {
                self.ledger.replace(request.handle_id, config);
                self.ack(proxy_firewall_ack::Status::Ok, request.handle_id, "replaced")
            }
            Err(error) => self.io_ack("replace", error),
        }
    }

    async fn deny(&mut self, request: DenyRequest) -> ProxyFirewallAck {
        if let Some(stale) = self.validate(request.expected_session_id, request.expected_epoch) {
            return stale;
        }
        let Some(current) = self.ledger.get(request.handle_id).cloned() else {
            return self.ack(proxy_firewall_ack::Status::Invalid, 0, "unknown runtime handle");
        };
        let mut denied = current;
        denied.allowed_clients.clear();
        denied.deny_all_ipv4 = true;
        denied.deny_all_ipv6 = true;
        match self.kernel.deny(request.handle_id, &denied).await {
            Ok(()) => {
                self.ledger.replace(request.handle_id, denied);
                self.ack(proxy_firewall_ack::Status::Ok, request.handle_id, "denied")
            }
            Err(error) => self.io_ack("deny", error),
        }
    }

    async fn stop(&mut self, request: StopRequest) -> ProxyFirewallAck {
        if let Some(stale) = self.validate(request.expected_session_id, request.expected_epoch) {
            return stale;
        }
        if self.ledger.get(request.handle_id).is_none() {
            return self.ack(proxy_firewall_ack::Status::Invalid, 0, "unknown runtime handle");
        }
        match self.kernel.stop(request.handle_id).await {
            Ok(()) => {
                self.ledger.remove(request.handle_id);
                self.ack(proxy_firewall_ack::Status::Ok, request.handle_id, "stopped")
            }
            Err(error) => self.io_ack("stop", error),
        }
    }

    fn validate(&self, session_id: u64, epoch: u64) -> Option<ProxyFirewallAck> {
        match self.session.validate(session_id, epoch) {
            Validation::Current => None,
            Validation::StaleSession => Some(self.ack(
                proxy_firewall_ack::Status::StaleSession,
                0,
                "rejected before mutation: stale daemon session",
            )),
            Validation::StaleEpoch => Some(self.ack(
                proxy_firewall_ack::Status::StaleEpoch,
                0,
                "rejected before mutation: stale or conflicting sanitation epoch",
            )),
        }
    }

    fn io_ack(&self, operation: &str, error: io::Error) -> ProxyFirewallAck {
        self.ack(
            proxy_firewall_ack::Status::IoError,
            0,
            format!("{operation}: {error}"),
        )
    }

    fn ack(
        &self,
        status: proxy_firewall_ack::Status,
        handle_id: u64,
        detail: impl Into<String>,
    ) -> ProxyFirewallAck {
        ProxyFirewallAck {
            status: status as i32,
            identity: Some(self.session.identity()),
            handle_id,
            detail: detail.into(),
        }
    }
}

fn validate_config(
    config: &ProxyFirewallConfig,
    require_deny_first: bool,
    require_downstream: bool,
) -> Result<(), String> {
    if config.tcp_port == 0 || config.tcp_port > u16::MAX as u32 {
        return Err("tcp_port must be in 1..=65535".to_owned());
    }
    let udp_disabled = config.udp_port_range_start == 0 && config.udp_port_range_end == 0;
    let udp_valid = config.udp_port_range_start > 0
        && config.udp_port_range_start <= config.udp_port_range_end
        && config.udp_port_range_end <= u16::MAX as u32;
    if !udp_disabled && !udp_valid {
        return Err("UDP port range must be 0/0 or a valid inclusive range".to_owned());
    }
    if config.generation == 0 {
        return Err("configuration generation must be non-zero".to_owned());
    }
    if require_downstream && config.downstreams.is_empty() {
        return Err("at least one downstream is required".to_owned());
    }
    if require_deny_first && (!config.deny_all_ipv4 || !config.deny_all_ipv6) {
        return Err("operation requires explicit IPv4 and IPv6 deny-first configuration".to_owned());
    }
    if require_deny_first && !config.allowed_clients.is_empty() {
        return Err("deny-first configuration must not contain allowed clients".to_owned());
    }
    if require_deny_first && !config.allowed_clients.is_empty() {
        return Err("deny-first configuration must not contain allowed clients".to_owned());
    }
    if require_deny_first && !config.allowed_clients.is_empty() {
        return Err("deny-first configuration must not contain allowed clients".to_owned());
    }
    if !config.deny_all_ipv6 {
        return Err("IPv6 allow mode is unsupported; deny_all_ipv6 must remain true".to_owned());
    }
    for downstream in &config.downstreams {
        if !valid_interface_name(&downstream.interface_name) {
            return Err(format!("invalid downstream interface {:?}", downstream.interface_name));
        }
        if downstream.ipv4_addresses.iter().any(|address| address.len() != 4) {
            return Err("downstream IPv4 addresses must be packed 4-byte values".to_owned());
        }
    }
    for client in &config.allowed_clients {
        if client.mac.len() != 6 || client.ipv4.is_empty() {
            return Err("clients require a 6-byte MAC and at least one IPv4 binding".to_owned());
        }
        if client.ipv4.iter().any(|address| address.len() != 4) {
            return Err("client IPv4 addresses must be packed 4-byte values".to_owned());
        }
    }
    Ok(())
}

fn valid_interface_name(name: &str) -> bool {
    !name.is_empty()
        && name != "."
        && name != ".."
        && name.len() <= 15
        && name.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-' | b'.' | b':')
        })
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicU64, Ordering};

    use crate::shared::proto::proxy;

    use super::*;

    #[derive(Default)]
    struct MemoryStore(AtomicU64);

    impl SessionStore for MemoryStore {
        fn next_session_id(&self) -> io::Result<u64> {
            Ok(self.0.fetch_add(1, Ordering::SeqCst) + 1)
        }
    }

    #[derive(Default)]
    struct SpyKernel {
        calls: Vec<&'static str>,
        fail_sanitize: bool,
    }

    impl KernelFirewall for SpyKernel {
        fn sanitize<'a>(
            &'a mut self,
            containment: &'a ProxyFirewallConfig,
        ) -> KernelFuture<'a, ()> {
            Box::pin(async move {
                assert!(containment.deny_all_ipv4);
                assert!(containment.deny_all_ipv6);
                assert!(containment.allowed_clients.is_empty());
                self.calls.push("sanitize");
                if self.fail_sanitize {
                    Err(io::Error::other("sanitize failed"))
                } else {
                    Ok(())
                }
            })
        }

        fn start<'a>(
            &'a mut self,
            _handle_id: u64,
            _config: &'a ProxyFirewallConfig,
        ) -> KernelFuture<'a, ()> {
            Box::pin(async move {
                self.calls.push("start");
                Ok(())
            })
        }

        fn replace<'a>(
            &'a mut self,
            _handle_id: u64,
            _config: &'a ProxyFirewallConfig,
        ) -> KernelFuture<'a, ()> {
            Box::pin(async move {
                self.calls.push("replace");
                Ok(())
            })
        }

        fn deny<'a>(
            &'a mut self,
            _handle_id: u64,
            config: &'a ProxyFirewallConfig,
        ) -> KernelFuture<'a, ()> {
            Box::pin(async move {
                assert!(config.deny_all_ipv4);
                assert!(config.deny_all_ipv6);
                assert!(config.allowed_clients.is_empty());
                self.calls.push("deny");
                Ok(())
            })
        }

        fn stop<'a>(&'a mut self, _handle_id: u64) -> KernelFuture<'a, ()> {
            Box::pin(async move {
                self.calls.push("stop");
                Ok(())
            })
        }
    }

    fn status(ack: &ProxyFirewallAck) -> proxy_firewall_ack::Status {
        proxy_firewall_ack::Status::try_from(ack.status).expect("valid status")
    }

    fn config(generation: u64, deny: bool) -> ProxyFirewallConfig {
        ProxyFirewallConfig {
            downstreams: vec![proxy::ProxyDownstream {
                interface_name: "wlan0".to_owned(),
                ipv4_addresses: vec![vec![192, 168, 43, 1]],
            }],
            tcp_port: 1080,
            udp_port_range_start: 20_000,
            udp_port_range_end: 20_100,
            allowed_clients: if deny {
                Vec::new()
            } else {
                vec![proxy::ProxyClient {
                    mac: vec![0x02, 0, 0, 0, 0, 1],
                    ipv4: vec![vec![192, 168, 43, 2]],
                }]
            },
            generation,
            deny_all_ipv4: deny,
            deny_all_ipv6: true,
            udp_return_policy: None,
        }
    }

    fn sanitize_command() -> ProxyFirewallCommand {
        ProxyFirewallCommand {
            kind: Some(proxy_firewall_command::Kind::Sanitize(SanitizeRequest {
                reason: "test".to_owned(),
                containment_config: Some(config(1, true)),
            })),
        }
    }

    #[tokio::test]
    async fn stale_session_command_makes_no_kernel_change() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let ack = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(config(1, true)),
                    expected_session_id: 99,
                    expected_epoch: 0,
                })),
            })
            .await;
        assert_eq!(status(&ack), proxy_firewall_ack::Status::StaleSession);
        assert!(firewall.kernel.calls.is_empty());
    }

    #[tokio::test]
    async fn stale_epoch_command_makes_no_kernel_change() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        let ack = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(config(1, true)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch + 1,
                })),
            })
            .await;
        assert_eq!(status(&ack), proxy_firewall_ack::Status::StaleEpoch);
        assert!(firewall.kernel.calls.is_empty());
    }

    #[tokio::test]
    async fn sanitize_failure_does_not_advance_epoch() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(
            &store,
            SpyKernel {
                fail_sanitize: true,
                ..SpyKernel::default()
            },
        )
        .unwrap();
        let before = firewall.identity();
        let ack = firewall.handle(sanitize_command()).await;
        assert_eq!(status(&ack), proxy_firewall_ack::Status::IoError);
        assert_eq!(before.epoch, firewall.identity().epoch);
        assert_eq!(firewall.kernel.calls, vec!["sanitize"]);
    }

    #[tokio::test]
    async fn sanitation_rejects_non_deny_containment_without_kernel_change() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let ack = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Sanitize(SanitizeRequest {
                    reason: "bad".to_owned(),
                    containment_config: Some(config(1, false)),
                })),
            })
            .await;
        assert_eq!(status(&ack), proxy_firewall_ack::Status::Invalid);
        assert!(firewall.kernel.calls.is_empty());
        assert_eq!(firewall.identity().epoch, 0);
    }

    #[tokio::test]
    async fn sanitize_then_start_issues_authoritative_handle() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let sanitize = firewall.handle(sanitize_command()).await;
        assert_eq!(status(&sanitize), proxy_firewall_ack::Status::Ok);
        let identity = sanitize.identity.unwrap();
        assert_eq!(identity.epoch, 1);

        let start = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(config(2, true)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&start), proxy_firewall_ack::Status::Ok);
        assert_ne!(start.handle_id, 0);
        assert_eq!(start.identity.unwrap(), identity);
        assert_eq!(firewall.kernel.calls, vec!["sanitize", "start"]);
    }

    #[tokio::test]
    async fn replacement_generation_must_advance() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        let start = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(config(1, true)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        let handle = start.handle_id;
        let duplicate = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Replace(ReplaceRequest {
                    handle_id: handle,
                    config: Some(config(1, false)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&duplicate), proxy_firewall_ack::Status::Invalid);
        assert_eq!(firewall.kernel.calls, vec!["start"]);

        let newer = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Replace(ReplaceRequest {
                    handle_id: handle,
                    config: Some(config(2, false)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&newer), proxy_firewall_ack::Status::Ok);
        assert_eq!(firewall.kernel.calls, vec!["start", "replace"]);
    }

    #[tokio::test]
    async fn start_rejects_clients_in_deny_first_config_without_kernel_change() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        let mut invalid = config(1, false);
        invalid.deny_all_ipv4 = true;
        invalid.deny_all_ipv6 = true;
        let ack = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(invalid),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&ack), proxy_firewall_ack::Status::Invalid);
        assert!(firewall.kernel.calls.is_empty());
    }

    #[tokio::test]
    async fn second_start_is_rejected_without_second_kernel_mutation() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        for expected in [proxy_firewall_ack::Status::Ok, proxy_firewall_ack::Status::Invalid] {
            let ack = firewall
                .handle(ProxyFirewallCommand {
                    kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                        config: Some(config(1, true)),
                        expected_session_id: identity.session_id,
                        expected_epoch: identity.epoch,
                    })),
                })
                .await;
            assert_eq!(status(&ack), expected);
        }
        assert_eq!(firewall.kernel.calls, vec!["start"]);
    }

    #[tokio::test]
    async fn deny_clears_clients_and_sets_both_deny_flags() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        let start = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(config(1, true)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        let handle = start.handle_id;
        let replace = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Replace(ReplaceRequest {
                    handle_id: handle,
                    config: Some(config(2, false)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&replace), proxy_firewall_ack::Status::Ok);
        let deny = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Deny(DenyRequest {
                    handle_id: handle,
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&deny), proxy_firewall_ack::Status::Ok);
        assert_eq!(firewall.kernel.calls, vec!["start", "replace", "deny"]);
    }

    #[tokio::test]
    async fn stop_removes_handle_and_second_stop_is_rejected() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        let start = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(config(1, true)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        let request = StopRequest {
            handle_id: start.handle_id,
            expected_session_id: identity.session_id,
            expected_epoch: identity.epoch,
        };
        let first = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Stop(request.clone())),
            })
            .await;
        let second = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Stop(request)),
            })
            .await;
        assert_eq!(status(&first), proxy_firewall_ack::Status::Ok);
        assert_eq!(status(&second), proxy_firewall_ack::Status::Invalid);
        assert_eq!(firewall.kernel.calls, vec!["start", "stop"]);
    }

    #[tokio::test]
    async fn failed_external_clean_preserves_epoch_and_runtime() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        let start = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(config(1, true)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        let before = firewall.identity();
        let error = firewall
            .record_external_clean(|| async { Err(io::Error::other("routing clean failed")) })
            .await
            .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::Other);
        assert_eq!(firewall.identity(), before);
        let stop = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Stop(StopRequest {
                    handle_id: start.handle_id,
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&stop), proxy_firewall_ack::Status::Ok);
        assert_eq!(firewall.kernel.calls, vec!["start", "stop"]);
    }

    #[tokio::test]
    async fn successful_external_clean_advances_epoch_and_clears_runtime() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        let start = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(config(1, true)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        firewall.record_external_clean(|| async { Ok(()) }).await.unwrap();
        assert_eq!(firewall.identity().epoch, identity.epoch + 1);
        let stale = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Stop(StopRequest {
                    handle_id: start.handle_id,
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&stale), proxy_firewall_ack::Status::StaleEpoch);
        assert_eq!(firewall.kernel.calls, vec!["start"]);
    }

    #[tokio::test]
    async fn start_rejects_clients_in_deny_first_config_without_kernel_change() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        let mut invalid = config(1, false);
        invalid.deny_all_ipv4 = true;
        invalid.deny_all_ipv6 = true;
        let ack = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(invalid),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&ack), proxy_firewall_ack::Status::Invalid);
        assert!(firewall.kernel.calls.is_empty());
    }

    #[tokio::test]
    async fn second_start_is_rejected_without_second_kernel_mutation() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        for expected in [proxy_firewall_ack::Status::Ok, proxy_firewall_ack::Status::Invalid] {
            let ack = firewall
                .handle(ProxyFirewallCommand {
                    kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                        config: Some(config(1, true)),
                        expected_session_id: identity.session_id,
                        expected_epoch: identity.epoch,
                    })),
                })
                .await;
            assert_eq!(status(&ack), expected);
        }
        assert_eq!(firewall.kernel.calls, vec!["start"]);
    }

    #[tokio::test]
    async fn deny_clears_clients_and_sets_both_deny_flags() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        let start = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(config(1, true)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        let handle = start.handle_id;
        let replace = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Replace(ReplaceRequest {
                    handle_id: handle,
                    config: Some(config(2, false)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&replace), proxy_firewall_ack::Status::Ok);
        let deny = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Deny(DenyRequest {
                    handle_id: handle,
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&deny), proxy_firewall_ack::Status::Ok);
        assert_eq!(firewall.kernel.calls, vec!["start", "replace", "deny"]);
    }

    #[tokio::test]
    async fn stop_removes_handle_and_second_stop_is_rejected() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        let start = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(config(1, true)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        let request = StopRequest {
            handle_id: start.handle_id,
            expected_session_id: identity.session_id,
            expected_epoch: identity.epoch,
        };
        let first = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Stop(request.clone())),
            })
            .await;
        let second = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Stop(request)),
            })
            .await;
        assert_eq!(status(&first), proxy_firewall_ack::Status::Ok);
        assert_eq!(status(&second), proxy_firewall_ack::Status::Invalid);
        assert_eq!(firewall.kernel.calls, vec!["start", "stop"]);
    }

    #[tokio::test]
    async fn failed_external_clean_preserves_epoch_and_runtime() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        let start = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(config(1, true)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        let before = firewall.identity();
        let error = firewall
            .record_external_clean(|| async { Err(io::Error::other("routing clean failed")) })
            .await
            .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::Other);
        assert_eq!(firewall.identity(), before);
        let stop = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Stop(StopRequest {
                    handle_id: start.handle_id,
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&stop), proxy_firewall_ack::Status::Ok);
        assert_eq!(firewall.kernel.calls, vec!["start", "stop"]);
    }

    #[tokio::test]
    async fn successful_external_clean_advances_epoch_and_clears_runtime() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        let start = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(config(1, true)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        firewall.record_external_clean(|| async { Ok(()) }).await.unwrap();
        assert_eq!(firewall.identity().epoch, identity.epoch + 1);
        let stale = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Stop(StopRequest {
                    handle_id: start.handle_id,
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&stale), proxy_firewall_ack::Status::StaleEpoch);
        assert_eq!(firewall.kernel.calls, vec!["start"]);
    }

    #[tokio::test]
    async fn start_rejects_clients_in_deny_first_config_without_kernel_change() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        let mut invalid = config(1, false);
        invalid.deny_all_ipv4 = true;
        invalid.deny_all_ipv6 = true;
        let ack = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(invalid),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&ack), proxy_firewall_ack::Status::Invalid);
        assert!(firewall.kernel.calls.is_empty());
    }

    #[tokio::test]
    async fn second_start_is_rejected_without_second_kernel_mutation() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        for expected in [proxy_firewall_ack::Status::Ok, proxy_firewall_ack::Status::Invalid] {
            let ack = firewall
                .handle(ProxyFirewallCommand {
                    kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                        config: Some(config(1, true)),
                        expected_session_id: identity.session_id,
                        expected_epoch: identity.epoch,
                    })),
                })
                .await;
            assert_eq!(status(&ack), expected);
        }
        assert_eq!(firewall.kernel.calls, vec!["start"]);
    }

    #[tokio::test]
    async fn deny_clears_clients_and_sets_both_deny_flags() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        let start = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(config(1, true)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        let handle = start.handle_id;
        let replace = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Replace(ReplaceRequest {
                    handle_id: handle,
                    config: Some(config(2, false)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&replace), proxy_firewall_ack::Status::Ok);
        let deny = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Deny(DenyRequest {
                    handle_id: handle,
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&deny), proxy_firewall_ack::Status::Ok);
        assert_eq!(firewall.kernel.calls, vec!["start", "replace", "deny"]);
    }

    #[tokio::test]
    async fn stop_removes_handle_and_second_stop_is_rejected() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        let start = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(config(1, true)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        let request = StopRequest {
            handle_id: start.handle_id,
            expected_session_id: identity.session_id,
            expected_epoch: identity.epoch,
        };
        let first = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Stop(request.clone())),
            })
            .await;
        let second = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Stop(request)),
            })
            .await;
        assert_eq!(status(&first), proxy_firewall_ack::Status::Ok);
        assert_eq!(status(&second), proxy_firewall_ack::Status::Invalid);
        assert_eq!(firewall.kernel.calls, vec!["start", "stop"]);
    }

    #[tokio::test]
    async fn failed_external_clean_preserves_epoch_and_runtime() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        let start = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(config(1, true)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        let before = firewall.identity();
        let error = firewall
            .record_external_clean(|| async { Err(io::Error::other("routing clean failed")) })
            .await
            .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::Other);
        assert_eq!(firewall.identity(), before);
        let stop = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Stop(StopRequest {
                    handle_id: start.handle_id,
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&stop), proxy_firewall_ack::Status::Ok);
        assert_eq!(firewall.kernel.calls, vec!["start", "stop"]);
    }

    #[tokio::test]
    async fn successful_external_clean_advances_epoch_and_clears_runtime() {
        let store = MemoryStore::default();
        let mut firewall = ProxyFirewall::boot(&store, SpyKernel::default()).unwrap();
        let identity = firewall.identity();
        let start = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Start(StartRequest {
                    config: Some(config(1, true)),
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        firewall.record_external_clean(|| async { Ok(()) }).await.unwrap();
        assert_eq!(firewall.identity().epoch, identity.epoch + 1);
        let stale = firewall
            .handle(ProxyFirewallCommand {
                kind: Some(proxy_firewall_command::Kind::Stop(StopRequest {
                    handle_id: start.handle_id,
                    expected_session_id: identity.session_id,
                    expected_epoch: identity.epoch,
                })),
            })
            .await;
        assert_eq!(status(&stale), proxy_firewall_ack::Status::StaleEpoch);
        assert_eq!(firewall.kernel.calls, vec!["start"]);
    }

    #[test]
    fn session_id_never_repeats_for_one_store() {
        let store = MemoryStore::default();
        let first = ProxySession::boot(&store).unwrap().identity().session_id;
        let second = ProxySession::boot(&store).unwrap().identity().session_id;
        assert!(second > first);
    }
}
