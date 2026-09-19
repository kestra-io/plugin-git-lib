package io.kestra.plugin.git.shared.testkit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class MockKestraApiServerTest {
    private MockKestraApiServer server;
    private HttpClient client;

    @BeforeEach
    void startMockServer() throws IOException {
        server = MockKestraApiServer.start(null);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void stopMockServer() {
        client.close();
        server.close();
    }

    @Test
    void getNamespace_shouldReturnRequestedNamespaceId() throws IOException, InterruptedException {
        String path = "/api/v1/tenant/namespaces/namespace";
        HttpRequest req = HttpRequest.newBuilder()
                                     .uri(URI.create(server.url() + path))
                                     .GET()
                                     .build();

        HttpResponse<String> resp =
            client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(resp.statusCode(), is(200));
        assertThat(resp.headers().firstValue("Content-Type").orElse(""), is("application/json"));
        assertThat(resp.body(), is("{\"id\":\"namespace\"}"));
    }

    @Test
    void postValid_shouldReturnValidationResultWithoutError() throws IOException, InterruptedException {
        String path = "/api/v1/tenant/flows/validate";
        HttpRequest req = HttpRequest.newBuilder()
                                     .uri(URI.create(server.url() + path))
                                     .POST(HttpRequest.BodyPublishers.ofString("""
                                         id: id
                                         namespace: namespace

                                         tasks:
                                           - id: say
                                             type: io.kestra.plugin.core.log.Log
                                             message: hello
                                         """))
                                     .build();

        HttpResponse<String> resp =
            client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(resp.statusCode(), is(200));
        assertThat(resp.headers().firstValue("Content-Type").orElse(""), is("application/json"));
        assertThat(resp.body(), is("""
                [{"index":0,"constraints":null,"flow":null,"namespace":null}]
                """.strip()));
    }
}
