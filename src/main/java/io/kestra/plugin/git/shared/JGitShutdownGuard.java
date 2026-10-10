package io.kestra.plugin.git.shared;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jgit.internal.util.CleanupService;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;

/**
 * Keeps JGit from releasing live index locks while a worker is still draining tasks.
 *
 * <p>{@code ShutdownHook} registers its own cleanup in its private enum constructor, through
 * {@code CleanupService.getInstance()}. The instance that {@code getInstance()} creates lazily is not in
 * OSGi mode, so that registration installs a real {@code Runtime} shutdown hook. Every {@code LockFile}
 * holds a listener that unlocks itself when that hook runs, so a {@code Clone}, {@code Fetch} or
 * {@code Checkout} still in flight loses its {@code .git/index} lock mid-write and dies with
 * {@code IllegalStateException: Lock on .../git/index not held}.
 *
 * <p>{@code CleanupService}'s public constructor installs an OSGi-mode singleton instead, and in that mode
 * {@code register} only stores the runnable: no {@code Runtime} hook is ever added. Doing this here rather
 * than in {@code plugin-git} or {@code plugin-ee-git} matters because each plugin classloader carries its
 * own copy of JGit, and this static state is per classloader.
 *
 * <p>The guard has to run before the first JGit call, which is why {@code CloneService.clone} and
 * {@code AbstractGitTask} both trigger it; a hook that JGit already handed to the JVM cannot be taken
 * back. The visible cost is that JGit no longer unlocks on shutdown either, so a JVM killed while an
 * operation holds the index can leave a stale {@code index.lock} behind for the next run in the same
 * directory. {@link #clearStaleIndexLock(Repository, Logger)} handles exactly that, and only for lock files
 * this process cannot own.
 */
public final class JGitShutdownGuard {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    /** Lower bound on any lock this process could have created, so an older lock is provably not ours. */
    private static final long PROCESS_START_MILLI = ManagementFactory.getRuntimeMXBean().getStartTime();

    private JGitShutdownGuard() {
    }

    /**
     * Switches JGit's cleanup service to OSGi mode so no JVM shutdown hook is registered for lock files.
     * Idempotent: only the first call creates an instance, later calls leave it alone.
     */
    public static void install() {
        if (INSTALLED.compareAndSet(false, true)) {
            new CleanupService();
        }
    }

    /**
     * Deletes a leftover {@code index.lock} that predates this process, which is what a shutdown-killed
     * clone leaves behind. A lock file created at or after process start is left untouched because a live
     * operation in this process may be holding it.
     */
    public static void clearStaleIndexLock(Repository repository, Logger logger) {
        Path lock = Path.of(repository.getIndexFile().getAbsolutePath() + Constants.LOCK_SUFFIX);
        if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (Files.getLastModifiedTime(lock, LinkOption.NOFOLLOW_LINKS).toMillis() < PROCESS_START_MILLI) {
                Files.deleteIfExists(lock);
                logger.info("Removed stale Git index lock '{}' left by an earlier run", lock);
            }
        } catch (IOException e) {
            logger.warn("Could not inspect the Git index lock '{}': {}", lock, e.getMessage());
        }
    }
}
