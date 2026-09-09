package io.kestra.plugin.git.shared.services;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.git.shared.AbstractGitTask;

import lombok.Builder;

/**
 * Plain jgit clone/checkout helper, independent of any {@code RunnableTask}.
 *
 * <p>Both {@link GitService#cloneBranch} and the {@code Clone} task of each plugin repository (plugin-git,
 * plugin-ee-git) delegate to this class, so the clone/checkout logic exists in a single place instead of the
 * {@code GitService} building a concrete {@code Clone} task instance (which would re-introduce a dependency from
 * this shared library onto a class that must stay in each plugin repository).
 */
public final class CloneService {

    private CloneService() {
    }

    /** Plain request object describing a clone operation; not a {@code RunnableTask}. */
    @Builder
    public record CloneRequest(
        String url,
        Path path,
        String branch,
        Integer depth,
        String commit,
        String tag,
        boolean cloneAllBranches,
        boolean noTags,
        Property<Boolean> cloneSubmodules
    ) {
    }

    public record CloneResult(String directory) {
    }

    /**
     * Clones {@code request.url()} into {@code request.path()}, authenticating and applying git config through
     * {@code gitTask}. Falls back to an init+fetch+checkout strategy when the target directory is already
     * non-empty (e.g. populated by a preceding {@code WorkingDirectory} task's input files).
     */
    public static CloneResult clone(RunContext runContext, AbstractGitTask gitTask, CloneRequest request) throws Exception {
        Logger logger = runContext.logger();
        boolean hasCommit = request.commit() != null;
        boolean hasTag = request.tag() != null;

        logger.info("Start cloning from '{}'", request.url());

        try {
            if (isNonEmptyDirectory(request.path())) {
                logger.info("Target directory '{}' is not empty, using init+fetch strategy", request.path());
                return initFetchCheckout(runContext, gitTask, logger, request, hasCommit, hasTag);
            }
            return cloneRepository(runContext, gitTask, logger, request, hasCommit, hasTag);
        } catch (TransportException e) {
            logger.error("Git clone failed for '{}': {}", request.url(), e.getMessage());
            throw e;
        }
    }

    private static CloneResult cloneRepository(RunContext runContext, AbstractGitTask gitTask, Logger logger, CloneRequest request, boolean hasCommit, boolean hasTag) throws Exception {
        Files.createDirectories(request.path());

        CloneCommand cloneCommand = Git.cloneRepository()
            .setURI(request.url())
            .setDirectory(request.path().toFile());

        if (!hasCommit && !hasTag) {
            if (request.branch() != null) {
                cloneCommand.setBranch(request.branch());
            }
            if (request.depth() != null) {
                cloneCommand.setDepth(request.depth());
            }
        }

        if (request.cloneSubmodules() != null) {
            cloneCommand.setCloneSubmodules(runContext.render(request.cloneSubmodules()).as(Boolean.class).orElseThrow());
        }

        cloneCommand.setCloneAllBranches(request.cloneAllBranches());

        if (!request.cloneAllBranches() && request.branch() != null) {
            cloneCommand.setBranchesToClone(List.of(normalizeBranchRef(shortBranchName(request.branch()))));
        }

        if (request.noTags()) {
            cloneCommand.setNoTags();
        }

        cloneCommand = gitTask.authentified(cloneCommand, runContext);

        try (var git = cloneCommand.call()) {
            gitTask.applyGitConfig(git.getRepository(), runContext);
            postCloneCheckout(runContext, gitTask, git, logger, request, hasCommit, hasTag);

            return new CloneResult(git.getRepository().getDirectory().getParent());
        }
    }

    private static void postCloneCheckout(RunContext runContext, AbstractGitTask gitTask, Git git, Logger logger, CloneRequest request, boolean hasCommit, boolean hasTag) throws Exception {
        if (hasCommit) {
            checkoutCommit(git, request.commit(), logger, request.noTags());
        } else if (hasTag) {
            checkoutTag(git, request.tag(), logger, request.noTags());
        } else if (request.branch() != null) {
            checkoutBranch(git, request.branch(), logger);
        }
    }

    /**
     * Clones into a non-empty directory using git init + fetch + checkout.
     * This is the standard Git workaround when the target directory already contains files
     * (e.g. from WorkingDirectory inputFiles).
     */
    private static CloneResult initFetchCheckout(RunContext runContext, AbstractGitTask gitTask, Logger logger, CloneRequest request, boolean hasCommit, boolean hasTag) throws Exception {
        try (var git = Git.init().setDirectory(request.path().toFile()).call()) {
            git.remoteAdd()
                .setName("origin")
                .setUri(new URIish(request.url()))
                .call();

            gitTask.applyGitConfig(git.getRepository(), runContext);

            List<RefSpec> refSpecs = new ArrayList<>();

            if (!request.cloneAllBranches() && request.branch() != null) {
                String branchName = shortBranchName(request.branch());
                refSpecs.add(new RefSpec("+refs/heads/" + branchName + ":refs/remotes/origin/" + branchName));
            } else {
                refSpecs.add(new RefSpec("+refs/heads/*:refs/remotes/origin/*"));
            }

            if (!request.noTags()) {
                refSpecs.add(new RefSpec("+refs/tags/*:refs/tags/*"));
            }

            FetchCommand fetchCommand = git.fetch()
                .setRemote("origin")
                .setRefSpecs(refSpecs);

            if (request.noTags()) {
                fetchCommand.setTagOpt(TagOpt.NO_TAGS);
            }

            if (!hasCommit && !hasTag && request.depth() != null) {
                fetchCommand.setDepth(request.depth());
            }

            gitTask.authentified(fetchCommand, runContext).call();

            if (hasCommit) {
                checkoutCommit(git, request.commit(), logger, request.noTags());
            } else if (hasTag) {
                checkoutTag(git, request.tag(), logger, request.noTags());
            } else {
                // Resolve the target branch; fall back to detecting the remote HEAD default branch
                var targetBranch = request.branch();
                if (targetBranch == null) {
                    var headRef = git.getRepository().exactRef("refs/remotes/origin/HEAD");
                    if (headRef != null && headRef.getTarget() != null) {
                        targetBranch = headRef.getTarget().getName().replace("refs/remotes/origin/", "");
                    }
                }

                // If still null, try common defaults
                if (targetBranch == null) {
                    if (git.getRepository().exactRef("refs/remotes/origin/main") != null) {
                        targetBranch = "main";
                    } else if (git.getRepository().exactRef("refs/remotes/origin/master") != null) {
                        targetBranch = "master";
                    } else {
                        throw new IllegalStateException(
                            "Cannot determine the default branch. Please specify the 'branch' property explicitly."
                        );
                    }
                }

                var remoteBranch = "origin/" + targetBranch;

                git.checkout()
                    .setName(targetBranch)
                    .setCreateBranch(true)
                    .setStartPoint(remoteBranch)
                    .call();

                logger.info("Checked out branch {} from {}", targetBranch, remoteBranch);
            }

            if (request.cloneSubmodules() != null && runContext.render(request.cloneSubmodules()).as(Boolean.class).orElse(false)) {
                git.submoduleInit().call();
                gitTask.authentified(git.submoduleUpdate(), runContext).call();
            }

            return new CloneResult(git.getRepository().getDirectory().getParent());
        }
    }

    public static void checkoutCommit(Git git, String sha, Logger logger, boolean noTags) throws Exception {
        // Ensure we have a full history in case the repo was shallow by default on the remote
        // or if the requested SHA is deep in history.
        try {
            var refSpecs = new ArrayList<>(List.of(new RefSpec("+refs/heads/*:refs/remotes/origin/*")));
            if (!noTags) {
                refSpecs.add(new RefSpec("+refs/tags/*:refs/tags/*"));
            }

            var fetch = git.fetch().setRefSpecs(refSpecs);
            if (noTags) {
                fetch.setTagOpt(TagOpt.NO_TAGS);
            }

            fetch.call();
        } catch (Exception fetchEx) {
            logger.warn("Fetch before checkout failed: {}", fetchEx.getMessage());
        }

        ObjectId target;
        try {
            target = git.getRepository().resolve(sha);
            if (target == null) {
                throw new IllegalArgumentException("Cannot resolve commit SHA: " + sha);
            }
            git.checkout().setName(target.name()).call();
            logger.info("Checked out commit {} (detached HEAD)", target.name());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to checkout commit " + sha + ": " + e.getMessage(), e);
        }
    }

    public static void checkoutTag(Git git, String rTagName, Logger logger, boolean noTags) throws Exception {
        if (!noTags) {
            git.fetch()
                .setRefSpecs(new RefSpec("+refs/tags/*:refs/tags/*"))
                .call();
        }

        Ref tagRef = git.getRepository().findRef("refs/tags/" + rTagName);
        if (tagRef == null) {
            throw new IllegalArgumentException("Cannot resolve tag: " + rTagName);
        }

        ObjectId commitId;
        try (RevWalk revWalk = new RevWalk(git.getRepository())) {
            RevObject object = revWalk.parseAny(tagRef.getObjectId());

            if (object instanceof RevTag revTag) {
                // we have Annotated tag, need to peel to commit
                commitId = revTag.getObject();
            } else {
                // we have Lightweight tag which already points to commit
                commitId = object;
            }
        }

        git.checkout().setName(commitId.name()).call();

        logger.info("Checked out tag {} at commit {}", rTagName, commitId.name());
    }

    public static void checkoutBranch(Git git, String branch, Logger logger) throws Exception {
        if (branch != null) {
            git.checkout().setName(branch).call();
            logger.info("Checked out branch {}", branch);
        }
    }

    /**
     * Returns true if the path exists, is a directory, and contains at least one entry.
     */
    private static boolean isNonEmptyDirectory(Path path) {
        var dir = path.toFile();
        if (!dir.exists() || !dir.isDirectory()) {
            return false;
        }
        var entries = dir.list();
        return entries != null && entries.length > 0;
    }

    private static String normalizeBranchRef(String branch) {
        if (branch == null || branch.isBlank()) {
            return branch;
        }
        return branch.startsWith("refs/heads/") ? branch : "refs/heads/" + branch;
    }

    private static String shortBranchName(String branch) {
        if (branch == null) {
            return null;
        }
        return branch.startsWith("refs/heads/") ? branch.substring("refs/heads/".length()) : branch;
    }
}
