# plugin-git-lib

## What

- Shared kernel library consumed by `plugin-git` (OSS) and `plugin-ee-git` (Enterprise Edition).
- Provides classes under `io.kestra.plugin.git.shared`: `AbstractGitTask`, `AbstractKestraTask`, `AbstractCloningTask`,
  `AbstractSyncTask`, `AbstractPushTask`, `KestraApiConnection`, `KestraApiAuth`, and the
  `io.kestra.plugin.git.shared.services` package (`GitService`, `CloneService`, `SshTransportConfigCallback`).
- Ships a `testFixtures` source set (`io.kestra.plugin.git.shared.testkit`) with the Gitea/Kestra-container test
  scaffolding and the in-process mock Kestra API server reused by both plugins' test suites.

## Why

Kestra ships a Git task family in two editions: `plugin-git` (OSS) and `plugin-ee-git` (Enterprise, which adds
apps/blueprints/dashboards/unit-tests sync on top of the same flows/files sync). Both editions cloned, authenticated
and pushed to Git the same way, but as two independently maintained forks — connection handling, SSH host-key
verification and the Kestra API auth layer drifted apart release after release. This library is the single source of
truth for that shared plumbing so a fix or a security hardening (e.g. SSH host-key checking) lands once and both
editions pick it up on their next lib bump.

## How

### Architecture

Single-module library, `io.kestra.gradle.repository-conventions` + `com.vanniktech.maven.publish`, published as
`io.kestra.plugin:plugin-git-lib`. Following the precedent set by `plugin-azure-lib` and `plugin-kubernetes-lib`:

- **No registered `@Plugin` task lives here.** Only abstract task bases, interfaces, models and services. Every
  concrete, user-facing task (`Clone`, `Push*`, `Sync*`, `NamespaceSync`, `TenantSync`, and the EE-only
  `PushApps`/`SyncBlueprints`/…) stays in its own plugin repository so plugin registration, doc generation and the
  `internal = true` markers stay where they are today.
- `compileOnly` on `io.kestra:core` / `io.kestra:script`; this library never depends on `io.kestra.ee:core-ee`, since
  it is consumed by the OSS plugin too.
- Owns the `api` jgit dependencies (`org.eclipse.jgit`, `org.eclipse.jgit.ssh.jsch`, `org.eclipse.jgit.http.apache`)
  and `io.kestra:kestra-api-client`, so both editions stop drifting on those versions — bump them here once.
- Both plugin repos consume it as `api 'io.kestra.plugin:plugin-git-lib:<version>'` and continue shipping it inside
  their own `shadowJar`.

### CloneService: breaking the Clone-task coupling

`GitService.cloneBranch()` needs to perform a plain clone/checkout before a sync or push begins. It used to do this
by building a concrete `Clone` task instance and calling `.run()` on it — which does not work here since `Clone`
intentionally stays in each plugin repository. `services.CloneService` extracts that clone/checkout/branch-create
logic into a plain, `RunnableTask`-free helper parameterized by URL/credentials/branch/depth/submodules. Both
`GitService.cloneBranch()` and each repository's own `Clone.run()` delegate to it, so the clone logic exists in
exactly one place.

### Minimum core version

This library compiles against `kestraVersion` set in `gradle.properties` (currently `2.0.0`). A consumer plugin
running against an older Kestra core than that may see `NoSuchMethodError` at runtime — keep the consuming plugin's
`kestraVersion` at or above this library's.

### Release order

Bump and publish this library first, then update `plugin-git` and `plugin-ee-git` to depend on the new version. A
plugin release referencing an unpublished lib version fails to resolve.

## Local rules

- Do not add a registered task or trigger to this repository — that breaks the shared-kernel invariant that both
  `plugin-git` and `plugin-ee-git` rely on (see `plugin-azure-lib` / `plugin-kubernetes-lib` for the same rule).
- `NamespaceSync` and `TenantSync` (and their EE app/blueprint/dashboard/unit-test counterparts) are **not** unified
  here — that is a separate, larger effort (pluggable resource-kind design) tracked outside Phase 1 of the
  shared-kernel extraction.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
