package io.kestra.plugin.git.shared;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
    public static class TestPushTask extends AbstractPushTask<TestPushTask.Output> {
        @Builder.Default
        private Property<String> gitDirectory = Property.ofValue(".");

        @Builder.Default
        private Map<String, String> filesToWrite = Map.of();

        /**
         * Globs restricting which resources are considered current on the instance, mirroring a concrete task's
         * {@code flows}/{@code namespaceFiles}-style filter — needed to exercise {@code deleteOutdatedResources}'
         * glob-matching (path, filename, and stem) from the shared kernel's own tests.
         */
        private List<String> globs;

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
            return globs;
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
        protected Output output(AbstractPushTask.Output pushOutput, URI diffFileStorageUri) {
            return Output.builder()
                .commitId(pushOutput.getCommitId())
                .commitURL(pushOutput.getCommitURL())
                .diffFileStorageUri(diffFileStorageUri)
                .build();
        }

        @SuperBuilder
        @Getter
        public static class Output extends AbstractPushTask.Output {
            private URI diffFileStorageUri;

            @Override
            public URI diffFileUri() {
                return diffFileStorageUri;
            }
        }
    }

    /**
     * An Enterprise-Edition-style push task: identical to {@link TestPushTask} but staging the whole git directory
     * in one {@code git add} call instead of per resolved file (the EE seam behavior), so {@code DELETE_ONLY}'s
     * {@code AddCommand}-skip can be verified against that code path too.
     */
    @SuperBuilder(toBuilder = true)
    @ToString
    @EqualsAndHashCode(callSuper = true)
    @Getter
    @NoArgsConstructor
    public static class TestEeStylePushTask extends TestPushTask {
        @Override
        protected boolean stageWholeGitDirectory() {
            return true;
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

        @Builder.Default
        private Property<String> branch = Property.ofValue("main");

        @Builder.Default
        private Property<Boolean> delete = Property.ofValue(false);

        /** Pre-existing instance resources fetched before the sync; each entry must be a valid resource URI string (see {@link #toUri}). */
        @Builder.Default
        private List<String> existingResources = List.of();

        /** Records every resource the sync actually deletes, so tests can assert nothing was deleted on failure. */
        @Builder.Default
        private List<String> deletedResources = new CopyOnWriteArrayList<>();

        @Override
        public Property<String> getBranch() {
            return branch;
        }

        @Override
        public Property<Boolean> getDelete() {
            return delete;
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
            deletedResources.add(instanceResource);
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
            return existingResources;
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
