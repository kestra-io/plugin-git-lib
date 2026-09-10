package io.kestra.plugin.git.shared;

/**
 * Distinguishes the two {@code kestraClient()} call sites sharing {@link KestraApiConnection#buildClient}, since
 * they intentionally differ in two ways that predate the shared-kernel extraction and must not drift towards each
 * other: which credential wins when the default SDK authentication carries both a token and Basic credentials, and
 * how strictly the explicit {@code auth.apiToken} / {@code auth.username}+{@code auth.password} mutual exclusion is
 * enforced.
 */
enum KestraTaskFamily {
    /**
     * {@code AbstractCloningTask} ({@code Clone}/{@code Push*}/{@code Sync*}/{@code NamespaceSync}): default SDK
     * auth prefers Basic over token; the explicit mutual-exclusion check is based on the rendered value.
     */
    CLONING,
    /**
     * {@code AbstractKestraTask} ({@code SyncFlow}/{@code TenantSync}): default SDK auth prefers token over Basic;
     * the explicit mutual-exclusion check is based on field nullness, so a declared-but-blank {@code apiToken}
     * alongside username/password still errors instead of falling through to Basic.
     */
    KESTRA_API
}
