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

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        assertEquals(200, resp.statusCode(), "should return 200");
        assertEquals(
            "application/json",
            resp.headers().firstValue("Content-Type").orElse(""),
            "should return Content-Type application/json"
        );
        assertEquals(
            "{\"id\":\"namespace\"}",
            resp.body(),
            "should return requested namespace id"
        );
    }
}
