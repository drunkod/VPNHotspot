from pathlib import Path


def remove_second_block(path: str, start: str, end: str) -> None:
    target = Path(path)
    text = target.read_text()
    positions = []
    offset = 0
    while True:
        position = text.find(start, offset)
        if position < 0:
            break
        positions.append(position)
        offset = position + len(start)
    if len(positions) == 1:
        print(f"SINGLE {path}: {start.splitlines()[-1]}")
        return
    if len(positions) != 2:
        raise SystemExit(f"{path}: expected one or two starts, found {len(positions)}")
    second = positions[1]
    boundary = text.find(end, second)
    if boundary < 0:
        raise SystemExit(f"{path}: duplicate block end not found")
    target.write_text(text[:second] + text[boundary:])
    print(f"DEDUPED {path}: {start.splitlines()[-1]}")


rust_mod = Path("mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/mod.rs")
text = rust_mod.read_text()
deny_check = '''    if require_deny_first && !config.allowed_clients.is_empty() {
        return Err("deny-first configuration must not contain allowed clients".to_owned());
    }
'''
if text.count(deny_check) == 2:
    text = text.replace(deny_check + deny_check, deny_check, 1)
elif text.count(deny_check) != 1:
    raise SystemExit(f"unexpected deny-first check count: {text.count(deny_check)}")
rust_mod.write_text(text)
remove_second_block(
    str(rust_mod),
    '''    #[tokio::test]
    async fn start_rejects_clients_in_deny_first_config_without_kernel_change() {
''',
    '''    #[test]
    fn session_id_never_repeats_for_one_store() {
''',
)

session = Path("mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/session.rs")
text = session.read_text()
duplicate_import = '''    use std::process::{Command, Stdio};
    use std::process::{Command, Stdio};
'''
if duplicate_import in text:
    text = text.replace(duplicate_import, '    use std::process::{Command, Stdio};\n', 1)
session.write_text(text)
remove_second_block(
    str(session),
    '''    #[test]
    fn file_store_removes_stale_temporary_files_under_lock() {
''',
    '''    #[test]
    fn file_store_serializes_concurrent_boots() {
''',
)

matrix = "mobile/src/test/java/be/mygod/vpnhotspot/proxy/CleanupGenerationMatrixTest.kt"
remove_second_block(
    matrix,
    '''    @Test
    fun primarySanitation_ackGenerationMismatchRetainsDaemonCleanDebt() = runBlocking {
''',
    '''    private fun firewallDebt(handle: ProxyFirewallHandle) = CleanupDebt(
''',
)

checks = {
    str(rust_mod): [
        "deny-first configuration must not contain allowed clients",
        "async fn start_rejects_clients_in_deny_first_config_without_kernel_change()",
        "async fn second_start_is_rejected_without_second_kernel_mutation()",
        "async fn deny_clears_clients_and_sets_both_deny_flags()",
        "async fn stop_removes_handle_and_second_stop_is_rejected()",
        "async fn failed_external_clean_preserves_epoch_and_runtime()",
        "async fn successful_external_clean_advances_epoch_and_clears_runtime()",
    ],
    str(session): [
        "use std::process::{Command, Stdio};",
        "fn file_store_removes_stale_temporary_files_under_lock()",
        "fn file_store_process_helper()",
        "fn file_store_serializes_concurrent_processes()",
    ],
    matrix: [
        "fun primarySanitation_ackGenerationMismatchRetainsDaemonCleanDebt()",
        "fun cleanupDebtRetry_ackGenerationMismatchDoesNotCommitSanitation()",
    ],
}
for path, needles in checks.items():
    text = Path(path).read_text()
    for needle in needles:
        count = text.count(needle)
        if count != 1:
            raise SystemExit(f"{path}: expected one {needle!r}, found {count}")
print("review fix source invariants are unique")
