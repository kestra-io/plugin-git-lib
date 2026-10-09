package io.kestra.plugin.git.shared;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.git.shared.TestTasks.TestSyncTask;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class AbstractSyncTaskTest {

    @Inject
    private RunContextFactory runContextFactory;

    private Path newRemoteWithDefaultBranchContent() throws Exception {
        Path remote = Files.createTempDirectory("sync-task-remote-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file.txt"), "hello\n");
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("init").call();
        }
        return remote;
    }

    /**
     * Reproduces kestra-io/plugin-git#343: syncing a `branch` that does not exist on the remote used to silently
     * fall back to the repository's default branch instead of failing, so with `delete` set to true, every
     * namespace resource that only existed on the requested branch was wiped out while the execution still
     * reported SUCCESS. The task must now fail loudly and must not delete anything.
     */
    @Test
    void run_failsAndDoesNotDeleteWhenBranchDoesNotExistOnRemote() throws Exception {
        Path remote = newRemoteWithDefaultBranchContent();
        RunContext runContext = runContextFactory.of();
        List<String> deletedResources = new CopyOnWriteArrayList<>();

        TestSyncTask task = TestSyncTask.builder()
            .url(Property.ofValue(remote.toUri().toString()))
            .branch(Property.ofValue("missing-branch"))
            .delete(Property.ofValue(true))
            .existingResources(List.of("/stale-resource.txt"))
            .deletedResources(deletedResources)
            .build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(deletedResources, empty());
    }

    /**
     * `failOnMissingBranch` is an explicit opt-out that preserves the pre-fix behavior (create the branch locally
     * from the default HEAD) for callers that rely on it.
     */
    @Test
    void run_fallsBackToDefaultBranchWhenFailOnMissingBranchIsFalse() throws Exception {
        Path remote = newRemoteWithDefaultBranchContent();
        RunContext runContext = runContextFactory.of();
        List<String> deletedResources = new CopyOnWriteArrayList<>();

        TestSyncTask task = TestSyncTask.builder()
            .url(Property.ofValue(remote.toUri().toString()))
            .branch(Property.ofValue("missing-branch"))
            .delete(Property.ofValue(true))
            .failOnMissingBranch(Property.ofValue(false))
            .existingResources(List.of("/stale-resource.txt"))
            .deletedResources(deletedResources)
            .build();

        task.run(runContext);

        assertThat(deletedResources, hasItem("/stale-resource.txt"));
    }

    /** The happy path — an existing branch — must keep working: the branch check must not be a false positive. */
    @Test
    void run_syncsNormallyWhenBranchExistsOnRemote() throws Exception {
        Path remote = newRemoteWithDefaultBranchContent();
        try (Git git = Git.open(remote.toFile())) {
            git.branchCreate().setName("feature").call();
        }
        RunContext runContext = runContextFactory.of();
        List<String> deletedResources = new CopyOnWriteArrayList<>();

        TestSyncTask task = TestSyncTask.builder()
            .url(Property.ofValue(remote.toUri().toString()))
            .branch(Property.ofValue("feature"))
            .delete(Property.ofValue(true))
            .existingResources(List.of("/file.txt"))
            .deletedResources(deletedResources)
            .build();

        task.run(runContext);

        assertThat(deletedResources, empty());
    }

    /**
     * These hooks default to OSS's unchanged behavior; an edition-specific base class overrides them instead of
     * every concrete EE sync task re-declaring the behavior (mirrors {@code defaultStrictHostKeyChecking}).
     */
    @Test
    void hooks_defaultToOssBehaviorUnlessOverridden() {
        var ossTask = TestSyncTask.builder().build();
        assertThat(ossTask.followSymlinks(), is(true));
        assertThat(ossTask.isGitInternalPath(Path.of(".github")), is(false));
        assertThat(ossTask.isGitInternalPath(Path.of(".git")), is(true));

        var eeStyleTask = new TestSyncTask() {
            @Override
            protected boolean followSymlinks() {
                return false;
            }

            @Override
            protected boolean isGitInternalPath(Path path) {
                return path.toString().contains(".git");
            }
        };
        assertThat(eeStyleTask.followSymlinks(), is(false));
        assertThat(eeStyleTask.isGitInternalPath(Path.of(".github")), is(true));
    }

    /** OSS has always excluded only paths ending with {@code .git}/{@code .gitignore}/{@code .gitkeep}. */
    @Test
    void gitResourcesContentByUri_ossDefault_onlyExcludesExactGitFileNames() throws Exception {
        Path baseDirectory = Files.createTempDirectory("sync-task-git-internal-");
        Files.createDirectories(baseDirectory.resolve(".github"));
        Files.writeString(baseDirectory.resolve(".github").resolve("workflow.yml"), "on: push\n");
        Files.writeString(baseDirectory.resolve(".gitignore"), "*.log\n");
        Files.writeString(baseDirectory.resolve("resource.txt"), "hello\n");

        var task = TestSyncTask.builder().build();
        RunContext runContext = runContextFactory.of();

        Set<String> uris = task.gitResourcesContentByUri(baseDirectory, runContext).keySet().stream()
            .map(URI::toString)
            .collect(Collectors.toSet());

        assertThat(uris, hasItem("/.github/"));
        assertThat(uris, hasItem("/.github/workflow.yml"));
        assertThat(uris, hasItem("/resource.txt"));
        assertThat(uris, not(hasItem("/.gitignore")));
    }

    /** The Enterprise Edition has always excluded any path merely containing {@code .git}, including {@code .github/}. */
    @Test
    void gitResourcesContentByUri_eeStyleOverride_excludesAnyPathContainingGit() throws Exception {
        Path baseDirectory = Files.createTempDirectory("sync-task-git-internal-");
        Files.createDirectories(baseDirectory.resolve(".github"));
        Files.writeString(baseDirectory.resolve(".github").resolve("workflow.yml"), "on: push\n");
        Files.writeString(baseDirectory.resolve(".gitignore"), "*.log\n");
        Files.writeString(baseDirectory.resolve("resource.txt"), "hello\n");

        var task = new TestSyncTask() {
            @Override
            protected boolean isGitInternalPath(Path path) {
                return path.toString().contains(".git");
            }
        };
        RunContext runContext = runContextFactory.of();

        Set<String> uris = task.gitResourcesContentByUri(baseDirectory, runContext).keySet().stream()
            .map(URI::toString)
            .collect(Collectors.toSet());

        assertThat(uris, not(hasItem("/.github/")));
        assertThat(uris, not(hasItem("/.github/workflow.yml")));
        assertThat(uris, not(hasItem("/.gitignore")));
        assertThat(uris, hasItem("/resource.txt"));
    }

    /** OSS has always followed symlinks while walking the git directory. */
    @Test
    void gitResourcesContentByUri_ossDefault_followsSymlinks() throws Exception {
        Path baseDirectory = Files.createTempDirectory("sync-task-symlink-");
        Path outsideTarget = Files.createTempDirectory("sync-task-symlink-target-");
        Files.writeString(outsideTarget.resolve("linked.txt"), "outside content\n");
        Files.createSymbolicLink(baseDirectory.resolve("link"), outsideTarget);

        var task = TestSyncTask.builder().build();
        RunContext runContext = runContextFactory.of();

        Set<String> uris = task.gitResourcesContentByUri(baseDirectory, runContext).keySet().stream()
            .map(URI::toString)
            .collect(Collectors.toSet());

        assertThat(uris, hasItem("/link/linked.txt"));
    }

    /** The Enterprise Edition has never followed symlinks: a link pointing outside the cloned directory must not be read. */
    @Test
    void gitResourcesContentByUri_eeStyleOverride_doesNotFollowSymlinks() throws Exception {
        Path baseDirectory = Files.createTempDirectory("sync-task-symlink-");
        Path outsideTarget = Files.createTempDirectory("sync-task-symlink-target-");
        Files.writeString(outsideTarget.resolve("linked.txt"), "outside content\n");
        Files.createSymbolicLink(baseDirectory.resolve("link"), outsideTarget);

        var task = new TestSyncTask() {
            @Override
            protected boolean followSymlinks() {
                return false;
            }
        };
        RunContext runContext = runContextFactory.of();

        Set<String> uris = task.gitResourcesContentByUri(baseDirectory, runContext).keySet().stream()
            .map(URI::toString)
            .collect(Collectors.toSet());

        assertThat(uris, not(hasItem("/link/linked.txt")));
    }

    @Test
    void gitResourcesContentByUri_encodesSpecialCharactersAndKeepsDecodedPath() throws Exception {
        Path baseDirectory = Files.createTempDirectory("sync-task-special-chars-");
        Files.createDirectories(baseDirectory.resolve("tasks"));
        Files.createDirectories(baseDirectory.resolve("dir with space"));
        Files.writeString(baseDirectory.resolve("tasks").resolve("merge_one copy.yml"), "id: a\n");
        Files.writeString(baseDirectory.resolve("dir with space").resolve("inner.txt"), "x\n");
        Files.writeString(baseDirectory.resolve("100%.txt"), "x\n");
        Files.writeString(baseDirectory.resolve("a#b[1].txt"), "x\n");

        var task = TestSyncTask.builder().build();
        RunContext runContext = runContextFactory.of();

        Set<URI> uris = task.gitResourcesContentByUri(baseDirectory, runContext).keySet();
        Set<String> paths = uris.stream().map(URI::getPath).collect(Collectors.toSet());

        assertThat(paths, hasItem("/tasks/merge_one copy.yml"));
        assertThat(paths, hasItem("/dir with space/"));
        assertThat(paths, hasItem("/dir with space/inner.txt"));
        assertThat(paths, hasItem("/100%.txt"));
        assertThat(paths, hasItem("/a#b[1].txt"));

        var strings = uris.stream().map(URI::toString).collect(Collectors.toSet());
        assertThat(strings, hasItem("/100%25.txt"));
        assertThat(strings, hasItem("/dir%20with%20space/"));
    }

    /** Literal percent-sequences and `?` in names are preserved verbatim (previously decoded / parsed as a query). */
    @Test
    void gitResourcesContentByUri_keepsLiteralPercentSequencesAndQueryCharacters() throws Exception {
        Path baseDirectory = Files.createTempDirectory("sync-task-literal-chars-");
        Files.writeString(baseDirectory.resolve("a%20b.txt"), "x\n");
        Files.writeString(baseDirectory.resolve("what?.txt"), "x\n");

        var task = TestSyncTask.builder().build();
        Set<URI> uris = task.gitResourcesContentByUri(baseDirectory, runContextFactory.of()).keySet();

        assertThat(uris.stream().map(URI::getPath).collect(Collectors.toSet()), containsInAnyOrder("/a%20b.txt", "/what?.txt"));
        assertThat(uris.stream().map(URI::getQuery).filter(Objects::nonNull).toList(), empty());
    }

    @Test
    void run_syncsRepositoryContainingFileNamesWithSpecialCharacters() throws Exception {
        Path remote = Files.createTempDirectory("sync-task-remote-special-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.createDirectories(remote.resolve("tasks"));
            Files.writeString(remote.resolve("tasks").resolve("merge_one copy.yml"), "id: a\n");
            Files.writeString(remote.resolve("a#b[1].txt"), "x\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").call();
            git.branchCreate().setName("main").call();
        }

        for (var dryRun : List.of(false, true)) {
            var task = TestSyncTask.builder()
                .id("sync-" + IdUtils.create())
                .url(Property.ofValue(remote.toUri().toString()))
                .dryRun(Property.ofValue(dryRun))
                .delete(Property.ofValue(true))
                // "/gone%20file.txt" was synced earlier and removed from git; "/a%23b%5B1%5D.txt" is still in git.
                .existingResources(List.of("/gone%20file.txt", "/a%23b%5B1%5D.txt"))
                .build();

            var output = task.run(runContextFactory.of());

            assertThat(output.diffFileUri(), notNullValue());
            assertThat(task.getWrittenPaths(), hasItems("/tasks/merge_one copy.yml", "/a#b[1].txt"));
            if (dryRun) {
                assertThat(task.getDeletedResources(), empty());
            } else {
                assertThat(task.getDeletedResources(), contains("/gone%20file.txt"));
            }
        }
    }

    @Test
    void gitPath_returnsDecodedPath() throws Exception {
        var encoded = new URI(null, null, "/tasks/merge_one copy.yml", null);
        assertThat(encoded.toString(), is("/tasks/merge_one%20copy.yml"));
        assertThat(TestSyncTask.gitPath("_files", encoded), is("_files/tasks/merge_one copy.yml"));
        assertThat(TestSyncTask.gitPath("_files", URI.create("/tasks/plain.yml")), is("_files/tasks/plain.yml"));
    }
}
