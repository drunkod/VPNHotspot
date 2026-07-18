use std::error::Error;

fn main() -> Result<(), Box<dyn Error>> {
    let proto_dir = "../../proto";
    let daemon_proto = format!("{proto_dir}/daemon.proto");
    let proxy_firewall_proto = format!("{proto_dir}/proxy_firewall.proto");
    println!("cargo:rerun-if-changed={daemon_proto}");
    println!("cargo:rerun-if-changed={proxy_firewall_proto}");

    prost_build::Config::new()
        .protoc_executable(protoc_bin_vendored::protoc_bin_path()?)
        .compile_protos(&[daemon_proto, proxy_firewall_proto], &[proto_dir])?;
    Ok(())
}
