package io.kestra.plugin.git.shared;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.runners.RunContext;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Minimal concrete subclasses of the abstract task hierarchy, used only to unit-test the shared abstracts —
 * {@code AbstractKestraTask} and {@code AbstractCloningTask} have no registered task of their own in this
 * repository (see AGENTS.md).
 */
public final class TestTasks {

    private TestTasks() {
    }

    @SuperBuilder
    @ToString
    @EqualsAndHashCode(callSuper = true)
    @Getter
    @NoArgsConstructor
    public static class TestKestraTask extends AbstractKestraTask implements RunnableTask<VoidOutput> {
        @Override
        public Property<String> getBranch() {
            return Property.ofValue("main");
        }

        @Override
        public VoidOutput run(RunContext runContext) {
            return null;
        }
    }

    @SuperBuilder
    @ToString
    @EqualsAndHashCode(callSuper = true)
    @Getter
    @NoArgsConstructor
    public static class TestCloningTask extends AbstractCloningTask implements RunnableTask<VoidOutput> {
        @Override
        public Property<String> getBranch() {
            return Property.ofValue("main");
        }

        @Override
        public VoidOutput run(RunContext runContext) {
            return null;
        }
    }
}
