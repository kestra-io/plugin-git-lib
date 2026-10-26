package io.kestra.plugin.git.shared.services;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.git.shared.TestTasks.TestCloningTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;

import jakarta.inject.Inject;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.lib.Repository;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class CloneServiceTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void clonesIntoAnEmptyDirectory() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-remote-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file.txt"), "hello\n");
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("init").call();
        }

        RunContext runContext = runContextFactory.of();
        TestCloningTask gitTask = TestCloningTask.builder().build();

        var result = CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
            .url(remote.toUri().toString())
            .path(runContext.workingDir().path())
            .cloneAllBranches(true)
            .build());

        assertThat(Files.exists(Path.of(result.directory()).resolve("file.txt")), is(true));
    }

    @Test
    void clonesAtASpecificCommit() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-remote-");
        String firstCommitSha;
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file1.txt"), "first\n");
            git.add().addFilepattern("file1.txt").call();
            git.commit().setMessage("first").call();
            firstCommitSha = git.getRepository().resolve("HEAD").name();

            Files.writeString(remote.resolve("file2.txt"), "second\n");
            git.add().addFilepattern("file2.txt").call();
            git.commit().setMessage("second").call();
        }

        RunContext runContext = runContextFactory.of();
        TestCloningTask gitTask = TestCloningTask.builder().build();

        var result = CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
            .url(remote.toUri().toString())
            .path(runContext.workingDir().path())
            .commit(firstCommitSha)
            .cloneAllBranches(true)
            .build());

        Path repoPath = Path.of(result.directory());
        assertThat(Files.exists(repoPath.resolve("file1.txt")), is(true));
        assertThat(Files.exists(repoPath.resolve("file2.txt")), is(false));
    }

    @Test
    void clonesIntoANonEmptyDirectoryUsingInitFetchCheckout() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-remote-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("repo-file.txt"), "from repo\n");
            git.add().addFilepattern("repo-file.txt").call();
            git.commit().setMessage("initial").call();
        }

        RunContext runContext = runContextFactory.of();
        Path workingDir = runContext.workingDir().path();
        Files.writeString(workingDir.resolve("pre-existing.txt"), "I was here first\n");

        TestCloningTask gitTask = TestCloningTask.builder().build();

        var result = CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
            .url(remote.toUri().toString())
            .path(workingDir)
            .cloneAllBranches(true)
            .build());

        Path repoPath = Path.of(result.directory());
        assertThat(Files.readString(repoPath.resolve("pre-existing.txt")), is("I was here first\n"));
        assertThat(Files.readString(repoPath.resolve("repo-file.txt")).replace("\r\n", "\n"), is("from repo\n"));
    }

    @Test
    void clonesOnlyTheConfiguredBranch() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-remote-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            String initialBranch = git.getRepository().getBranch();

            Files.writeString(remote.resolve("main.txt"), "main\n");
            git.add().addFilepattern("main.txt").call();
            git.commit().setMessage("main").call();

            git.checkout().setCreateBranch(true).setName("feature/only").call();
            Files.writeString(remote.resolve("feature.txt"), "feature\n");
            git.add().addFilepattern("feature.txt").call();
            git.commit().setMessage("feature").call();

            git.checkout().setCreateBranch(true).setName("extra/branch").call();
            Files.writeString(remote.resolve("extra.txt"), "extra\n");
            git.add().addFilepattern("extra.txt").call();
            git.commit().setMessage("extra").call();

            git.checkout().setName(initialBranch).call();
        }

        RunContext runContext = runContextFactory.of();
        TestCloningTask gitTask = TestCloningTask.builder().build();

        var result = CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
            .url(remote.toUri().toString())
            .path(runContext.workingDir().path())
            .branch("feature/only")
            .cloneAllBranches(false)
            .build());

        try (Git cloned = Git.open(Path.of(result.directory()).toFile())) {
            assertNotNull(cloned.getRepository().exactRef("refs/remotes/origin/feature/only"));
            assertNull(cloned.getRepository().exactRef("refs/remotes/origin/extra/branch"));
        }
    }

    /**
     * {@code CloneService.clone} is the single source of the "Start cloning" log line; the consumer {@code Clone}
     * tasks (plugin-git, plugin-ee-git) must not log it a second time, otherwise every clone logs it twice.
     */
    @Test
    void logsStartCloningExactlyOnce() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-remote-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file.txt"), "hello\n");
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("init").call();
        }

        RunContext runContext = runContextFactory.of();
        TestCloningTask gitTask = TestCloningTask.builder().build();

        Logger logbackLogger = (Logger) runContext.logger();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);

        try {
            CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
                .url(remote.toUri().toString())
                .path(runContext.workingDir().path())
                .cloneAllBranches(true)
                .build());
        } finally {
            logbackLogger.detachAppender(appender);
        }

        long startCloningLogCount = appender.list.stream()
            .filter(event -> event.getFormattedMessage().startsWith("Start cloning"))
            .count();

        assertThat(startCloningLogCount, is(1L));
    }

    @Test
    void clonesAtASpecificTag() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-remote-tag-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file1.txt"), "first\n");
            git.add().addFilepattern("file1.txt").call();
            var commit1 = git.commit().setMessage("first").call();
            git.tag().setName("v1.0.0").setObjectId(commit1).call();

            Files.writeString(remote.resolve("file2.txt"), "second\n");
            git.add().addFilepattern("file2.txt").call();
            git.commit().setMessage("second").call();
        }

        RunContext runContext = runContextFactory.of();
        TestCloningTask gitTask = TestCloningTask.builder().build();

        var result = CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
            .url(remote.toUri().toString())
            .path(runContext.workingDir().path())
            .tag("v1.0.0")
            .cloneAllBranches(true)
            .build());

        Path repoPath = Path.of(result.directory());
        assertThat(Files.exists(repoPath.resolve("file1.txt")), is(true));
        assertThat(Files.exists(repoPath.resolve("file2.txt")), is(false));
    }

    @Test
    void clonesAtAnAnnotatedTag() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-remote-annotated-tag-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file1.txt"), "first\n");
            git.add().addFilepattern("file1.txt").call();
            git.commit().setMessage("first").call();

            Files.writeString(remote.resolve("file2.txt"), "second\n");
            git.add().addFilepattern("file2.txt").call();
            git.commit().setMessage("second").call();
            git.tag().setName("v2.0.0").setAnnotated(true).setMessage("release v2").call();
        }

        RunContext runContext = runContextFactory.of();
        TestCloningTask gitTask = TestCloningTask.builder().build();

        var result = CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
            .url(remote.toUri().toString())
            .path(runContext.workingDir().path())
            .tag("v2.0.0")
            .cloneAllBranches(true)
            .build());

        Path repoPath = Path.of(result.directory());
        assertThat(Files.exists(repoPath.resolve("file1.txt")), is(true));
        assertThat(Files.exists(repoPath.resolve("file2.txt")), is(true));
    }

    private static final String TEST_USER = "gituser";
    private static final String TEST_PASSWORD = "gitpassword";

    @Test
    void clonesWithCredentialsAtTagOverHttp() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-http-auth-tag-");
        try (Git originGit = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file.txt"), "hello\n");
            originGit.add().addFilepattern("file.txt").call();
            originGit.commit().setMessage("init").call();
            originGit.tag().setName("v1.0.0").call();

            try (GitHttpServer server = new GitHttpServer(originGit.getRepository(), TEST_USER, TEST_PASSWORD)) {
                RunContext runContext = runContextFactory.of();
                TestCloningTask gitTask = TestCloningTask.builder()
                    .username(Property.ofValue(TEST_USER))
                    .password(Property.ofValue(TEST_PASSWORD))
                    .build();

                Logger logbackLogger = (Logger) runContext.logger();
                ListAppender<ILoggingEvent> appender = new ListAppender<>();
                appender.start();
                logbackLogger.addAppender(appender);

                try {
                    var result = CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
                        .url(server.getUrl())
                        .path(runContext.workingDir().path())
                        .tag("v1.0.0")
                        .cloneAllBranches(true)
                        .build());

                    Path repoPath = Path.of(result.directory());
                    assertThat(Files.exists(repoPath.resolve("file.txt")), is(true));
                } finally {
                    logbackLogger.detachAppender(appender);
                }

                boolean hasCredentialsWarning = appender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().contains("no CredentialsProvider has been registered")
                        || event.getFormattedMessage().contains("Fetch before tag checkout failed"));

                assertThat(hasCredentialsWarning, is(false));
            }
        }
    }

    @Test
    void clonesWithCredentialsAtTagWithNoTagsOverHttp() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-http-auth-notags-");
        try (Git originGit = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file.txt"), "hello\n");
            originGit.add().addFilepattern("file.txt").call();
            originGit.commit().setMessage("init").call();
            originGit.tag().setName("v1.0.0").call();

            try (GitHttpServer server = new GitHttpServer(originGit.getRepository(), TEST_USER, TEST_PASSWORD)) {
                RunContext runContext = runContextFactory.of();
                TestCloningTask gitTask = TestCloningTask.builder()
                    .username(Property.ofValue(TEST_USER))
                    .password(Property.ofValue(TEST_PASSWORD))
                    .build();

                var result = CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
                    .url(server.getUrl())
                    .path(runContext.workingDir().path())
                    .tag("v1.0.0")
                    .noTags(true)
                    .build());

                Path repoPath = Path.of(result.directory());
                assertThat(Files.exists(repoPath.resolve("file.txt")), is(true));
            }
        }
    }

    @Test
    void clonesWithCredentialsAtCommitOverHttp() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-http-auth-commit-");
        try (Git originGit = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file.txt"), "hello\n");
            originGit.add().addFilepattern("file.txt").call();
            var commit = originGit.commit().setMessage("init").call();

            try (GitHttpServer server = new GitHttpServer(originGit.getRepository(), TEST_USER, TEST_PASSWORD)) {
                RunContext runContext = runContextFactory.of();
                TestCloningTask gitTask = TestCloningTask.builder()
                    .username(Property.ofValue(TEST_USER))
                    .password(Property.ofValue(TEST_PASSWORD))
                    .build();

                Logger logbackLogger = (Logger) runContext.logger();
                ListAppender<ILoggingEvent> appender = new ListAppender<>();
                appender.start();
                logbackLogger.addAppender(appender);

                try {
                    var result = CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
                        .url(server.getUrl())
                        .path(runContext.workingDir().path())
                        .commit(commit.name())
                        .cloneAllBranches(true)
                        .build());

                    Path repoPath = Path.of(result.directory());
                    assertThat(Files.exists(repoPath.resolve("file.txt")), is(true));
                } finally {
                    logbackLogger.detachAppender(appender);
                }

                boolean hasFetchWarning = appender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().contains("Fetch before checkout failed")
                        || event.getFormattedMessage().contains("no CredentialsProvider has been registered"));
                assertThat(hasFetchWarning, is(false));
            }
        }
    }

    @Test
    void cloningAtTagWithoutCredentialsOverHttpFails() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-http-unauth-tag-");
        try (Git originGit = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file.txt"), "hello\n");
            originGit.add().addFilepattern("file.txt").call();
            originGit.commit().setMessage("init").call();
            originGit.tag().setName("v1.0.0").call();

            try (GitHttpServer server = new GitHttpServer(originGit.getRepository(), TEST_USER, TEST_PASSWORD)) {
                RunContext runContext = runContextFactory.of();
                TestCloningTask gitTask = TestCloningTask.builder().build();

                assertThrows(TransportException.class, () ->
                    CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
                        .url(server.getUrl())
                        .path(runContext.workingDir().path())
                        .tag("v1.0.0")
                        .cloneAllBranches(true)
                        .build())
                );
            }
        }
    }

    @Test
    void cloningAtCommitWithoutCredentialsOverHttpFails() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-http-unauth-commit-");
        try (Git originGit = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file.txt"), "hello\n");
            originGit.add().addFilepattern("file.txt").call();
            var commit = originGit.commit().setMessage("init").call();

            try (GitHttpServer server = new GitHttpServer(originGit.getRepository(), TEST_USER, TEST_PASSWORD)) {
                RunContext runContext = runContextFactory.of();
                TestCloningTask gitTask = TestCloningTask.builder().build();

                assertThrows(TransportException.class, () ->
                    CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
                        .url(server.getUrl())
                        .path(runContext.workingDir().path())
                        .commit(commit.name())
                        .cloneAllBranches(true)
                        .build())
                );
            }
        }
    }

    static class GitHttpServer implements AutoCloseable {
        private static final String LOOPBACK_HOST = "127.0.0.1";

        private final Server server;
        private final int port;
        private final String username;
        private final String password;

        GitHttpServer(Repository repository, String username, String password) throws Exception {
            this.username = username;
            this.password = password;

            this.server = new Server();
            ServerConnector connector = new ServerConnector(server);
            connector.setHost(LOOPBACK_HOST);
            connector.setPort(0);
            server.addConnector(connector);
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");

            context.addFilter(new FilterHolder(new Filter() {
                @Override
                public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
                    HttpServletRequest req = (HttpServletRequest) request;
                    HttpServletResponse res = (HttpServletResponse) response;

                    String auth = req.getHeader("Authorization");
                    if (auth != null && auth.startsWith("Basic ")) {
                        String creds = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
                        int colon = creds.indexOf(':');
                        if (colon > 0) {
                            String user = creds.substring(0, colon);
                            String pass = creds.substring(colon + 1);
                            if (GitHttpServer.this.username.equals(user) && GitHttpServer.this.password.equals(pass)) {
                                chain.doFilter(request, response);
                                return;
                            }
                        }
                    }

                    res.setHeader("WWW-Authenticate", "Basic realm=\"Git\"");
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                }
            }), "/*", EnumSet.of(DispatcherType.REQUEST));

            GitServlet gitServlet = new GitServlet();
            gitServlet.setRepositoryResolver((req, name) -> {
                repository.incrementOpen();
                return repository;
            });
            context.addServlet(new ServletHolder(gitServlet), "/*");

            server.setHandler(context);
            server.start();
            this.port = connector.getLocalPort();
        }

        String getUrl() {
            return "http://" + LOOPBACK_HOST + ":" + port + "/repo.git";
        }

        @Override
        public void close() throws Exception {
            server.stop();
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void backwardCompatibilityCheckoutMethodsSucceed() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-legacy-");
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file.txt"), "hello\n");
            git.add().addFilepattern("file.txt").call();
            var commit = git.commit().setMessage("init").call();
            git.tag().setName("v1.0.0").call();

            // Invoke legacy 4-parameter checkout methods directly
            CloneService.checkoutTag(git, "v1.0.0", runContextFactory.of().logger(), false);
            CloneService.checkoutCommit(git, commit.name(), runContextFactory.of().logger(), false);
        }
    }

    @Test
    void clone_checksOutBranchWithRefsHeadsPrefix() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-remote-refs-heads-");
        String initialBranch;
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file.txt"), "hello\n");
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("init").call();
            initialBranch = git.getRepository().getBranch();

            git.checkout().setCreateBranch(true).setName("feature/mybranch").call();
            Files.writeString(remote.resolve("feature.txt"), "feature\n");
            git.add().addFilepattern("feature.txt").call();
            git.commit().setMessage("feature").call();

            git.checkout().setName(initialBranch).call();
        }

        RunContext runContext = runContextFactory.of();
        TestCloningTask gitTask = TestCloningTask.builder().build();

        var result = CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
            .url(remote.toUri().toString())
            .path(runContext.workingDir().path())
            .branch("refs/heads/feature/mybranch")
            .build());

        try (Git cloned = Git.open(Path.of(result.directory()).toFile())) {
            assertThat(cloned.getRepository().getBranch(), is("feature/mybranch"));
        }
    }

    @Test
    void clone_checksOutBranchWithRefsHeadsPrefix_intoNonEmptyDirectory() throws Exception {
        Path remote = Files.createTempDirectory("clone-service-remote-refs-heads-non-empty-");
        String initialBranch;
        try (Git git = Git.init().setDirectory(remote.toFile()).call()) {
            Files.writeString(remote.resolve("file.txt"), "hello\n");
            git.add().addFilepattern("file.txt").call();
            git.commit().setMessage("init").call();
            initialBranch = git.getRepository().getBranch();

            git.checkout().setCreateBranch(true).setName("feature/mybranch").call();
            Files.writeString(remote.resolve("feature.txt"), "feature\n");
            git.add().addFilepattern("feature.txt").call();
            git.commit().setMessage("feature").call();

            git.checkout().setName(initialBranch).call();
        }

        RunContext runContext = runContextFactory.of();
        Files.writeString(runContext.workingDir().path().resolve("pre-existing.txt"), "pre-existing\n");

        TestCloningTask gitTask = TestCloningTask.builder().build();

        var result = CloneService.clone(runContext, gitTask, CloneService.CloneRequest.builder()
            .url(remote.toUri().toString())
            .path(runContext.workingDir().path())
            .branch("refs/heads/feature/mybranch")
            .build());

        try (Git cloned = Git.open(Path.of(result.directory()).toFile())) {
            assertThat(cloned.getRepository().getBranch(), is("feature/mybranch"));
            assertThat(Files.exists(Path.of(result.directory()).resolve("feature.txt")), is(true));
        }
    }
}
