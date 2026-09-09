package io.kestra.plugin.git.shared;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.git.shared.TestTasks.TestCloningTask;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
class AbstractGitTaskTest {

    @Inject
    private RunContextFactory runContextFactory;

    /** {@code TransportCommand} exposes neither field via a public getter. */
    private static Object readField(TransportCommand<?, ?> command, String name) throws Exception {
        Field field = TransportCommand.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(command);
    }

    @Test
    void authentified_setsCredentialsProviderForUsernamePassword() throws Exception {
        var task = TestCloningTask.builder()
            .username(Property.ofValue("user"))
            .password(Property.ofValue("token"))
            .build();

        LsRemoteCommand command = task.authentified(Git.lsRemoteRepository(), runContextFactory.of());

        assertThat(readField(command, "credentialsProvider"), instanceOf(UsernamePasswordCredentialsProvider.class));
    }

    @Test
    void authentified_setsTransportConfigCallbackForPrivateKey() throws Exception {
        var task = TestCloningTask.builder()
            .privateKey(Property.ofValue("dummy-pem-content"))
            .build();

        LsRemoteCommand command = task.authentified(Git.lsRemoteRepository(), runContextFactory.of());

        assertThat(readField(command, "transportConfigCallback"), notNullValue());
    }

    @Test
    void strictHostKeyChecking_defaultsToFalse() throws Exception {
        var task = TestCloningTask.builder().build();

        assertThat(runContextFactory.of().render(task.getStrictHostKeyChecking()).as(Boolean.class).orElseThrow(), is(false));
    }

    @Test
    void applyGitConfig_setsBooleanAndStringValues() throws Exception {
        Path repoDir = Files.createTempDirectory("apply-git-config-");
        PersonIdent author = new PersonIdent("Test User", "test@example.com");
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            Files.writeString(repoDir.resolve("README.md"), "hello\n");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("init").setAuthor(author).setCommitter(author).call();

            RunContext runContext = runContextFactory.of();
            var task = TestCloningTask.builder()
                .gitConfig(Property.ofValue(Map.of("core.fileMode", false, "user.name", "kestra")))
                .build();

            task.applyGitConfig(git.getRepository(), runContext);

            assertThat(git.getRepository().getConfig().getBoolean("core", null, "fileMode", true), is(false));
            assertThat(git.getRepository().getConfig().getString("user", null, "name"), is("kestra"));
        }
    }
}
