package io.kestra.plugin.git.shared.services;

import java.util.Optional;

import io.kestra.core.models.flows.FlowWithSource;
import io.kestra.sdk.KestraClient;
import io.kestra.sdk.internal.ApiException;

/**
 * Shared single-flow lookup used by the flow-sync tasks to resolve whether a flow already exists in the
 * target Kestra instance. A 404 unambiguously means the flow does not exist yet and is returned as an empty
 * {@link Optional}. Any other API failure, including status 0 (no HTTP response at all, e.g. a connection,
 * DNS or TLS failure), propagates the {@link ApiException} so callers never mistake a real failure for an
 * absent flow. Each caller owns its own policy for that exception.
 */
public final class FlowLookupService {

    private FlowLookupService() {
    }

    /**
     * @param withSource whether to fetch the flow source; pass {@code false} when only the revision is needed
     * @return the existing flow, or empty when the API returns 404
     * @throws ApiException on any non-404 failure, so the caller can decide whether to fail or tolerate it
     */
    public static Optional<FlowWithSource> fetch(KestraClient kestraClient, String tenantId, String namespace, String flowId, boolean withSource) throws ApiException {
        try {
            var apiFlow = kestraClient.flows().flow(namespace, flowId, tenantId, withSource, null, false);
            return Optional.of(FlowWithSource.builder()
                .id(apiFlow.getId())
                .namespace(apiFlow.getNamespace())
                .revision(apiFlow.getRevision())
                .tenantId(tenantId)
                .source(apiFlow.getSource())
                .build());
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
