package com.eop.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.eop.TestcontainersConfig;
import java.time.Instant;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;

/**
 * The real risk of moving the BFF session to Redis (and the thing that silently breaks Phase 8's >=2
 * tasks if unproven): the Spring Security {@code SecurityContext} carrying the OIDC principal +
 * authorities must serialize into Redis and deserialize back intact on another task.
 *
 * <p>This stores an authenticated context through the actual configured {@link SessionRepository}
 * (default JDK serialization — all classes here are {@code Serializable}, and every task runs the
 * identical image so there is no class-skew risk), reads it back fresh from Redis, and asserts the
 * principal name, the OIDC claims, and the granted authorities all survive the round trip. If this ever
 * fails we switch the session serializer to {@code SecurityJackson2Modules} — decided cheaply here, not
 * discovered in Phase 8.
 */
@SpringBootTest
@ActiveProfiles("data")
@Import(TestcontainersConfig.class)
class RedisSessionRoundTripTest {

    @Autowired
    SessionRepository<? extends Session> sessions;

    @Test
    void security_context_with_oidc_principal_survives_redis_round_trip() {
        roundTrip(sessions);
    }

    // Generic helper so the wildcard repository's element type is captured (createSession/save/findById
    // must agree on one concrete Session type).
    private <S extends Session> void roundTrip(SessionRepository<S> repo) {
        Collection<GrantedAuthority> authorities =
                AuthorityUtils.createAuthorityList("APPROLE_ADMIN", "APPROLE_AUDITOR", "SCOPE_openid");

        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .issuer("https://login.microsoftonline.com/tenant/v2.0")
                .subject("user-123")
                .claim("name", "Test User")
                .build();
        OidcUser user = new DefaultOidcUser(authorities, idToken, "sub");
        var authentication = new OAuth2AuthenticationToken(user, authorities, "entra");
        SecurityContext context = new SecurityContextImpl(authentication);

        // Save under the standard Spring Security session attribute, through the real repository.
        S toSave = repo.createSession();
        toSave.setAttribute("SPRING_SECURITY_CONTEXT", context);
        repo.save(toSave);

        // Read it back — this deserializes from Redis (genuine cross-task round trip).
        S loaded = repo.findById(toSave.getId());
        assertThat(loaded).as("session must be retrievable from Redis").isNotNull();

        SecurityContext restored = loaded.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(restored).isNotNull();

        var restoredAuth = (OAuth2AuthenticationToken) restored.getAuthentication();
        assertThat(restoredAuth.getName()).isEqualTo("user-123");
        assertThat(restoredAuth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("APPROLE_ADMIN", "APPROLE_AUDITOR", "SCOPE_openid");

        OidcUser restoredUser = (OidcUser) restoredAuth.getPrincipal();
        assertThat(restoredUser.getClaimAsString("name")).isEqualTo("Test User");
        assertThat(restoredUser.getSubject()).isEqualTo("user-123");
    }
}
