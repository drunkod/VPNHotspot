use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use crate::shared::proto::proxy::DaemonIdentity;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Validation {
    Current,
    StaleSession,
    StaleEpoch,
}

pub trait SessionStore: Send + Sync {
    fn next_session_id(&self) -> io::Result<u64>;
}

#[derive(Clone, Debug)]
pub struct FileSessionStore {
    path: PathBuf,
}

impl FileSessionStore {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }
}

impl SessionStore for FileSessionStore {
    fn next_session_id(&self) -> io::Result<u64> {
        if let Some(parent) = self.path.parent().filter(|path| !path.as_os_str().is_empty()) {
            fs::create_dir_all(parent)?;
        }

        let current = match fs::read_to_string(&self.path) {
            Ok(value) => value.trim().parse::<u64>().map_err(|error| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("invalid proxy firewall session counter: {error}"),
                )
            })?,
            Err(error) if error.kind() == io::ErrorKind::NotFound => 0,
            Err(error) => return Err(error),
        };
        let next = current.checked_add(1).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidData, "proxy firewall session counter overflow")
        })?;

        let temporary = self
            .path
            .with_extension(format!("tmp-{}", std::process::id()));
        let mut file = OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(&temporary)?;
        writeln!(file, "{next}")?;
        file.sync_all()?;
        fs::rename(&temporary, &self.path)?;

        if let Some(parent) = self.path.parent().filter(|path| !path.as_os_str().is_empty()) {
            OpenOptions::new().read(true).open(parent)?.sync_all()?;
        }
        Ok(next)
    }
}

pub struct ProxySession {
    session_id: u64,
    epoch: AtomicU64,
}

impl ProxySession {
    pub fn boot(store: &dyn SessionStore) -> io::Result<Self> {
        let session_id = store.next_session_id()?;
        if session_id == 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "proxy firewall session ID must be non-zero",
            ));
        }
        Ok(Self {
            session_id,
            epoch: AtomicU64::new(0),
        })
    }

    pub fn identity(&self) -> DaemonIdentity {
        DaemonIdentity {
            session_id: self.session_id,
            epoch: self.epoch.load(Ordering::SeqCst),
            // The boot/session counter is also the transport generation for Track B.
            generation: self.session_id,
        }
    }

    pub fn bump_epoch(&self) -> io::Result<u64> {
        let current = self.epoch.load(Ordering::SeqCst);
        let next = current.checked_add(1).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidData, "proxy firewall epoch overflow")
        })?;
        self.epoch.store(next, Ordering::SeqCst);
        Ok(next)
    }

    pub fn validate(&self, expected_session: u64, expected_epoch: u64) -> Validation {
        if expected_session != self.session_id {
            return Validation::StaleSession;
        }
        if expected_epoch != self.epoch.load(Ordering::SeqCst) {
            return Validation::StaleEpoch;
        }
        Validation::Current
    }
}

#[cfg(test)]
mod tests {
    use std::time::{SystemTime, UNIX_EPOCH};

    use super::*;

    #[test]
    fn file_store_persists_strictly_increasing_session_ids() {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("clock after Unix epoch")
            .as_nanos();
        let directory = std::env::temp_dir().join(format!(
            "vpnhotspotd-proxy-session-{}-{unique}",
            std::process::id(),
        ));
        let path = directory.join("counter");

        let first = FileSessionStore::new(&path).next_session_id().unwrap();
        let second = FileSessionStore::new(&path).next_session_id().unwrap();
        let third = FileSessionStore::new(&path).next_session_id().unwrap();

        assert_eq!(first, 1);
        assert_eq!(second, 2);
        assert_eq!(third, 3);
        assert_eq!(fs::read_to_string(&path).unwrap().trim(), "3");

        fs::remove_dir_all(directory).unwrap();
    }
}
