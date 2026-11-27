package io.kestra.plugin.git.shared;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import javax.net.ssl.SSLHandshakeException;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.SDK;
import io.kestra.sdk.KestraClient;

import jakarta.annotation.Nullable;

/**
 * The Kestra API endpoint a task talks to, together with the default SDK authentication it was resolved from.
 *
 * <p>
 * Both are read from the same place, so that an instance, tenant or namespace default covers the URL as well as
 * the credentials.
 */
final class KestraApiConnection {
    private static final String DEFAULT_URL = "http://localhost:8080";
    private static final String UNPRINTABLE_URL = "the configured URL";
    private static final String URL_TEMPLATE = "{{ kestra.url }}";
    private static final String NO_AUTH_MESSAGE = "No authentication method provided. Set 'auth.apiToken', or " +
        "'auth.username' and 'auth.password', or configure a default one with the 'kestra.tasks.sdk.authentication' properties. " +
        "If this API requires no authentication, set 'auth.auto' to false and leave the credentials unset.";

    private final String url;
    private final DefaultAuthSupplier defaultAuth;

    private KestraApiConnection(String url, DefaultAuthSupplier defaultAuth) {
        this.url = url;
        this.defaultAuth = defaultAuth;
    }

    /** The API URL, without any trailing slash. */
    String url() {
        return url;
    }

    /** The default SDK authentication, empty when the task opted out of it with {@code auth.auto}. */
    Optional<SDK.Auth> defaultAuth() {
        return defaultAuth.get();
    }

    /** Whether the default SDK authentication may be used, i.e. {@code auth.auto} is on. */
    boolean auto() {
        return defaultAuth.enabled;
    }

    /**
     * Builds a {@link KestraClient} for the given task {@code kestraUrl}/{@code auth} properties.
     *
     * <p>{@code auth.auto} chooses where the credentials are read from, not whether the call is authenticated.
     * Together with whether the task sets a credential of its own, it expresses the three supported scenarios:
     *
     * <ul>
     *   <li><b>Authenticated with the task's own credentials</b> — {@code auth.apiToken}, or {@code auth.username}
     *       and {@code auth.password}, set on the task. They always win, whatever {@code auth.auto} is set to.</li>
     *   <li><b>Authenticated from the Kestra configuration</b> — {@code auth.auto} on (the default) and no
     *       credential on the task. The default SDK authentication is used, which an administrator sets through the
     *       {@code kestra.tasks.sdk.authentication} properties or, on the Enterprise Edition, at the tenant or the
     *       namespace level. It carries the API URL as well as the credentials.</li>
     *   <li><b>Unauthenticated</b> — {@code auth.auto} off and no credential on the task. Turning {@code auth.auto}
     *       off means "use only what this task declares"; declaring nothing therefore means calling the API with no
     *       credentials at all, so the client is built with {@code noAuth()} and sends no {@code Authorization}
     *       header. This is the only way to reach a Kestra API that requires none.</li>
     * </ul>
     *
     * <p>The fourth combination — {@code auth.auto} on, no credential on the task, and nothing configured for the
     * instance to fall back on — is a configuration error rather than a scenario. A task family that requires
     * authentication ({@code requireAuthentication}) then fails with {@link #NO_AUTH_MESSAGE} instead of quietly
     * calling unauthenticated, since nothing in the flow said that was the intent.
     *
     * <p>{@code requireAuthentication} is what separates the two families in that last case:
     * {@code AbstractKestraTask} (whose {@code auth} property is mandatory) throws. {@code AbstractCloningTask}
     * (whose {@code auth} property is optional, used by {@code Clone}/{@code Push*}/{@code Sync*}/
     * {@code NamespaceSync}) preserves its long-standing behavior of returning an unauthenticated client, since
     * some deployments legitimately point these tasks at a Kestra API that does not require authentication and
     * existing flows rely on that. Tracked for a follow-up decision on whether to align it with the strict
     * behavior.
     *
     * <p>{@code family} carries the two ways the two hierarchies have always differed and must keep differing
     * (see {@link KestraTaskFamily}): which credential wins in the default SDK authentication when it carries both
     * a token and Basic credentials, and how strictly the explicit mutual-exclusion is enforced.
     */
    static KestraClient buildClient(RunContext runContext, @Nullable Property<String> kestraUrl, @Nullable KestraApiAuth auth, boolean requireAuthentication, KestraTaskFamily family) throws IllegalVariableEvaluationException {
        KestraApiConnection connection = resolve(runContext, kestraUrl, auth);
        runContext.logger().debug("Kestra URL: {}", safeUrl(connection.url()));

        var builder = KestraClient.builder().url(connection.url());

        // AbstractKestraTask historically rejected a declared-but-blank apiToken next to username/password instead
        // of silently falling through to Basic; AbstractCloningTask never had that rule, so it stays rendered-value based below.
        if (family == KestraTaskFamily.KESTRA_API && auth != null
            && auth.getApiToken() != null && (auth.getUsername() != null || auth.getPassword() != null)) {
            throw new IllegalArgumentException("Cannot use both API Token authentication and HTTP Basic authentication");
        }

        Optional<String> maybeApiToken = auth == null ? Optional.empty() : runContext.render(auth.getApiToken()).as(String.class);
        Optional<String> maybeUsername = auth == null ? Optional.empty() : runContext.render(auth.getUsername()).as(String.class);
        Optional<String> maybePassword = auth == null ? Optional.empty() : runContext.render(auth.getPassword()).as(String.class);

        if (family == KestraTaskFamily.CLONING && maybeApiToken.isPresent() && (maybeUsername.isPresent() || maybePassword.isPresent())) {
            throw new IllegalArgumentException("Cannot use both API Token authentication and HTTP Basic authentication");
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

        Optional<SDK.Auth> autoAuth = connection.defaultAuth();
        if (autoAuth.isPresent()) {
            // The order below is the only difference between the two families' default-auth resolution: restoring
            // the pre-extraction precedence for each is the entire point of this branch (see KestraTaskFamily).
            if (family == KestraTaskFamily.KESTRA_API) {
                if (autoAuth.get().apiToken().isPresent()) {
                    return builder.tokenAuth(autoAuth.get().apiToken().get()).build();
                }
                if (autoAuth.get().username().isPresent() && autoAuth.get().password().isPresent()) {
                    return builder.basicAuth(autoAuth.get().username().get(), autoAuth.get().password().get()).build();
                }
            } else {
                if (autoAuth.get().username().isPresent() && autoAuth.get().password().isPresent()) {
                    return builder.basicAuth(autoAuth.get().username().get(), autoAuth.get().password().get()).build();
                }
                if (autoAuth.get().apiToken().isPresent()) {
                    return builder.tokenAuth(autoAuth.get().apiToken().get()).build();
                }
            }
        }

        if (requireAuthentication && connection.auto()) {
            throw new IllegalArgumentException(NO_AUTH_MESSAGE);
        }

        return builder.noAuth().build();
    }

    /** Wraps a TLS handshake failure with a Kestra API hint; returns {@code error} otherwise. */
    static RuntimeException explainApiError(RunContext runContext, @Nullable Property<String> kestraUrl, @Nullable KestraApiAuth auth, RuntimeException error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof UntrustedCertificateException) {
                return error;
            }
            if (cause instanceof SSLHandshakeException) {
                return new UntrustedCertificateException(
                    "Could not connect to the Kestra API at " + urlForMessage(runContext, kestraUrl, auth) + " because the TLS handshake failed, "
                        + "most often because its server certificate is not trusted by the JVM truststore. "
                        + "This is the call to the Kestra API, not to the Git remote. Set `kestraUrl` to a URL whose certificate is trusted, "
                        + "or add the issuing CA to the JVM truststore (`-Djavax.net.ssl.trustStore` in `JAVA_OPTS`). Cause: "
                        + Optional.ofNullable(cause.getMessage()).orElse(cause.getClass().getName()),
                    error
                );
            }
        }
        return error;
    }

    /**
     * The API URL for an error message, without any user info. Never throws: it runs while another error is being
     * reported, which a rendering or configuration-lookup failure here must not replace.
     */
    private static String urlForMessage(RunContext runContext, @Nullable Property<String> kestraUrl, @Nullable KestraApiAuth auth) {
        String url;
        try {
            url = resolve(runContext, kestraUrl, auth).url();
        } catch (Exception e) {
            return UNPRINTABLE_URL;
        }
        return safeUrl(url);
    }

    /**
     * {@code scheme://host[:port]/path} of {@code url}, for logs and messages: user info, query and fragment are never
     * printed. A URL that does not parse with a host (e.g. a raw {@code /}, {@code ?} or {@code #} in the password
     * moves the authority boundary) is replaced entirely, since no part of it can be told apart from the user info.
     */
    static String safeUrl(@Nullable String url) {
        if (url == null) {
            return null;
        }
        try {
            var uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return UNPRINTABLE_URL;
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + Optional.ofNullable(uri.getRawPath()).orElse("");
        } catch (URISyntaxException e) {
            return UNPRINTABLE_URL;
        }
    }

    /** Marks an error already explained by {@link #explainApiError}, so a second call does not wrap it again. */
    static final class UntrustedCertificateException extends IllegalStateException {
        private UntrustedCertificateException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Resolves the API URL from, in order: the task's own {@code kestraUrl}, the default SDK authentication
     * (namespace then tenant then instance configuration, on the Enterprise Edition), the {@code kestra.url}
     * configuration property, and finally {@code http://localhost:8080}.
     *
     * <p>{@code auth.auto} opts out of the default authentication for the URL as well as for the credentials.
     */
    private static KestraApiConnection resolve(RunContext runContext, @Nullable Property<String> kestraUrl, @Nullable KestraApiAuth auth) throws IllegalVariableEvaluationException {
        boolean rAuto = runContext.render(Optional.ofNullable(auth).map(KestraApiAuth::getAuto).orElse(null))
            .as(Boolean.class)
            .orElse(Boolean.TRUE);
        DefaultAuthSupplier defaultAuth = new DefaultAuthSupplier(runContext, rAuto);

        String rUrl = runContext.render(kestraUrl).as(String.class)
            .filter(Predicate.not(String::isBlank))
            .or(() -> defaultAuth.get().flatMap(SDK.Auth::url).filter(Predicate.not(String::isBlank)))
            .orElseGet(() -> configuredUrl(runContext));

        return new KestraApiConnection(rUrl.trim().replaceAll("/+$", ""), defaultAuth);
    }

    private static String configuredUrl(RunContext runContext) {
        try {
            String rUrl = runContext.render(URL_TEMPLATE);
            return rUrl == null || rUrl.isBlank() ? DEFAULT_URL : rUrl;
        } catch (IllegalVariableEvaluationException e) {
            return DEFAULT_URL;
        }
    }

    /** Looks the defaults up at most once, since on the Enterprise Edition it reads the namespace and tenant metastores. */
    private static final class DefaultAuthSupplier {
        private final RunContext runContext;
        private final boolean enabled;
        private Optional<SDK.Auth> resolved;

        private DefaultAuthSupplier(RunContext runContext, boolean enabled) {
            this.runContext = runContext;
            this.enabled = enabled;
        }

        private Optional<SDK.Auth> get() {
            if (resolved == null) {
                SDK sdk = enabled ? runContext.sdk() : null;
                resolved = Optional.ofNullable(sdk).map(SDK::defaultAuthentication).orElse(Optional.empty());
            }
            return resolved;
        }
    }
}
