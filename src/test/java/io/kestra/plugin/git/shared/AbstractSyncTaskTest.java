package io.kestra.plugin.git.shared;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.git.shared.TestTasks.TestSyncTask;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@KestraTest
class AbstractSyncTaskTest {

    @Inject
    private RunContextFactory runContextFactory;

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
}
