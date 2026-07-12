# Track F — Dependency Review CI fix

**Goal:** get the `Dependency Review` check green **by finding its real cause**, not by
weakening policy. Every review from R10 to R12 reports this check failing, with the
action log truncated before the final diagnostic. All three explicitly forbid lowering
`fail-on-severity` or adding `continue-on-error` without establishing the actual error.

**Blocker refs:** R10 §CI, R11 §CI, R12 §CI ("Do not weaken the dependency-review
severity policy or suppress the check without identifying its actual failure").

**Current workflow** (`.github/workflows/dependency-review.yml`):

```yaml
name: Dependency Review
on:
  pull_request:
permissions:
  contents: read
jobs:
  dependency-review:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/dependency-review-action@v5
        with:
          fail-on-severity: moderate
```

---

## Step F1 — get the actual diagnostic (do this first)

The reviews never captured the final error. Two most common failure modes for
`dependency-review-action` produce very different fixes, so identify which one applies:

```bash
# with gh CLI authenticated (see github-cli-cowork-setup skill)
gh run list --workflow "Dependency Review" --branch agent/proxy-only-design --limit 5
gh run view <run-id> --log-failed
```

Look for one of these two signatures at the end of the log:

- **A. Dependency graph not enabled / snapshot missing** —
  `"Dependency review is not supported on this repository"` or
  `"Unable to retrieve the dependency graph"` / HTTP 403 on the diff API. This is a
  **repository/permissions configuration** problem, not a real vulnerable dependency.
- **B. A dependency at/above `moderate`** — the log names a package + advisory
  (GHSA-…). This is a **real finding** and must be remediated, not suppressed.

Do not proceed to a fix until you know which signature you have.

---

## Step F2a — if cause A (configuration)

Most likely for this repo, because the check has failed consistently regardless of the
proxy-only source changes (a real vuln would track specific dependency edits).

1. **Enable the dependency graph** for the repo: Settings → Code security and analysis →
   Dependency graph → Enable. `dependency-review-action` reads the graph diff of the PR;
   with it disabled the action cannot retrieve a diff and fails.
2. **Confirm permissions.** The action needs `contents: read` (present). On private
   repos it may also need the graph API reachable; verify the run is not hitting a 403.
3. For Gradle projects the dependency graph is not auto-populated the way it is for
   npm/Maven-central manifests. If the graph diff is empty/unsupported, submit the
   dependency graph from the build so the action has data:

```yaml
# add BEFORE the dependency-review step
- name: Set up JDK
  uses: actions/setup-java@v4
  with:
    distribution: temurin
    java-version: '17'

- name: Submit Gradle dependency graph
  uses: gradle/actions/dependency-submission@v4
  # populates the dependency graph so dependency-review-action has a diff to read
```

> `gradle/actions/dependency-submission` requires `contents: write` to push the
> snapshot. Scope it narrowly — either raise the job permission or run submission in a
> separate `push`-triggered workflow and keep the PR review job read-only. Do NOT grant
> broad write to the review step itself.

A common clean split:

```yaml
permissions:
  contents: read
jobs:
  dependency-review:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/dependency-review-action@v5
        with:
          fail-on-severity: moderate
          comment-summary-in-pr: on-failure   # surface the real finding in the PR
```

…with a **separate** `dependency-submission.yml` on `push` to `main`/branch that has
`contents: write`, so the graph is populated out-of-band and the PR check stays
read-only. This keeps `fail-on-severity: moderate` intact.

---

## Step F2b — if cause B (a real finding)

1. Read the named advisory; find whether it is a direct or transitive dependency
   (`./gradlew :mobile:dependencies` / `dependencyInsight`).
2. Bump to a fixed version, or apply a constraint/resolution if transitive.
3. Re-run; the finding should clear. Only if the advisory is a proven false-positive for
   this usage should you add a scoped `allow-ghsas:` entry **with a written justification
   in the workflow comment** — never a blanket severity downgrade.

```yaml
      - uses: actions/dependency-review-action@v5
        with:
          fail-on-severity: moderate
          # Justified, specific, time-boxed — NOT a policy weakening:
          # GHSA-xxxx: transitive via <dep>, not reachable from proxy-only paths; tracked in #<issue>
          allow-ghsas: GHSA-xxxx-xxxx-xxxx
```

---

## Step F3 — verify

```bash
gh run list --workflow "Dependency Review" --branch agent/proxy-only-design --limit 1
# expect: completed / success
```

Confirm `fail-on-severity: moderate` is unchanged and no `continue-on-error` was added.

## Acceptance criteria

- The root cause is documented (A or B) with the captured log excerpt.
- If A: dependency graph populated; check passes with policy intact.
- If B: dependency remediated (preferred) or a specific, justified `allow-ghsas` entry.
- `fail-on-severity: moderate` preserved; no `continue-on-error`; no blanket suppression.
- Green `Dependency Review` on the PR.

## Notes

- Independent of all other tracks; smallest scope; unblocks a red PR check.
- Needs `gh` in the Cowork VM — see the `github-cli-cowork-setup` skill if not installed.
