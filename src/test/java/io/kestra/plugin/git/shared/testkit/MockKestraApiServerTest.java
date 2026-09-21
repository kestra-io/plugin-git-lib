package io.kestra.plugin.git.shared.testkit;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.flows.FlowWithSource;
import io.kestra.core.models.flows.GenericFlow;
import io.kestra.core.repositories.FlowRepositoryInterface;
import com.fasterxml.jackson.databind.JsonNode;
import io.kestra.core.serializers.JacksonMapper;
import jakarta.inject.Inject;
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

@KestraTest
public class MockKestraApiServerTest {
    private MockKestraApiServer server;
    private HttpClient client;
    @Inject
    private FlowRepositoryInterface flowRepository;
    private static final String TENANT_ID = "mock-kestra-api-server-test";

    @BeforeEach
    void startMockServer() throws IOException {
        flowRepository.findAllWithSource(TENANT_ID)
                      .forEach(f -> flowRepository.delete(f));
        server = MockKestraApiServer.start(flowRepository);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void stopMockServer() {
        try {
            client.close();
        } finally {
            server.close();
        }
    }

    @Test
    void getNamespace_shouldReturnRequestedNamespaceId() throws IOException, InterruptedException {
        String path = "/api/v1/" + TENANT_ID + "/namespaces/namespace";
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
    void validateFlows_shouldReturnNoErrorsForValidFlow() throws IOException, InterruptedException {
        String path = "/api/v1/" + TENANT_ID + "/flows/validate";
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

    @Test
    void validateFlows_shouldReturnValidationErrorForInvalidTaskType() throws IOException, InterruptedException {
        String path = "/api/v1/" + TENANT_ID + "/flows/validate";
        HttpRequest req = HttpRequest.newBuilder()
                                     .uri(URI.create(server.url() + path))
                                     .POST(HttpRequest.BodyPublishers.ofString("""
                                         id: id
                                         namespace: namespace

                                         tasks:
                                           - id: say
                                             type: io.kestra.plugin.core.log.Invalid
                                             message: hello
                                         """))
                                     .build();

        HttpResponse<String> resp =
            client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(resp.statusCode(), is(200));
        assertThat(resp.headers().firstValue("Content-Type").orElse(""), is("application/json"));
        assertThat(resp.body(), is("""
            [{"index":0,"constraints":"Invalid task type: unknown or unregistered plugin","flow":null,"namespace":null}]
            """.strip()));
    }

    @Test
    void validateFlows_shouldReturnValidationErrorForUnknownTaskType() throws IOException, InterruptedException {
        String path = "/api/v1/" + TENANT_ID + "/flows/validate";
        HttpRequest req = HttpRequest.newBuilder()
                                     .uri(URI.create(server.url() + path))
                                     .POST(HttpRequest.BodyPublishers.ofString("""
                                         id: id
                                         namespace: namespace

                                         tasks:
                                           - id: say
                                             type: unknown.
                                             message: hello
                                         """))
                                     .build();

        HttpResponse<String> resp =
            client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(resp.statusCode(), is(200));
        assertThat(resp.headers().firstValue("Content-Type").orElse(""), is("application/json"));
        assertThat(resp.body(), is("""
            [{"index":0,"constraints":"Invalid task type: unknown or unregistered plugin","flow":null,"namespace":null}]
            """.strip()));
    }

    @Test
    void getFlow_shouldReturnRequestedFlowWithSource() throws IOException, InterruptedException {
        String src = """
            id: id
            namespace: namespace

            tasks:
              - id: say
                type: io.kestra.plugin.core.log.Log
                message: hello
            """;
        GenericFlow flow = GenericFlow.fromYaml(TENANT_ID, src);
        FlowWithSource repository = flowRepository.create(flow);
        String path = "/api/v1/" + TENANT_ID + "/flows/namespace/id";
        HttpRequest req = HttpRequest.newBuilder()
                                     .uri(URI.create(server.url() + path))
                                     .GET()
                                     .build();

        HttpResponse<String> resp =
            client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(resp.statusCode(), is(200));
        assertThat(resp.headers().firstValue("Content-Type").orElse(""), is("application/json"));

        var mapper = JacksonMapper.ofJson();
        JsonNode root = mapper.readTree(resp.body());
        String id = root.get("id").asText();
        String namespace = root.get("namespace").asText();
        String source = root.get("source").asText();
        int revision = root.get("revision").asInt();

        assertThat(id, is("id"));
        assertThat(namespace, is("namespace"));
        assertThat(source, is(src));
        assertThat(revision, is(repository.getRevision()));
    }

    @Test
    void getFlow_shouldReturnNotFoundForMissingFlow() throws IOException, InterruptedException {
        String src = """
            id: id
            namespace: namespace

            tasks:
              - id: say
                type: io.kestra.plugin.core.log.Log
                message: hello
            """;
        GenericFlow flow = GenericFlow.fromYaml(TENANT_ID, src);
        flowRepository.create(flow);
        String path = "/api/v1/" + TENANT_ID + "/flows/namespace/di";
        HttpRequest req = HttpRequest.newBuilder()
                                     .uri(URI.create(server.url() + path))
                                     .GET()
                                     .build();

        HttpResponse<String> resp =
            client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(resp.statusCode(), is(404));
        assertThat(resp.body(), is(""));
    }

    @Test
    void getFlow_shouldReturnForcedStatusForRequestedFlow() throws IOException, InterruptedException {
        String src = """
            id: id
            namespace: namespace

            tasks:
              - id: say
                type: io.kestra.plugin.core.log.Log
                message: hello
            """;
        GenericFlow flow = GenericFlow.fromYaml(TENANT_ID, src);
        flowRepository.create(flow);
        server.forceGetFlowStatus("namespace", "id", 500);
        String path = "/api/v1/" + TENANT_ID + "/flows/namespace/id";
        HttpRequest req = HttpRequest.newBuilder()
                                     .uri(URI.create(server.url() + path))
                                     .GET()
                                     .build();

        HttpResponse<String> resp =
            client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(resp.statusCode(), is(500));
        assertThat(resp.body(), is(""));
    }

    @Test
    void importFlows_shouldCreateFlowFromRawYaml() throws IOException, InterruptedException {
        String src = """
            id: id
            namespace: namespace

            tasks:
              - id: say
                type: io.kestra.plugin.core.log.Log
                message: hello
            """;
        String path = "/api/v1/" + TENANT_ID + "/flows/import";
        HttpRequest req = HttpRequest.newBuilder()
                                     .uri(URI.create(server.url() + path))
                                     .POST(HttpRequest.BodyPublishers.ofString(src))
                                     .build();

        HttpResponse<String> resp =
            client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(resp.statusCode(), is(200));
        assertThat(resp.headers().firstValue("Content-Type").orElse(""), is("application/json"));
        assertThat(resp.body(), is("[]"));

        var match = flowRepository.findByNamespaceWithSource(TENANT_ID, "namespace").stream()
                                  .filter(f -> "id".equals(f.getId()))
                                  .findFirst();

        assertThat(match.isPresent(), is(true));

        var flow = match.get();
        assertThat(flow.getSource(), is(src.stripTrailing()));
    }

    @Test
    void importFlows_shouldUpdateExistingFlowFromRawYaml() throws IOException, InterruptedException {
        String src = """
            id: id
            namespace: namespace

            tasks:
              - id: say
                type: io.kestra.plugin.core.log.Log
                message: hello
            """;
        GenericFlow flow = GenericFlow.fromYaml(TENANT_ID, src);
        flowRepository.create(flow);
        String path = "/api/v1/" + TENANT_ID + "/flows/import";
        String updatedSource = """
            id: id
            namespace: namespace

            tasks:
              - id: say
                type: io.kestra.plugin.core.log.Log
                message: update
            """;
        HttpRequest req = HttpRequest.newBuilder()
                                     .uri(URI.create(server.url() + path))
                                     .POST(HttpRequest.BodyPublishers.ofString(updatedSource))
                                     .build();

        HttpResponse<String> resp =
            client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(resp.statusCode(), is(200));
        assertThat(resp.headers().firstValue("Content-Type").orElse(""), is("application/json"));
        assertThat(resp.body(), is("[]"));

        var match = flowRepository.findByNamespaceWithSource(TENANT_ID, "namespace").stream()
                                  .filter(f -> "id".equals(f.getId()))
                                  .findFirst();

        assertThat(match.isPresent(), is(true));

        var updatedFlow = match.get();
        assertThat(updatedFlow.getSource(), is(updatedSource.stripTrailing()));
    }

    @Test
    void importFlows_shouldCreateFlowFromMultipart() throws IOException, InterruptedException {
        String src = """
            id: id
            namespace: namespace

            tasks:
              - id: say
                type: io.kestra.plugin.core.log.Log
                message: hello
            """;
        String multipartBody = "--test-boundary\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"flow.yml\"\r\n"
            + "\r\n"
            + src + "\r\n" + "--test-boundary--\r\n";
        String path = "/api/v1/" + TENANT_ID + "/flows/import";
        HttpRequest req = HttpRequest.newBuilder()
                                     .uri(URI.create(server.url() + path))
                                     .header("Content-Type", "multipart/form-data; boundary=test-boundary")
                                     .POST(HttpRequest.BodyPublishers.ofString(multipartBody))
                                     .build();

        HttpResponse<String> resp =
            client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(resp.statusCode(), is(200));
        assertThat(resp.headers().firstValue("Content-Type").orElse(""), is("application/json"));
        assertThat(resp.body(), is("[]"));

        var match = flowRepository.findByNamespaceWithSource(TENANT_ID, "namespace").stream()
                                  .filter(f -> "id".equals(f.getId()))
                                  .findFirst();

        assertThat(match.isPresent(), is(true));

        var flow = match.get();
        assertThat(flow.getSource(), is(src.stripTrailing()));
    }

    @Test
    void deleteFlow_shouldDeleteRequestedFlowAndPreserveOtherFlow() throws IOException, InterruptedException {
        String src = """
            id: to-keep
            namespace: namespace

            tasks:
              - id: say
                type: io.kestra.plugin.core.log.Log
                message: hello
            """;
        GenericFlow flow = GenericFlow.fromYaml(TENANT_ID, src);
        flowRepository.create(flow);
        String path = "/api/v1/" + TENANT_ID + "/flows/namespace/to-delete";
        String sourceToDelete = """
            id: to-delete
            namespace: namespace

            tasks:
              - id: say
                type: io.kestra.plugin.core.log.Log
                message: hello
            """;
        GenericFlow flow2 = GenericFlow.fromYaml(TENANT_ID, sourceToDelete);
        flowRepository.create(flow2);
        HttpRequest req = HttpRequest.newBuilder()
                                     .uri(URI.create(server.url() + path))
                                     .DELETE()
                                     .build();

        HttpResponse<String> resp =
            client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(resp.statusCode(), is(200));
        assertThat(resp.body(), is(""));

        var match = flowRepository.findByNamespaceWithSource(TENANT_ID, "namespace").stream()
                                  .filter(f -> "to-keep".equals(f.getId()))
                                  .findFirst();

        var match2 = flowRepository.findByNamespaceWithSource(TENANT_ID, "namespace").stream()
                                   .filter(f -> "to-delete".equals(f.getId()))
                                   .findFirst();

        assertThat(match.isPresent(), is(true));
        assertThat(match2.isPresent(), is(false));
    }

    @Test
    void deleteFlow_shouldReturnSuccessForMissingFlow() throws IOException, InterruptedException {
        String src = """
            id: to-keep
            namespace: namespace

            tasks:
              - id: say
                type: io.kestra.plugin.core.log.Log
                message: hello
            """;
        GenericFlow flow = GenericFlow.fromYaml(TENANT_ID, src);
        flowRepository.create(flow);
        String path = "/api/v1/" + TENANT_ID + "/flows/namespace/to-delete";
        HttpRequest req = HttpRequest.newBuilder()
                                     .uri(URI.create(server.url() + path))
                                     .DELETE()
                                     .build();

        HttpResponse<String> resp =
            client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(resp.statusCode(), is(200));
        assertThat(resp.body(), is(""));

        var match = flowRepository.findByNamespaceWithSource(TENANT_ID, "namespace").stream()
                                  .filter(f -> "to-keep".equals(f.getId()))
                                  .findFirst();

        assertThat(match.isPresent(), is(true));
    }
}