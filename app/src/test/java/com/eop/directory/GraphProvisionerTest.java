package com.eop.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.eop.wif.WifAssertionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * The real Graph provisioner, exercised against a mocked Graph ({@link MockRestServiceServer}) and a
 * stubbed WIF token — no live tenant. Proves the find-or-create idempotency contract and the Graph
 * behaviours that matter: tag find-hit (reuse, no create), find-miss (create with the requestId tag),
 * 429/Retry-After backoff, opaque 403 (missing consent), and deterministic resolution of a >1 tag match.
 */
class GraphProvisionerTest {

    private final UUID requestId = UUID.fromString("11111111-2222-3333-4444-555555555555");
    // The requestId tag marker appears verbatim in the (URL-encoded) $filter query — match on it to avoid
    // brittleness around space encoding (t:t%20eq%20'...').
    private final String tagFilter = "eop:requestId:" + requestId;

    private record Fixture(GraphProvisioner provisioner, MockRestServiceServer server) {}

    private Fixture newFixture() {
        WifAssertionService wif = mock(WifAssertionService.class);
        when(wif.graphToken()).thenReturn("test-token");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // bindTo set the request factory on `builder`; GraphProvisioner builds its client from it.
        GraphProvisioner provisioner = new GraphProvisioner(wif, builder, "https://graph.microsoft.com/v1.0");
        return new Fixture(provisioner, server);
    }

    @Test
    void find_hit_reuses_the_existing_app_and_never_creates() {
        Fixture f = newFixture();
        f.server().expect(method(HttpMethod.GET)).andExpect(requestTo(containsString(tagFilter)))
                .andRespond(withSuccess(
                        "{\"value\":[{\"appId\":\"client-existing\",\"id\":\"obj-1\",\"createdDateTime\":\"2026-06-01T00:00:00Z\"}]}",
                        MediaType.APPLICATION_JSON));

        String clientId = f.provisioner().provision(requestId, "my-app");

        assertThat(clientId).isEqualTo("client-existing");
        f.server().verify(); // exactly one request — no POST
    }

    @Test
    void find_miss_creates_with_the_requestid_tag() {
        Fixture f = newFixture();
        f.server().expect(method(HttpMethod.GET)).andExpect(requestTo(containsString(tagFilter)))
                .andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
        f.server().expect(method(HttpMethod.POST)).andExpect(requestTo(containsString("/applications")))
                .andExpect(jsonPath("$.displayName").value("my-app"))
                .andExpect(jsonPath("$.signInAudience").value("AzureADMyOrg"))
                .andExpect(jsonPath("$.tags[0]").value("eop:requestId:" + requestId))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .body("{\"appId\":\"client-new\",\"id\":\"obj-2\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        String clientId = f.provisioner().provision(requestId, "my-app");

        assertThat(clientId).isEqualTo("client-new");
        f.server().verify();
    }

    @Test
    void throttled_create_backs_off_then_re_finds_and_succeeds() {
        Fixture f = newFixture();
        // Attempt 0: find empty, POST 429 (Retry-After: 0 → no real sleep).
        f.server().expect(method(HttpMethod.GET)).andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
        f.server().expect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "0"));
        // Attempt 1: re-find (atomic retry unit), still empty, POST 201.
        f.server().expect(method(HttpMethod.GET)).andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
        f.server().expect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .body("{\"appId\":\"client-after-429\"}").contentType(MediaType.APPLICATION_JSON));

        String clientId = f.provisioner().provision(requestId, "my-app");

        assertThat(clientId).isEqualTo("client-after-429");
        f.server().verify();
    }

    @Test
    void forbidden_surfaces_a_clear_consent_error() {
        Fixture f = newFixture();
        f.server().expect(method(HttpMethod.GET)).andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
        f.server().expect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> f.provisioner().provision(requestId, "my-app"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Application.ReadWrite.OwnedBy");
        f.server().verify();
    }

    @Test
    void duplicate_tag_match_is_resolved_deterministically_to_earliest() {
        Fixture f = newFixture();
        // Graph does not enforce tag uniqueness — two apps share the tag; the earlier createdDateTime wins.
        f.server().expect(method(HttpMethod.GET)).andRespond(withSuccess(
                "{\"value\":["
                        + "{\"appId\":\"client-late\",\"createdDateTime\":\"2026-06-10T00:00:00Z\"},"
                        + "{\"appId\":\"client-early\",\"createdDateTime\":\"2026-06-01T00:00:00Z\"}"
                        + "]}",
                MediaType.APPLICATION_JSON));

        String clientId = f.provisioner().provision(requestId, "my-app");

        assertThat(clientId).isEqualTo("client-early");
        f.server().verify();
    }
}
