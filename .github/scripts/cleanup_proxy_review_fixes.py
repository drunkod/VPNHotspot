from pathlib import Path


def collapse_blocks(path: str, start: str, end: str) -> None:
    target = Path(path)
    text = target.read_text()
    count = text.count(start)
    if count == 0:
        raise SystemExit(f"{path}: required block not found: {start.splitlines()[-1]}")
    while text.count(start) > 1:
        first = text.find(start)
        second = text.find(start, first + len(start))
        boundary = text.find(end, second)
        if boundary < 0:
            raise SystemExit(f"{path}: duplicate block end not found")
        text = text[:second] + text[boundary:]
    target.write_text(text)
    print(f"UNIQUE {path}: {start.splitlines()[-1]} (collapsed {count} to 1)")


def collapse_literal(path: str, literal: str) -> None:
    target = Path(path)
    text = target.read_text()
    count = text.count(literal)
    if count == 0:
        raise SystemExit(f"{path}: required literal not found: {literal[:80]!r}")
    first = text.find(literal)
    before = text[: first + len(literal)]
    after = text[first + len(literal) :].replace(literal, "")
    target.write_text(before + after)
    print(f"UNIQUE {path}: literal collapsed {count} to 1")


rust_mod = "mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/mod.rs"
deny_check = '''    if require_deny_first && !config.allowed_clients.is_empty() {
        return Err("deny-first configuration must not contain allowed clients".to_owned());
    }
'''
collapse_literal(rust_mod, deny_check)
collapse_blocks(
    rust_mod,
    '''    #[tokio::test]
    async fn start_rejects_clients_in_deny_first_config_without_kernel_change() {
''',
    '''    #[test]
    fn session_id_never_repeats_for_one_store() {
''',
)

session = "mobile/src/main/rust/vpnhotspotd/src/proxy_firewall/session.rs"
collapse_literal(session, '    use std::process::{Command, Stdio};\n')
collapse_blocks(
    session,
    '''    #[test]
    fn file_store_removes_stale_temporary_files_under_lock() {
''',
    '''    #[test]
    fn file_store_serializes_concurrent_boots() {
''',
)

matrix = "mobile/src/test/java/be/mygod/vpnhotspot/proxy/CleanupGenerationMatrixTest.kt"
collapse_blocks(
    matrix,
    '''    @Test
    fun primarySanitation_ackGenerationMismatchRetainsDaemonCleanDebt() = runBlocking {
''',
    '''    private fun firewallDebt(handle: ProxyFirewallHandle) = CleanupDebt(
''',
)

checks = {
    rust_mod: [
        "deny-first configuration must not contain allowed clients",
        "async fn start_rejects_clients_in_deny_first_config_without_kernel_change()",
        "async fn second_start_is_rejected_without_second_kernel_mutation()",
        "async fn deny_clears_clients_and_sets_both_deny_flags()",
        "async fn stop_removes_handle_and_second_stop_is_rejected()",
        "async fn failed_external_clean_preserves_epoch_and_runtime()",
        "async fn successful_external_clean_advances_epoch_and_clears_runtime()",
    ],
    session: [
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
