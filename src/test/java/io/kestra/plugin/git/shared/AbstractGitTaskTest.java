package io.kestra.plugin.git.shared;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
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
import static org.hamcrest.Matchers.not;
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

    /**
     * The safe default differs by edition: OSS never verified the host key (unchanged), while the Enterprise
     * Edition has always defaulted to verifying it (unchanged). {@code defaultStrictHostKeyChecking()} is the
     * overridable seam an edition-specific base class hooks into instead of every concrete task re-declaring the
     * property with a different hardcoded default.
     */
    @Test
    void defaultStrictHostKeyChecking_isFalseUnlessAnEditionOverridesIt() {
        var ossTask = TestCloningTask.builder().build();
        assertThat(ossTask.defaultStrictHostKeyChecking(), is(false));

        var eeStyleTask = new TestCloningTask() {
            @Override
            protected boolean defaultStrictHostKeyChecking() {
                return true;
            }
        };
        assertThat(eeStyleTask.defaultStrictHostKeyChecking(), is(true));
    }

    @Test
    void authentified_resolvesStrictHostKeyCheckingThroughTheDefaultHookWhenUnset() throws Exception {
        var ossTask = TestCloningTask.builder()
            .privateKey(Property.ofValue("dummy-pem-content"))
            .build();
        var eeStyleTask = new TestCloningTask() {
            @Override
            protected boolean defaultStrictHostKeyChecking() {
                return true;
            }
        };
        eeStyleTask.privateKey = Property.ofValue("dummy-pem-content");

        var ossCommand = ossTask.authentified(Git.lsRemoteRepository(), runContextFactory.of());
        var eeCommand = eeStyleTask.authentified(Git.lsRemoteRepository(), runContextFactory.of());

        assertThat(readSshCallbackField(ossCommand, "strictHostKeyChecking"), is(false));
        assertThat(readSshCallbackField(eeCommand, "strictHostKeyChecking"), is(true));
    }

    @Test
    void authentified_explicitPropertyOverridesTheDefaultHook() throws Exception {
        var eeStyleTaskWithExplicitFalse = new TestCloningTask() {
            @Override
            protected boolean defaultStrictHostKeyChecking() {
                return true;
            }
        };
        eeStyleTaskWithExplicitFalse.privateKey = Property.ofValue("dummy-pem-content");
        eeStyleTaskWithExplicitFalse.strictHostKeyChecking = Property.ofValue(false);

        var command = eeStyleTaskWithExplicitFalse.authentified(Git.lsRemoteRepository(), runContextFactory.of());

        assertThat(readSshCallbackField(command, "strictHostKeyChecking"), is(false));
    }

    private static Object readSshCallbackField(TransportCommand<?, ?> command, String name) throws Exception {
        Object callback = readField(command, "transportConfigCallback");
        Field field = callback.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(callback);
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

    /**
     * {@code configureHttpTransport} installs a JVM-global connection factory; a task that leaves {@code noProxy}
     * /{@code connectTimeout}/{@code readTimeout} unset must not mutate it, so it never affects an unrelated task
     * running concurrently in the same JVM.
     */
    @Test
    void configureHttpTransport_isNoOpWhenNothingIsConfigured() throws Exception {
        HttpConnectionFactory before = HttpTransport.getConnectionFactory();
        try {
            var task = TestCloningTask.builder().build();

            task.configureHttpTransport(runContextFactory.of());

            assertThat(HttpTransport.getConnectionFactory(), is(before));
        } finally {
            HttpTransport.setConnectionFactory(before);
        }
    }

    @Test
    void configureHttpTransport_installsAFactoryWhenNoProxyIsSet() throws Exception {
        HttpConnectionFactory before = HttpTransport.getConnectionFactory();
        try {
            var task = TestCloningTask.builder()
                .noProxy(Property.ofValue(true))
                .build();

            task.configureHttpTransport(runContextFactory.of());

            assertThat(HttpTransport.getConnectionFactory(), is(not(before)));
        } finally {
            HttpTransport.setConnectionFactory(before);
        }
    }

    @Test
    void configureHttpTransport_installsAFactoryWhenConnectTimeoutIsSet() throws Exception {
        HttpConnectionFactory before = HttpTransport.getConnectionFactory();
        try {
            var task = TestCloningTask.builder()
                .connectTimeout(Property.ofValue(5000))
                .build();

            task.configureHttpTransport(runContextFactory.of());

            assertThat(HttpTransport.getConnectionFactory(), is(not(before)));
        } finally {
            HttpTransport.setConnectionFactory(before);
        }
    }
}
