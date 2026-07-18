use std::env;
use std::io;
use std::path::PathBuf;

use prost::Message;
use tokio::sync::Mutex;
use vpnhotspotd::proxy_firewall::{FileSessionStore, ProxyFirewall};
use vpnhotspotd::shared::proto::{daemon, proxy};

use crate::proxy_firewall_kernel::AndroidProxyFirewall;

pub(super) type State = ProxyFirewall<AndroidProxyFirewall>;

pub(super) fn boot() -> io::Result<State> {
    let state_dir = env::var_os("VPNHOTSPOTD_STATE_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/data/local/tmp/vpnhotspotd"));
    let store = FileSessionStore::new(state_dir.join("proxy-firewall-session"));
    ProxyFirewall::boot(&store, AndroidProxyFirewall)
}

pub(super) async fn handle(
    state: &Mutex<State>,
    command: proxy::ProxyFirewallCommand,
) -> proxy::ProxyFirewallAck {
    state.lock().await.handle(command).await
}

pub(super) fn reply_frame(id: u64, ack: proxy::ProxyFirewallAck) -> Vec<u8> {
    daemon::DaemonEnvelope {
        frame: Some(daemon::daemon_envelope::Frame::Reply(daemon::ReplyFrame {
            call_id: id,
            payload: Some(daemon::reply_frame::Payload::ProxyFirewall(ack)),
        })),
    }
    .encode_to_vec()
}
