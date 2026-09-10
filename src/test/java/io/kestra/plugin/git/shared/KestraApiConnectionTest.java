package io.kestra.plugin.git.shared;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.runners.SDK;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.git.shared.TestTasks.TestCloningTask;
import io.kestra.plugin.git.shared.TestTasks.TestKestraTask;
import io.kestra.sdk.KestraClient;
import io.kestra.sdk.internal.ApiClient;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The API URL comes from the same default SDK authentication as the credentials, so one instance-wide setting covers both. */
@KestraTest
class KestraApiConnectionTest {
    private static final String DEFAULT_URL = "http://localhost:8080";
    private static final String SDK_DEFAULT_URL = "https://sdk-default.example.com";

    @Inject
    private RunContextFactory runContextFactory;

    /**
     * The default authentication normally comes from the application configuration, but declaring it with
     * {@code @Property} would spawn a second Micronaut context, and the two embedded servers then fight over the same
     * port. Swapping the SDK on the run context keeps the whole suite on a single context.
     */
    private RunContext runContextWithSdkUrl(Task task, String url) throws Exception {
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        SDK sdk = () -> Optional.of(new SDK.Auth(Optional.of(url), Optional.empty(), Optional.empty(), Optional.empty()));
        Field sdkField = runContext.getClass().getDeclaredField("sdk");
        sdkField.setAccessible(true);
        sdkField.set(runContext, sdk);

        return runContext;
    }

    /** Reads the URL the client was built with (the SDK only exposes it on its inner API client). */
    private static String url(KestraClient client) throws Exception {
        Field apiClientField = KestraClient.class.getDeclaredField("apiClient");
        apiClientField.setAccessible(true);
        Object apiClient = apiClientField.get(client);

        return (String) apiClient.getClass().getMethod("getBasePath").invoke(apiClient);
    }

    /** Reads the `Authorization` header the client was built with, to tell Basic auth apart from Bearer/token auth. */
    private static String authorizationHeader(KestraClient client) throws Exception {
        Field apiClientField = KestraClient.class.getDeclaredField("apiClient");
        apiClientField.setAccessible(true);
        ApiClient apiClient = (ApiClient) apiClientField.get(client);

        return apiClient.getDefaultHeaders().get("Authorization");
    }

    /**
     * Same helper as {@link #runContextWithSdkUrl}, but resolving a default SDK authentication that carries both a
     * username/password pair and an API token, to assert which one wins.
     */
    private RunContext runContextWithSdkBasicAndTokenAuth(Task task, String username, String password, String apiToken) throws Exception {
        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        SDK sdk = () -> Optional.of(new SDK.Auth(Optional.empty(), Optional.of(apiToken), Optional.of(username), Optional.of(password)));
        Field sdkField = runContext.getClass().getDeclaredField("sdk");
        sdkField.setAccessible(true);
        sdkField.set(runContext, sdk);

        return runContext;
    }

    private static TestCloningTask.TestCloningTaskBuilder<?, ?> cloningTask() {
        return TestCloningTask.builder()
            .id("clone")
            .type(TestCloningTask.class.getName())
            .url(Property.ofValue("https://github.com/kestra-io/plugin-git"));
    }

    @Test
    void shouldUseTheDefaultSdkAuthenticationUrlWhenTheTaskDoesNotSetOne() throws Exception {
        var task = cloningTask().build();

        assertThat(url(task.kestraClient(runContextWithSdkUrl(task, SDK_DEFAULT_URL))), is(SDK_DEFAULT_URL));
    }

    @Test
    void shouldUseTheDefaultSdkAuthenticationUrlForKestraApiTasks() throws Exception {
        var task = TestKestraTask.builder()
            .id("kestraTask")
            .type(TestKestraTask.class.getName())
            .url(Property.ofValue("https://github.com/kestra-io/plugin-git"))
            .auth(AbstractKestraTask.Auth.builder().apiToken(Property.ofValue("token")).build())
            .build();

        assertThat(url(task.kestraClient(runContextWithSdkUrl(task, SDK_DEFAULT_URL))), is(SDK_DEFAULT_URL));
    }

    @Test
    void shouldPreferTheTaskUrlOverTheDefaultSdkAuthenticationUrl() throws Exception {
        var task = cloningTask().kestraUrl(Property.ofValue("https://task.example.com/")).build();

        assertThat(url(task.kestraClient(runContextWithSdkUrl(task, SDK_DEFAULT_URL))), is("https://task.example.com"));
    }

    @Test
    void shouldFallThroughWhenTheDefaultSdkAuthenticationUrlIsBlank() throws Exception {
        var task = cloningTask().build();

        assertThat(url(task.kestraClient(runContextWithSdkUrl(task, "   "))), is(DEFAULT_URL));
    }

    @Test
    void shouldIgnoreTheDefaultSdkAuthenticationUrlWhenAutoIsDisabled() throws Exception {
        var task = cloningTask()
            .auth(AbstractCloningTask.Auth.builder().auto(Property.ofValue(false)).build())
            .build();

        assertThat(url(task.kestraClient(runContextWithSdkUrl(task, SDK_DEFAULT_URL))), is(DEFAULT_URL));
    }

    /**
     * Unlike {@code AbstractKestraTask} (whose {@code auth} property is mandatory and throws when nothing
     * resolves, see {@code shouldFailWhenAutoIsDisabledWithoutCredentials} below), {@code AbstractCloningTask}'s
     * {@code auth} is optional and currently still returns an unauthenticated client when no credential source
     * resolves. Some existing OSS flows point {@code Clone}/{@code Push*}/{@code Sync*}/{@code NamespaceSync} at a
     * Kestra API that does not require authentication and rely on this; aligning it with the strict
     * {@code AbstractKestraTask} behavior is a deliberate follow-up decision, not part of this change.
     */
    @Test
    void cloningTask_stillReturnsAnUnauthenticatedClientWhenNoAuthenticationResolves() throws Exception {
        var task = cloningTask().build();

        assertThat(task.kestraClient(runContextFactory.of()), notNullValue());
    }

    /**
     * A task family that must never silently proceed unauthenticated (e.g. the Enterprise Edition's
     * {@code NamespaceSync}) overrides {@code requireKestraAuthentication()} instead of relying on the lenient
     * OSS-oriented default.
     */
    @Test
    void cloningTask_failsFastWhenRequireKestraAuthenticationIsOverridden() {
        var task = new TestCloningTask() {
            @Override
            protected boolean requireKestraAuthentication() {
                return true;
            }
        };

        var e = assertThrows(IllegalArgumentException.class, () -> task.kestraClient(runContextFactory.of()));

        assertThat(
            e.getMessage(),
            is("No authentication method provided. Set 'auth.apiToken', or 'auth.username' and 'auth.password', or configure a default one with the 'kestra.tasks.sdk.authentication' properties.")
        );
    }

    /**
     * When the default SDK authentication resolves both a username/password pair and an API token (e.g. an
     * instance-wide config where both happen to be set), Basic auth must win, matching the long-standing
     * OSS {@code AbstractCloningTask.kestraClient()} behavior this default branch was extracted from.
     */
    @Test
    void shouldPreferBasicAuthOverApiTokenInTheDefaultSdkAuthentication() throws Exception {
        var task = cloningTask().build();
        var runContext = runContextWithSdkBasicAndTokenAuth(task, "default-user", "default-pass", "default-token");

        assertThat(authorizationHeader(task.kestraClient(runContext)), startsWith("Basic "));
    }

    /** The SDK builder defaults to Basic auth, so building a client without credentials would send `Basic base64("null:null")`. */
    @Test
    void shouldFailWhenAutoIsDisabledWithoutCredentials() throws Exception {
        var task = TestKestraTask.builder()
            .id("kestraTask")
            .type(TestKestraTask.class.getName())
            .url(Property.ofValue("https://github.com/kestra-io/plugin-git"))
            .auth(AbstractKestraTask.Auth.builder().auto(Property.ofValue(false)).build())
            .build();

        RunContext runContext = runContextWithSdkUrl(task, SDK_DEFAULT_URL);

        assertThrows(IllegalArgumentException.class, () -> task.kestraClient(runContext));
    }
}
