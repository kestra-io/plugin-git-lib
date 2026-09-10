package io.kestra.plugin.git.shared.services;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.git.shared.TestTasks.TestCloningTask;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class GitServiceTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void getHttpUrl_convertsSshUrlToHttps() {
        var gitService = new GitService(TestCloningTask.builder().build());

        assertThat(gitService.getHttpUrl("git@github.com:kestra-io/plugin-git.git"), is("https://github.com/kestra-io/plugin-git.git"));
    }

    @Test
    void getHttpUrl_stripsCredentialsFromHttpUrl() {
        var gitService = new GitService(TestCloningTask.builder().build());

        assertThat(gitService.getHttpUrl("https://user:token@github.com/kestra-io/plugin-git.git"), is("https://github.com/kestra-io/plugin-git.git"));
    }

    @Test
    void cloneBranch_createsTheBranchWhenItDoesNotExistOnTheRemote() throws Exception {
        Path remote = Files.createTempDirectory("git-service-remote-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file.txt"), "hello\n");
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("init").call();
        }

        RunContext runContext = runContextFactory.of();
        var gitTask = TestCloningTask.builder().url(Property.ofValue(remote.toUri().toString())).build();
        var gitService = new GitService(gitTask);

        try (Git git = gitService.cloneBranch(runContext, "new-branch", null)) {
            assertThat(git.getRepository().getBranch(), is("new-branch"));
        }
    }

    private GitService remoteWithMainBranch() throws Exception {
        Path remote = Files.createTempDirectory("git-service-remote-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file.txt"), "hello\n");
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("init").call();
        }
        return new GitService(TestCloningTask.builder().url(Property.ofValue(remote.toUri().toString())).build());
    }

    @Test
    void ensureBranchExistsOrFail_throwsWhenBranchMissingAndFailOnMissing() throws Exception {
        RunContext runContext = runContextFactory.of();
        GitService gitService = remoteWithMainBranch();

        assertThrows(IllegalArgumentException.class, () -> gitService.ensureBranchExistsOrFail(runContext, "missing-branch", true));
    }

    @Test
    void ensureBranchExistsOrFail_isNoOpWhenFailOnMissingIsFalse() throws Exception {
        RunContext runContext = runContextFactory.of();
        GitService gitService = remoteWithMainBranch();

        gitService.ensureBranchExistsOrFail(runContext, "missing-branch", false);
    }

    @Test
    void ensureBranchExistsOrFail_isNoOpWhenBranchIsBlank() throws Exception {
        RunContext runContext = runContextFactory.of();
        GitService gitService = remoteWithMainBranch();

        gitService.ensureBranchExistsOrFail(runContext, null, true);
        gitService.ensureBranchExistsOrFail(runContext, "  ", true);
    }

    @Test
    void ensureBranchExistsOrFail_passesWhenBranchExists() throws Exception {
        Path remote = Files.createTempDirectory("git-service-remote-");
        String defaultBranch;
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file.txt"), "hello\n");
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("init").call();
            defaultBranch = git.getRepository().getBranch();
        }

        RunContext runContext = runContextFactory.of();
        GitService gitService = new GitService(TestCloningTask.builder().url(Property.ofValue(remote.toUri().toString())).build());

        gitService.ensureBranchExistsOrFail(runContext, defaultBranch, true);
    }
}
