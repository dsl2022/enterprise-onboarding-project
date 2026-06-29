package com.eop.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eop.TestcontainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Contract tests for the onboarding HTTP surface across roles: create/list/get/patch/submit/decision/
 * timeline + review-queue, with ETag/If-Match, Idempotency-Key (replay + 422), read ABAC, and SoD.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("data")
@Import(TestcontainersConfig.class)
class OnboardingApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private static RequestPostProcessor login(String userId, String role) {
        Consumer<OidcIdToken.Builder> t = b -> b.subject(userId).claim("oid", userId)
                .claim("name", "User " + userId).claim("email", userId + "@eop").claim("roles", List.of(role));
        return oidcLogin().idToken(t);
    }

    private Application createApp(String owner, String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"env\":\"dev\",\"uris\":[\"https://app/cb\"]}";
        MvcResult r = mvc.perform(post("/api/v1/applications").with(login(owner, "APPLICATION_OWNER"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return json.readValue(r.getResponse().getContentAsString(), Application.class);
    }

    @Test
    void create_projects_payload_and_sets_etag() throws Exception {
        String body = "{\"name\":\"billing\",\"env\":\"dev\",\"uris\":[\"https://b/cb\"]}";
        MvcResult r = mvc.perform(post("/api/v1/applications").with(login("owner-1", "APPLICATION_OWNER"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(r.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
        Application app = json.readValue(r.getResponse().getContentAsString(), Application.class);
        assertThat(app.status()).isEqualTo("DRAFT");
        assertThat(app.owner()).isEqualTo("owner-1");
        assertThat(app.redirectUris()).containsExactly("https://b/cb"); // uris → redirectUris
        assertThat(app.clientId()).isNull();
    }

    @Test
    void create_is_idempotent_replay_and_422_on_key_reuse_with_different_body() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"name\":\"idem\",\"env\":\"dev\"}";
        MvcResult first = mvc.perform(post("/api/v1/applications").with(login("owner-1", "APPLICATION_OWNER"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String firstId = json.readValue(first.getResponse().getContentAsString(), Application.class).id();

        // Replay: same key + same body → original response (same id), no duplicate.
        MvcResult replay = mvc.perform(post("/api/v1/applications").with(login("owner-1", "APPLICATION_OWNER"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json.readValue(replay.getResponse().getContentAsString(), Application.class).id()).isEqualTo(firstId);

        // Same key + different body → 422.
        mvc.perform(post("/api/v1/applications").with(login("owner-1", "APPLICATION_OWNER"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"different\",\"env\":\"dev\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void read_abac_owner_sees_only_own_admin_sees_all() throws Exception {
        Application a = createApp("owner-a", "app-a");

        // A different owner cannot read it (app.read is own-scoped).
        mvc.perform(get("/api/v1/applications/" + a.id()).with(login("owner-b", "APPLICATION_OWNER")))
                .andExpect(status().isForbidden());
        // Ops (app.read = all) can.
        mvc.perform(get("/api/v1/applications/" + a.id()).with(login("ops-1", "SSO_OPERATIONS")))
                .andExpect(status().isOk());
    }

    @Test
    void patch_merges_and_enforces_if_match() throws Exception {
        Application a = createApp("owner-1", "patchme");

        // Stale If-Match → 412.
        mvc.perform(patch("/api/v1/applications/" + a.id()).with(login("owner-1", "APPLICATION_OWNER"))
                .header("If-Match", "\"99\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"x\"}"))
                .andExpect(status().isPreconditionFailed());

        // Correct If-Match → merge (description set; name/env preserved).
        MvcResult r = mvc.perform(patch("/api/v1/applications/" + a.id()).with(login("owner-1", "APPLICATION_OWNER"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"now described\"}"))
                .andExpect(status().isOk()).andReturn();
        Application patched = json.readValue(r.getResponse().getContentAsString(), Application.class);
        assertThat(patched.description()).isEqualTo("now described");
        assertThat(patched.name()).isEqualTo("patchme"); // immutable, preserved
        assertThat(r.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    }

    @Test
    void submit_then_decision_lifecycle_with_sod_and_review_queue() throws Exception {
        Application a = createApp("owner-1", "lifecycle");

        // Submit (owner) → UNDER_REVIEW.
        MvcResult sub = mvc.perform(post("/api/v1/applications/" + a.id() + "/submit").with(login("owner-1", "APPLICATION_OWNER"))
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"0\""))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readValue(sub.getResponse().getContentAsString(), Application.class).status()).isEqualTo("UNDER_REVIEW");

        // Review queue: ops sees it, owner is forbidden (review.read).
        mvc.perform(get("/api/v1/review-queue").with(login("ops-1", "SSO_OPERATIONS"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/review-queue").with(login("owner-1", "APPLICATION_OWNER"))).andExpect(status().isForbidden());

        // SoD: the owner who submitted cannot decide (needs decide perm too, but SoD blocks regardless).
        mvc.perform(post("/api/v1/applications/" + a.id() + "/decision").with(login("owner-1", "APPLICATION_OWNER"))
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"2\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());

        // Ops approves → APPROVED.
        MvcResult dec = mvc.perform(post("/api/v1/applications/" + a.id() + "/decision").with(login("ops-1", "SSO_OPERATIONS"))
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", "\"2\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readValue(dec.getResponse().getContentAsString(), Application.class).status()).isEqualTo("APPROVED");

        // Timeline visible to the owner.
        mvc.perform(get("/api/v1/applications/" + a.id() + "/timeline").with(login("owner-1", "APPLICATION_OWNER")))
                .andExpect(status().isOk());
    }
}
