package com.eop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

/**
 * #149: the authorization request must force a credential prompt ({@code prompt=login}) so the shared demo
 * Super-Admin account re-authenticates after sign-out, WITHOUT dropping PKCE. Pure unit test of the composed
 * customizer — no Spring context, no Entra config.
 */
class AuthorizationRequestCustomizerTest {

    @Test
    void adds_prompt_login_and_keeps_pkce() {
        var builder = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://login.microsoftonline.com/tenant/oauth2/v2.0/authorize")
                .clientId("client-id")
                .redirectUri("https://app.example/login/oauth2/code/entra")
                .state("state");

        SecurityConfig.authorizationRequestCustomizer().accept(builder);
        OAuth2AuthorizationRequest request = builder.build();

        // belt-and-suspenders re-auth
        assertThat(request.getAdditionalParameters()).containsEntry("prompt", "login");
        // PKCE survived the composition (code_challenge still on the wire)
        assertThat(request.getAdditionalParameters())
                .containsKey(PkceParameterNames.CODE_CHALLENGE)
                .containsEntry(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256");
    }
}
