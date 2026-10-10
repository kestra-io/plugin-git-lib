package io.kestra.plugin.git.shared;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.util.CleanupService;
import org.eclipse.jgit.lib.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class JGitShutdownGuardTest {
    @Test
    void installKeepsJgitCleanupInOsgiMode() throws Exception {
        JGitShutdownGuard.install();

        CleanupService service = CleanupService.getInstance();
        assertTrue(isOsgi(service),
            "JGit must not register a Runtime shutdown hook, otherwise a draining worker unlocks a live index lock");

        JGitShutdownGuard.install();
        assertSame(service, CleanupService.getInstance(), "install is idempotent");
    }

    @Test
    void clearStaleIndexLockRemovesOnlyALockThisProcessCannotOwn(@TempDir Path workDir) throws Exception {
        Path reused = Files.createDirectory(workDir.resolve("reused"));
        try (Git git = Git.init().setDirectory(reused.toFile()).call()) {
            Path indexLock = lockOf(git);
            Files.writeString(indexLock, "left behind by a killed run");
            Files.setLastModifiedTime(indexLock, FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)));

            JGitShutdownGuard.clearStaleIndexLock(git.getRepository(), LoggerFactory.getLogger(JGitShutdownGuardTest.class));

            assertFalse(Files.exists(indexLock), "a lock older than this process cannot be ours and must not block the next run");
        }

        Path live = Files.createDirectory(workDir.resolve("live"));
        try (Git git = Git.init().setDirectory(live.toFile()).call()) {
            Path indexLock = lockOf(git);
            Files.writeString(indexLock, "held by an operation in this process");

            JGitShutdownGuard.clearStaleIndexLock(git.getRepository(), LoggerFactory.getLogger(JGitShutdownGuardTest.class));

            assertTrue(Files.exists(indexLock), "a lock created after process start may be live and must be left alone");
        }
    }

    @Test
    void guardedProcessLeavesALiveIndexLockAloneWhileJgitWouldUnlockIt(@TempDir Path workDir) throws Exception {
        Path unguarded = workDir.resolve("unguarded");
        Path guarded = workDir.resolve("guarded");

        assertFalse(runChildHoldingIndexLock(unguarded, false),
            "reproduces #23: JGit's own JVM shutdown hook unlocks an index lock a running task still holds");
        assertTrue(runChildHoldingIndexLock(guarded, true),
            "with the guard installed no shutdown hook is registered, so an in-flight operation keeps its lock");
    }

    /**
     * Launches the child through a java argument file rather than a long command line, so the test also
     * works where the command line is limited, and kills it if it stops making progress.
     */
    private static boolean runChildHoldingIndexLock(Path workDir, boolean guarded) throws Exception {
        Path argFile = Files.createTempFile("jgit-shutdown-guard-child-", ".options");
        try {
            String classPath = System.getProperty("java.class.path").replace('\\', '/');
            Files.write(argFile, List.of(
                "-cp",
                "\"" + classPath + "\"",
                IndexLockHolder.class.getName(),
                workDir.toAbsolutePath().toString().replace('\\', '/'),
                Boolean.toString(guarded)));
            String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process child = new ProcessBuilder(javaBinary, "@" + argFile.toAbsolutePath())
                .redirectErrorStream(true)
                .start();
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!child.waitFor(2, TimeUnit.MINUTES)) {
                child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                fail("the child JVM never finished: " + output);
            }
            assertEquals(0, child.exitValue(), "the child JVM failed: " + output);
            return Files.exists(workDir.resolve(Constants.DOT_GIT).resolve("index" + Constants.LOCK_SUFFIX));
        } finally {
            Files.deleteIfExists(argFile);
        }
    }

    /** Opens a repository, locks its index and exits without unlocking, exactly like a task caught by a SIGTERM. */
    public static class IndexLockHolder {
        public static void main(String[] args) throws Exception {
            if (Boolean.parseBoolean(args[1])) {
                JGitShutdownGuard.install();
            }
            try (Git git = Git.init().setDirectory(Path.of(args[0]).toFile()).call()) {
                git.getRepository().lockDirCache();
                System.exit(0);
            }
        }
    }

    private static Path lockOf(Git git) {
        return Path.of(git.getRepository().getIndexFile().getAbsolutePath() + Constants.LOCK_SUFFIX);
    }

    private static boolean isOsgi(CleanupService service) throws Exception {
        Field field = CleanupService.class.getDeclaredField("isOsgi");
        field.setAccessible(true);
        return (boolean) field.get(service);
    }
}
