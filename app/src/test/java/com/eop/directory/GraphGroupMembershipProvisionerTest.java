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

import com.eop.wif.WifAssertionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * The real group-membership provisioner against a mocked Graph + stubbed WIF token. Proves the
 * idempotency-by-specific-signal contract: add-already-member (400 "already exist") and remove-not-member
 * (404) succeed; a bad-group 404 on add surfaces; 403 is a distinct loud consent error; 429 backs off.
 */
class GraphGroupMembershipProvisionerTest {

    private static final String ALREADY_EXISTS =
            "{\"error\":{\"code\":\"Request_BadRequest\",\"message\":\"One or more added object references "
            + "already exist for the following modified properties: 'members'.\"}}";

    private record Fixture(GraphGroupMembershipProvisioner provisioner, MockRestServiceServer server) {}

    private Fixture newFixture() {
        WifAssertionService wif = mock(WifAssertionService.class);
        when(wif.graphToken()).thenReturn("test-token");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new GraphGroupMembershipProvisioner(wif, builder, "https://graph.microsoft.com/v1.0"), server);
    }

    @Test
    void add_member_posts_oid_ref_and_returns_marker() {
        Fixture f = newFixture();
        f.server().expect(method(HttpMethod.POST)).andExpect(requestTo(containsString("/groups/g1/members/$ref")))
                .andExpect(jsonPath("$.['@odata.id']").value(containsString("directoryObjects/u1")))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThat(f.provisioner().addMember("g1", "u1")).isEqualTo("g1:u1");
        f.server().verify();
    }

    @Test
    void add_already_member_is_idempotent_success() {
        Fixture f = newFixture();
        f.server().expect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body(ALREADY_EXISTS));

        assertThat(f.provisioner().addMember("g1", "u1")).isEqualTo("g1:u1"); // no throw
        f.server().verify();
    }

    @Test
    void add_to_bad_group_surfaces_404() {
        Fixture f = newFixture();
        f.server().expect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> f.provisioner().addMember("bad-group", "u1"))
                .isInstanceOf(HttpClientErrorException.class); // NOT swallowed as success
        f.server().verify();
    }

    @Test
    void add_403_is_a_distinct_consent_error() {
        Fixture f = newFixture();
        f.server().expect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> f.provisioner().addMember("g1", "u1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GroupMember.ReadWrite.All");
        f.server().verify();
    }

    @Test
    void add_throttled_then_succeeds() {
        Fixture f = newFixture();
        f.server().expect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "0"));
        f.server().expect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThat(f.provisioner().addMember("g1", "u1")).isEqualTo("g1:u1");
        f.server().verify();
    }

    @Test
    void remove_member_deletes_the_ref() {
        Fixture f = newFixture();
        f.server().expect(method(HttpMethod.DELETE))
                .andExpect(requestTo(containsString("/groups/g1/members/u1/$ref")))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        f.provisioner().removeMember("g1", "u1"); // no throw
        f.server().verify();
    }

    @Test
    void remove_not_member_is_idempotent_success() {
        Fixture f = newFixture();
        f.server().expect(method(HttpMethod.DELETE)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        f.provisioner().removeMember("g1", "u1"); // 404 → treated as already-removed, no throw
        f.server().verify();
    }
}
