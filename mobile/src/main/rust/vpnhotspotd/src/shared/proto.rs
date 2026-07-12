pub mod proxy {
    include!(concat!(env!("OUT_DIR"), "/vpnhotspot.proxy.rs"));
}

pub mod daemon {
    include!(concat!(env!("OUT_DIR"), "/vpnhotspot.daemon.rs"));
}
