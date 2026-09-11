package io.kestra.plugin.git.shared.services;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.git.shared.AbstractGitTask;

import lombok.AllArgsConstructor;

import static org.eclipse.jgit.lib.Constants.R_HEADS;

@AllArgsConstructor
public class GitService {
    private static final Pattern SSH_URL_PATTERN = Pattern.compile("git@(?:ssh\\.)?([^:]+):(?:v\\d*/)?(.*)");

    private AbstractGitTask gitTask;

    public Git cloneBranch(RunContext runContext, String branch, Property<Boolean> withSubmodules) throws Exception {
        boolean branchExists = this.branchExists(runContext, branch);

        CloneService.CloneRequest request = CloneService.CloneRequest.builder()
            .url(runContext.render(gitTask.getUrl()).as(String.class).orElse(null))
            .path(runContext.workingDir().path())
            .branch(branchExists ? branch : null)
            .depth(1)
            .cloneAllBranches(true)
            .noTags(false)
            .cloneSubmodules(withSubmodules)
            .build();

        if (!branchExists) {
            runContext.logger().info("Branch {} does not exist, creating it", branch);
        }

        CloneService.clone(runContext, gitTask, request);

        Git git = Git.open(runContext.workingDir().path().toFile());

        gitTask.applyGitConfig(git.getRepository(), runContext);

        if (!branchExists && git.getRepository().resolve(Constants.HEAD) != null) {
            git.checkout()
                .setName(branch)
                .setCreateBranch(true)
                .call();
        }

        return git;
    }

    public boolean branchExists(RunContext runContext, String branch) throws Exception {
        return gitTask.authentified(Git.lsRemoteRepository().setRemote(runContext.render(gitTask.getUrl()).as(String.class).orElse(null)), runContext)
            .callAsMap()
            .containsKey(R_HEADS + branch);
    }

    /**
     * Fails when {@code branch} is not present on the remote, so a read/sync operation never silently falls back to
     * the repository's default branch (see {@code cloneBranch}, which would otherwise create the missing branch from
     * the default HEAD). No-op when {@code failOnMissing} is false or {@code branch} is null/blank (no branch requested
     * means the default branch is intended).
     */
    public void ensureBranchExistsOrFail(RunContext runContext, String branch, boolean failOnMissing) throws Exception {
        if (!failOnMissing || branch == null || branch.isBlank()) {
            return;
        }

        if (!branchExists(runContext, branch)) {
            throw new IllegalArgumentException(
                String.format(
                    "Branch '%s' does not exist on repository '%s'. Git sync tasks never create a missing branch, " +
                        "because that silently reads from the repository's default branch instead. Create the branch " +
                        "on the remote, fix the `branch` property, or set `failOnMissingBranch` to false to allow the " +
                        "fallback.",
                    branch,
                    runContext.render(gitTask.getUrl()).as(String.class).orElse(null)
                )
            );
        }
    }

    public String getHttpUrl(String gitUrl) {
        String httpUrl = gitUrl;
        // SSH URL
        Matcher sshUrlMatcher = SSH_URL_PATTERN.matcher(httpUrl);
        if (sshUrlMatcher.matches()) {
            httpUrl = sshUrlMatcher.group(1) + "/" + sshUrlMatcher.group(2);

            if (httpUrl.contains("azure.com")) {
                int orgFromProjectSeparatorIndex = httpUrl.lastIndexOf("/");
                httpUrl = httpUrl.substring(0, orgFromProjectSeparatorIndex) + "/_git/" + httpUrl.substring(orgFromProjectSeparatorIndex + 1);
            }

            httpUrl = "https://" + httpUrl;
        } else if (httpUrl.contains("@")) {
            httpUrl = httpUrl.replaceFirst("//.*@", "//");
        }

        return httpUrl;
    }

    public void namespaceAccessGuard(RunContext runContext, Property<String> namespaceToAccess) throws IllegalVariableEvaluationException {
        String namespace = runContext.render(namespaceToAccess).as(String.class).orElse(null);
        if (namespace != null && !namespace.isBlank()) {
            runContext.acl().allowNamespaces(List.of(namespace)).check();
        }
    }
}
