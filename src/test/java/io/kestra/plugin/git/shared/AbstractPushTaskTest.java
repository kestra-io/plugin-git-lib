package io.kestra.plugin.git.shared;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.Rethrow;
import io.kestra.plugin.git.shared.AbstractPushTask.PushMode;
import io.kestra.plugin.git.shared.TestTasks.TestEeStylePushTask;
import io.kestra.plugin.git.shared.TestTasks.TestPushTask;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
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
        return newPushableRemote(Map.of("resource-a.txt", "hello\n"));
    }

    private Path newPushableRemote(Map<String, String> initialFiles) throws Exception {
        Path remote = Files.createTempDirectory("push-task-remote-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            git.getRepository().updateRef(Constants.HEAD).link("refs/heads/main");
            git.getRepository().getConfig().setString("receive", null, "denyCurrentBranch", "ignore");
            git.getRepository().getConfig().save();

            for (var entry : initialFiles.entrySet()) {
                Files.writeString(remote.resolve(entry.getKey()), entry.getValue());
                git.add().addFilepattern(entry.getKey()).call();
            }
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
     * Reads a file's content back from the git object store on "refs/heads/main", for the same {@code file://}
     * reason as {@link #pushedTreeContains}.
     */
    private String readPushedFileContent(Path remote, String relativePath) throws Exception {
        try (
            Git git = Git.open(remote.toFile());
            RevWalk revWalk = new RevWalk(git.getRepository());
            ObjectReader reader = git.getRepository().newObjectReader()
        ) {
            var headId = git.getRepository().resolve("refs/heads/main");
            RevCommit commit = revWalk.parseCommit(headId);
            try (TreeWalk treeWalk = TreeWalk.forPath(git.getRepository(), relativePath, commit.getTree())) {
                ObjectId blobId = treeWalk.getObjectId(0);
                return new String(reader.open(blobId).getBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private void assertDiffs(RunContext runContext, URI diffFileUri, List<Map<String, String>> expectedDiffs) throws IOException {
        String diffSummary = new String(runContext.storage().getFile(diffFileUri).readAllBytes(), StandardCharsets.UTF_8);
        List<Map<String, String>> diffMaps = diffSummary.lines()
            .map(Rethrow.throwFunction(diff -> JacksonMapper.ofIon().readValue(diff, new TypeReference<Map<String, String>>() {})))
            .toList();
        assertThat(diffMaps, containsInAnyOrder(expectedDiffs.toArray(Map[]::new)));
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

    @Test
    void syncMode_explicit_matchesDefaultBehavior() throws Exception {
        Path remote = newPushableRemote();
        RunContext runContext = runContextFactory.of();

        TestPushTask task = TestPushTask.builder()
            .url(Property.ofValue(remote.toUri().toString()))
            .filesToWrite(Map.of("resource-b.txt", "new content\n"))
            .pushMode(Property.ofValue(PushMode.SYNC))
            .build();

        TestPushTask.Output output = task.run(runContext);

        assertThat(output.getCommitId(), notNullValue());
        assertThat(pushedTreeContains(remote, "resource-b.txt"), is(true));
    }

    /**
     * Exact repro of the reported bug: a deletion (resource-a.txt) and an unrelated modification (resource-b.txt)
     * are both present on the instance side, but {@code DELETE_ONLY} must stage only the deletion — the modified
     * resource must reach the remote untouched.
     */
    @Test
    void deleteOnly_stagesOnlyTheDeletion_leavesModifiedResourceUntouched() throws Exception {
        Path remote = newPushableRemote(Map.of(
            "resource-a.txt", "hello\n",
            "resource-b.txt", "original content\n"
        ));
        RunContext runContext = runContextFactory.of();

        TestPushTask task = TestPushTask.builder()
            .url(Property.ofValue(remote.toUri().toString()))
            .filesToWrite(Map.of("resource-b.txt", "modified content\n"))
            .pushMode(Property.ofValue(PushMode.DELETE_ONLY))
            .build();

        TestPushTask.Output output = task.run(runContext);

        assertThat(output.getCommitId(), notNullValue());
        assertThat(pushedTreeContains(remote, "resource-a.txt"), is(false));
        assertThat(readPushedFileContent(remote, "resource-b.txt"), is("original content\n"));
    }

    @Test
    void deleteOnly_withNothingRemoved_skipsGitOperations() throws Exception {
        Path remote = newPushableRemote();
        RunContext runContext = runContextFactory.of();

        TestPushTask task = TestPushTask.builder()
            .url(Property.ofValue(remote.toUri().toString()))
            .filesToWrite(Map.of("resource-a.txt", "hello\n"))
            .pushMode(Property.ofValue(PushMode.DELETE_ONLY))
            .build();

        TestPushTask.Output output = task.run(runContext);

        assertThat(output.getCommitId(), is((String) null));
        assertThat(pushedTreeContains(remote, "resource-a.txt"), is(true));
    }

    @Test
    void deleteOnly_withDryRun_diffListsOnlyDeletions_nothingIsPushed() throws Exception {
        Path remote = newPushableRemote(Map.of(
            "resource-a.txt", "hello\n",
            "resource-b.txt", "original content\n"
        ));
        RunContext runContext = runContextFactory.of();

        TestPushTask task = TestPushTask.builder()
            .url(Property.ofValue(remote.toUri().toString()))
            .filesToWrite(Map.of("resource-b.txt", "modified content\n"))
            .pushMode(Property.ofValue(PushMode.DELETE_ONLY))
            .dryRun(Property.ofValue(true))
            .build();

        TestPushTask.Output output = task.run(runContext);

        assertThat(output.getCommitURL(), is((String) null));
        assertDiffs(runContext, output.diffFileUri(), List.of(
            Map.of("additions", "+0", "deletions", "-1", "changes", "0", "file", "resource-a.txt")
        ));
        assertThat(readPushedFileContent(remote, "resource-a.txt"), is("hello\n"));
    }

    /**
     * A user setting {@code pushMode: DELETE_ONLY} together with {@code delete: false} would otherwise push
     * nothing at all — {@code delete} is treated as true in this mode, but the contradiction is surfaced with a
     * warning naming both properties.
     */
    @Test
    void deleteOnly_withDeleteFalse_warnsAndStillStagesDeletion() throws Exception {
        Path remote = newPushableRemote();
        RunContext runContext = runContextFactory.of();

        TestPushTask task = TestPushTask.builder()
            .url(Property.ofValue(remote.toUri().toString()))
            .filesToWrite(Map.of())
            .pushMode(Property.ofValue(PushMode.DELETE_ONLY))
            .delete(Property.ofValue(false))
            .build();

        Logger logbackLogger = (Logger) runContext.logger();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);

        TestPushTask.Output output;
        try {
            output = task.run(runContext);
        } finally {
            logbackLogger.detachAppender(appender);
        }

        assertThat(output.getCommitId(), notNullValue());
        assertThat(pushedTreeContains(remote, "resource-a.txt"), is(false));

        boolean warned = appender.list.stream()
            .anyMatch(event -> event.getFormattedMessage().contains("pushMode")
                && event.getFormattedMessage().contains("DELETE_ONLY")
                && event.getFormattedMessage().contains("delete"));
        assertThat(warned, is(true));
    }

    /**
     * Guards the EE seam: {@code TestEeStylePushTask} stages the whole git directory in one call instead of per
     * resolved file, but {@code DELETE_ONLY} must skip the {@code AddCommand} step entirely regardless, so only the
     * deletion is staged.
     */
    @Test
    void deleteOnly_withStageWholeGitDirectoryOverride_stagesOnlyDeletion() throws Exception {
        Path remote = newPushableRemote(Map.of(
            "resource-a.txt", "hello\n",
            "resource-b.txt", "original content\n"
        ));
        RunContext runContext = runContextFactory.of();

        TestEeStylePushTask task = TestEeStylePushTask.builder()
            .url(Property.ofValue(remote.toUri().toString()))
            .filesToWrite(Map.of("resource-b.txt", "modified content\n"))
            .pushMode(Property.ofValue(PushMode.DELETE_ONLY))
            .build();

        TestPushTask.Output output = task.run(runContext);

        assertThat(output.getCommitId(), notNullValue());
        assertThat(pushedTreeContains(remote, "resource-a.txt"), is(false));
        assertThat(readPushedFileContent(remote, "resource-b.txt"), is("original content\n"));
    }

    /**
     * Regression test for the second, unconfirmed report on the issue: a single glob scoped to one flow id, with
     * {@code delete: true}, must stage the deletion of only the matching file — {@code deleteOutdatedResources}
     * matches a glob against the git-relative path, the file name, AND the file stem, so a bare flow id like
     * "flow-a" matches "flow-a.yml".
     */
    @Test
    void singleGlob_withDelete_stagesDeletionScopedToTheGlob() throws Exception {
        Path remote = newPushableRemote(Map.of(
            "flow-a.yml", "id: flow-a\n",
            "flow-b.yml", "id: flow-b\n"
        ));
        RunContext runContext = runContextFactory.of();

        TestPushTask task = TestPushTask.builder()
            .url(Property.ofValue(remote.toUri().toString()))
            .filesToWrite(Map.of())
            .globs(List.of("flow-a"))
            .delete(Property.ofValue(true))
            .build();

        TestPushTask.Output output = task.run(runContext);

        assertThat(output.getCommitId(), notNullValue());
        assertThat(pushedTreeContains(remote, "flow-a.yml"), is(false));
        assertThat(pushedTreeContains(remote, "flow-b.yml"), is(true));
    }
}
