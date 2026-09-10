package io.kestra.plugin.git.shared;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.runners.RunContext;

import lombok.Builder;
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

    /**
     * An Enterprise-Edition-style cloning task: identical to {@link TestCloningTask} but opting out of installing the
     * JVM-global HTTP connection factory when nothing is configured (the EE seam behavior), so the shared
     * {@code alwaysConfigureHttpTransport()} hook can be exercised from the lib's own tests.
     */
    @SuperBuilder
    @ToString
    @EqualsAndHashCode(callSuper = true)
    @Getter
    @NoArgsConstructor
    public static class TestEeCloningTask extends AbstractCloningTask implements RunnableTask<VoidOutput> {
        @Override
        public Property<String> getBranch() {
            return Property.ofValue("main");
        }

        @Override
        protected boolean alwaysConfigureHttpTransport() {
            return false;
        }

        @Override
        public VoidOutput run(RunContext runContext) {
            return null;
        }
    }

    /**
     * Minimal concrete {@code AbstractPushTask}, backed by an in-memory {@code filesToWrite} map instead of a real
     * instance resource fetch, used to unit-test the shared push flow (deletion staging, commit/push, and the
     * per-edition hooks) without a registered task of its own (see AGENTS.md).
     */
    @SuperBuilder(toBuilder = true)
    @ToString
    @EqualsAndHashCode(callSuper = true)
    @Getter
    @NoArgsConstructor
    public static class TestPushTask extends AbstractPushTask<AbstractPushTask.Output> {
        @Builder.Default
        private Property<String> gitDirectory = Property.ofValue(".");

        @Builder.Default
        private Map<String, String> filesToWrite = Map.of();

        @Override
        public Property<String> getBranch() {
            return Property.ofValue("main");
        }

        @Override
        public Property<String> getCommitMessage() {
            return Property.ofValue("test commit");
        }

        @Override
        public Property<String> getGitDirectory() {
            return gitDirectory;
        }

        @Override
        public Object globs() {
            return null;
        }

        @Override
        public Property<String> fetchedNamespace() {
            return Property.ofValue(null);
        }

        @Override
        protected Map<Path, Supplier<InputStream>> instanceResourcesContentByPath(RunContext runContext, Path baseDirectory, List<String> globs) {
            Map<Path, Supplier<InputStream>> result = new LinkedHashMap<>();
            filesToWrite.forEach((relativePath, content) ->
                result.put(baseDirectory.resolve(relativePath), () -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
            return result;
        }

        @Override
        protected AbstractPushTask.Output output(AbstractPushTask.Output pushOutput, URI diffFileStorageUri) {
            return pushOutput;
        }
    }

    /**
     * Minimal concrete {@code AbstractSyncTask}, with every resource-fetching/writing hook a no-op, used to
     * unit-test {@code gitResourcesContentByUri} (symlink-following and git-internal-path exclusion) without a
     * registered task of its own (see AGENTS.md).
     */
    @SuperBuilder(toBuilder = true)
    @ToString
    @EqualsAndHashCode(callSuper = true)
    @Getter
    @NoArgsConstructor
    public static class TestSyncTask extends AbstractSyncTask<String, TestSyncTask.Output> {
        @Builder.Default
        private Property<String> gitDirectory = Property.ofValue(".");

        @Override
        public Property<String> getBranch() {
            return Property.ofValue("main");
        }

        @Override
        public Property<Boolean> getDelete() {
            return Property.ofValue(false);
        }

        @Override
        public Property<String> getGitDirectory() {
            return gitDirectory;
        }

        @Override
        public Property<String> fetchedNamespace() {
            return Property.ofValue(null);
        }

        @Override
        protected void deleteResource(RunContext runContext, String renderedNamespace, String instanceResource) {
        }

        @Override
        protected String simulateResourceWrite(RunContext runContext, String renderedNamespace, URI uri, InputStream inputStream) {
            return uri.toString();
        }

        @Override
        protected String writeResource(RunContext runContext, String renderedNamespace, URI uri, InputStream inputStream) {
            return uri.toString();
        }

        @Override
        protected SyncResult wrapper(RunContext runContext, String renderedGitDirectory, String renderedNamespace, URI resourceUri, String resourceBeforeUpdate, String resourceAfterUpdate) {
            return null;
        }

        @Override
        protected List<String> fetchResources(RunContext runContext, String renderedNamespace) {
            return List.of();
        }

        @Override
        protected URI toUri(String renderedNamespace, String resource) {
            return URI.create(resource);
        }

        @Override
        protected Output output(URI diffFileStorageUri) {
            return Output.builder().diffFileUri(diffFileStorageUri).build();
        }

        @SuperBuilder
        @Getter
        public static class Output extends AbstractSyncTask.Output {
            private URI diffFileUri;

            @Override
            public URI diffFileUri() {
                return diffFileUri;
            }
        }
    }
}
