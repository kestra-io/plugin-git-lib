package io.kestra.plugin.git.shared;

/**
 * Direction of synchronization between Git and Kestra, shared by every task/property that lets the user
 * pick which side is authoritative (task-level {@code sourceOfTruth}, per-kind {@link SourceOfTruthOverrides}).
 */
public enum SourceOfTruth {
    GIT,
    KESTRA
}
