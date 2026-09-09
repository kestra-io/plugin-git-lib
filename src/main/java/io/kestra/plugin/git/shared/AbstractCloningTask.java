package io.kestra.plugin.git.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.SDK;
import io.kestra.sdk.KestraClient;
import io.kestra.sdk.internal.ApiException;
import io.kestra.sdk.model.PagedResultsNamespace;
import io.kestra.sdk.model.QueryFilter;
import io.kestra.sdk.model.QueryFilterField;
import io.kestra.sdk.model.QueryFilterOp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@Getter
public abstract class AbstractCloningTask extends AbstractGitTask {
    @Schema(
        title = "Kestra API URL",
        description = """
            URL of the Kestra server API.
            If not set, the URL of the default SDK authentication is used, set with the `kestra.tasks.sdk.authentication.url` \
            configuration property, or at the namespace or the tenant level on the Enterprise Edition.
            It then falls back to the `kestra.url` configuration property, and finally to `http://localhost:8080`."""
    )
    @PluginProperty(group = "connection")
    protected Property<String> kestraUrl;

    @Schema(
        title = "Clone submodules",
        description = "Default false; enable to fetch and initialize nested submodules."
    )
    @PluginProperty(group = "advanced")
    protected Property<Boolean> cloneSubmodules;

    @Schema(title = "Kestra API authentication")
    @PluginProperty(group = "connection")
    protected Auth auth;

    protected KestraClient kestraClient(RunContext runContext) throws IllegalVariableEvaluationException {
        KestraApiConnection connection = KestraApiConnection.resolve(runContext, kestraUrl, auth);

        runContext.logger().debug("Kestra URL: {}", connection.url());

        var builder = KestraClient.builder().url(connection.url());

        if (auth != null) {
            Optional<String> maybeApiToken = runContext.render(auth.apiToken).as(String.class);
            Optional<String> maybeUsername = runContext.render(auth.username).as(String.class);
            Optional<String> maybePassword = runContext.render(auth.password).as(String.class);
            if (maybeApiToken.isPresent() && (maybeUsername.isPresent() || maybePassword.isPresent())) {
                throw new IllegalArgumentException("Use either apiToken or username/password for authentication, not both");
            }
            if (maybeApiToken.isPresent()) {
                return builder.tokenAuth(maybeApiToken.get()).build();
            }
            if (maybeUsername.isPresent() && maybePassword.isPresent()) {
                return builder.basicAuth(maybeUsername.get(), maybePassword.get()).build();
            }
            if (maybeUsername.isPresent() || maybePassword.isPresent()) {
                throw new IllegalArgumentException("Both username and password are required for HTTP Basic authentication");
            }
        }

        Optional<SDK.Auth> autoAuth = connection.defaultAuth();
        if (autoAuth.isPresent()) {
            if (autoAuth.get().username().isPresent() && autoAuth.get().password().isPresent()) {
                return builder.basicAuth(autoAuth.get().username().get(), autoAuth.get().password().get()).build();
            }
            if (autoAuth.get().apiToken().isPresent()) {
                return builder.tokenAuth(autoAuth.get().apiToken().get()).build();
            }
        }

        return builder.build();
    }

    protected List<String> descendantNamespaces(RunContext runContext, String tenantId, String namespace) throws IllegalVariableEvaluationException, ApiException {
        var client = kestraClient(runContext);
        List<String> out = new ArrayList<>();
        int page = 1;
        int size = 200;
        List<io.kestra.sdk.model.Namespace> results;
        do {
            // QUERY/EQUALS is a server-side contains filter, so prefix matches are never missed
            PagedResultsNamespace result = client.namespaces().searchNamespaces(
                tenantId,
                page,
                size,
                null,
                false,
                List.of(new QueryFilter().field(QueryFilterField.QUERY).operation(QueryFilterOp.EQUALS).value(namespace + "."))
            );
            results = result.getResults();
            if (results == null) {
                break;
            }
            results.forEach(ns ->
            {
                if (isDescendant(namespace, ns.getId())) {
                    out.add(ns.getId());
                }
            });
            page++;
        } while (results.size() == size);
        return out;
    }

    protected static boolean isDescendant(String rootNamespace, String namespace) {
        return namespace != null && namespace.startsWith(rootNamespace + ".");
    }

    @Builder
    @Getter
    @Jacksonized
    public static class Auth implements KestraApiAuth {
        @Schema(title = "Username for HTTP Basic authentication.")
        @PluginProperty(secret = true, group = "connection")
        private Property<String> username;

        @Schema(title = "Password for HTTP Basic authentication.")
        @PluginProperty(secret = true, group = "connection")
        private Property<String> password;

        @Schema(title = "API token for authentication.")
        @PluginProperty(secret = true, group = "connection")
        private Property<String> apiToken;

        @Schema(
            title = "Automatically retrieve the URL and the credentials from Kestra's configuration if available",
            description = """
                Can be configured globally in the Kestra configuration file:
                - Set `kestra.tasks.sdk.authentication.url` for the API URL
                - Set `kestra.tasks.sdk.authentication.api-token` for API token auth
                - Set `kestra.tasks.sdk.authentication.username` and `kestra.tasks.sdk.authentication.password` for HTTP Basic auth
                The Enterprise Edition also allows an administrator to set these defaults at the namespace or the tenant level."""
        )
        @Builder.Default
        @PluginProperty(group = "advanced")
        private Property<Boolean> auto = Property.ofValue(Boolean.TRUE);
    }
}
