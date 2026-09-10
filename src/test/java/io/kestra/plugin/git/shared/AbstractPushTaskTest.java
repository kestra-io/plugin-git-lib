package io.kestra.plugin.git.shared;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.git.shared.TestTasks.TestPushTask;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
class AbstractPushTaskTest {

    @Inject
    private RunContextFactory runContextFactory;

    /**
     * A local, non-bare repository usable as a push target: allows pushing to the checked-out branch. The pinned
     * "refs/heads/main" avoids depending on the system's {@code init.defaultBranch} config, since TestPushTask
     * always pushes to "main".
     */
    private Path newPushableRemote() throws Exception {
        Path remote = Files.createTempDirectory("push-task-remote-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            git.getRepository().updateRef(Constants.HEAD).link("refs/heads/main");
            git.getRepository().getConfig().setString("receive", null, "denyCurrentBranch", "ignore");
            git.getRepository().getConfig().save();

            Files.writeString(remote.resolve("resource-a.txt"), "hello\n");
            git.add().addFilepattern("resource-a.txt").call();
            git.commit().setMessage("init").call();
        }
        return remote;
    }

    /**
     * {@code file://} pushes bypass jgit's {@code ReceivePack}/{@code denyCurrentBranch=updateInstead} working-tree
     * sync, so the pushed content must be read back from the git object store on "refs/heads/main" instead of the
     * remote's working directory.
     */
    private boolean pushedTreeContains(Path remote, String relativePath) throws Exception {
        try (
            Git git = Git.open(remote.toFile());
            RevWalk revWalk = new RevWalk(git.getRepository())
        ) {
            var headId = git.getRepository().resolve("refs/heads/main");
            RevCommit commit = revWalk.parseCommit(headId);
            try (TreeWalk treeWalk = TreeWalk.forPath(git.getRepository(), relativePath, commit.getTree())) {
                return treeWalk != null;
            }
        }
    }

    /**
     * Reproduces the bug: an instance with zero resources of a kind stages the deletion of every previously-pushed
     * resource of that kind (via {@code git rm}), but the push used to be skipped entirely because
     * {@code contentByPath} was empty, silently dropping the staged deletion. The deletion must still be committed
     * and pushed.
     */
    @Test
    void emptyContentWithADeletion_stillCommitsAndPushesTheDeletion() throws Exception {
        Path remote = newPushableRemote();
        RunContext runContext = runContextFactory.of();

        TestPushTask task = TestPushTask.builder()
            .url(Property.ofValue(remote.toUri().toString()))
            .filesToWrite(Map.of())
            .build();

        AbstractPushTask.Output output = task.run(runContext);

        assertThat(output.getCommitId(), notNullValue());
        assertThat(pushedTreeContains(remote, "resource-a.txt"), is(false));
    }

    @Test
    void nonEmptyContent_isCommittedAndPushed() throws Exception {
        Path remote = newPushableRemote();
        RunContext runContext = runContextFactory.of();

        TestPushTask task = TestPushTask.builder()
            .url(Property.ofValue(remote.toUri().toString()))
            .filesToWrite(Map.of("resource-b.txt", "new content\n"))
            .build();

        AbstractPushTask.Output output = task.run(runContext);

        assertThat(output.getCommitId(), notNullValue());
        assertThat(pushedTreeContains(remote, "resource-b.txt"), is(true));
    }

    @Test
    void noContentAndNoDeletion_skipsGitOperationsEntirely() throws Exception {
        Path remote = Files.createTempDirectory("push-task-remote-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            git.getRepository().updateRef(Constants.HEAD).link("refs/heads/main");
            git.getRepository().getConfig().setString("receive", null, "denyCurrentBranch", "ignore");
            git.getRepository().getConfig().save();
            Files.writeString(remote.resolve("README.md"), "hello\n");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("init").call();
        }

        RunContext runContext = runContextFactory.of();
        TestPushTask task = TestPushTask.builder()
            .url(Property.ofValue(remote.toUri().toString()))
            .filesToWrite(Map.of())
            .delete(Property.ofValue(false))
            .build();

        AbstractPushTask.Output output = task.run(runContext);

        assertThat(output.getCommitId(), is((String) null));
    }

    /**
     * These hooks default to OSS's unchanged behavior; an edition-specific base class overrides them instead of
     * every concrete EE push task re-declaring the behavior (mirrors {@code defaultStrictHostKeyChecking}).
     */
    @Test
    void hooks_defaultToOssBehaviorUnlessOverridden() {
        var ossTask = TestPushTask.builder().build();
        assertThat(ossTask.applyKestraIgnoreFiltering(), is(true));
        assertThat(ossTask.stageWholeGitDirectory(), is(false));
        assertThat(ossTask.setCommitterFromAuthor(), is(true));

        var eeStyleTask = new TestPushTask() {
            @Override
            protected boolean applyKestraIgnoreFiltering() {
                return false;
            }

            @Override
            protected boolean stageWholeGitDirectory() {
                return true;
            }

            @Override
            protected boolean setCommitterFromAuthor() {
                return false;
            }
        };
        assertThat(eeStyleTask.applyKestraIgnoreFiltering(), is(false));
        assertThat(eeStyleTask.stageWholeGitDirectory(), is(true));
        assertThat(eeStyleTask.setCommitterFromAuthor(), is(false));
    }
}
