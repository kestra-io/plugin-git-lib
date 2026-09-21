package io.kestra.plugin.git.shared;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Per-resource-kind override of the task-level {@code sourceOfTruth}, letting a single sync task push one
 * kind of resource to Git while pulling another kind from Git in the same run. Non-final so the Enterprise
 * Edition can extend it with its own resource kinds (dashboards, apps, tests, blueprints).
 */
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@Getter
@ToString
@EqualsAndHashCode
public class SourceOfTruthOverrides {

    @Schema(
        title = "Source of truth for flows",
        description = "Overrides the task-level `sourceOfTruth` for flows only. Falls back to `sourceOfTruth` when unset."
    )
    @Nullable
    @PluginProperty(group = "source")
    private Property<SourceOfTruth> flows;

    @Schema(
        title = "Source of truth for Namespace Files",
        description = "Overrides the task-level `sourceOfTruth` for Namespace Files only. Falls back to `sourceOfTruth` when unset."
    )
    @Nullable
    @PluginProperty(group = "source")
    private Property<SourceOfTruth> namespaceFiles;

    /**
     * Resolves {@code override ?? fallback}, rendering {@code override} only when present.
     */
    public static SourceOfTruth resolve(
        RunContext runContext,
        @Nullable Property<SourceOfTruth> override,
        SourceOfTruth fallback) throws IllegalVariableEvaluationException {
        if (override == null) {
            return fallback;
        }
        return runContext.render(override).as(SourceOfTruth.class).orElse(fallback);
    }
}
