use std::collections::BTreeMap;
use std::io;

use crate::shared::proto::proxy::ProxyFirewallConfig;

#[derive(Default)]
pub struct AppliedLedger {
    next_handle_id: u64,
    runtimes: BTreeMap<u64, ProxyFirewallConfig>,
}

impl AppliedLedger {
    pub fn is_empty(&self) -> bool {
        self.runtimes.is_empty()
    }

    pub fn allocate_handle(&mut self) -> io::Result<u64> {
        let next = self.next_handle_id.checked_add(1).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidData, "proxy firewall handle ID overflow")
        })?;
        self.next_handle_id = next;
        Ok(next)
    }

    pub fn insert(&mut self, handle_id: u64, config: ProxyFirewallConfig) {
        self.runtimes.insert(handle_id, config);
    }

    pub fn get(&self, handle_id: u64) -> Option<&ProxyFirewallConfig> {
        self.runtimes.get(&handle_id)
    }

    pub fn replace(&mut self, handle_id: u64, config: ProxyFirewallConfig) {
        self.runtimes.insert(handle_id, config);
    }

    pub fn remove(&mut self, handle_id: u64) -> Option<ProxyFirewallConfig> {
        self.runtimes.remove(&handle_id)
    }

    pub fn clear(&mut self) {
        self.runtimes.clear();
    }
}
