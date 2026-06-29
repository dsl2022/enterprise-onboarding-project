package com.eop.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import java.util.function.Consumer;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.util.StringUtils;

/**
 * Flow 1 BFF security.
 *
 * <p>The {@code auth} profile (set on ECS via SPRING_PROFILES_ACTIVE) turns on the OIDC login chain
 * against Entra. Without it (local dev / CI docker build), a permissive chain lets the app boot with no
 * Entra config so {@code /healthz} answers. Tokens live in the server-side session; the browser only
 * gets the session cookie.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final String appBaseUrl;

    public SecurityConfig(@Value("${app.base-url:}") String appBaseUrl) {
        this.appBaseUrl = appBaseUrl;
    }

    /** Active in the `auth` profile: real Entra login + protected API. */
    @Bean
    @Profile("auth")
    SecurityFilterChain authChain(HttpSecurity http, ClientRegistrationRepository repo) throws Exception {
        var apiMatcher = new OrRequestMatcher(
                new AntPathRequestMatcher("/api/**"),
                new AntPathRequestMatcher("/auth/me"));

        http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/healthz", "/error").permitAll()
                        .requestMatchers("/api/**", "/auth/me").authenticated()
                        .anyRequest().permitAll())
                .oauth2Login(o -> o
                        .authorizationEndpoint(e -> e.authorizationRequestResolver(pkceResolver(repo)))
                        .successHandler(returnToSuccessHandler()))
                // RP-initiated logout: clear the local session AND redirect to Entra's
                // end_session_endpoint so the IdP session is dropped too (real sign-out).
                .logout(l -> l
                        .logoutRequestMatcher(new AntPathRequestMatcher("/auth/logout", "GET"))
                        .logoutSuccessHandler(oidcLogoutSuccessHandler(repo)))
                // fetch() calls to the API get 401 instead of a cross-origin redirect to Entra.
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), apiMatcher))
                // Throwaway GET-only API + demo UI; the OAuth2 flow has its own state/PKCE protection.
                .csrf(c -> c.disable());
        return http.build();
    }

    // After local logout, send the browser to Entra to end the IdP session, then back to the app home.
    // The post-logout URI must be registered on the Entra app (see modules/entra redirect_uris).
    private LogoutSuccessHandler oidcLogoutSuccessHandler(ClientRegistrationRepository repo) {
        var handler = new OidcClientInitiatedLogoutSuccessHandler(repo);
        handler.setPostLogoutRedirectUri(StringUtils.hasText(appBaseUrl) ? appBaseUrl + "/" : "{baseUrl}/");
        return handler;
    }

    // On login success, redirect to the same-origin `returnTo` the SPA stashed at /auth/login
    // (so a sign-in started from /app lands back on /app), falling back to "/" otherwise. Replaces
    // the old defaultSuccessUrl("/", true). Re-validated here as defense in depth against open redirects.
    private AuthenticationSuccessHandler returnToSuccessHandler() {
        return (request, response, authentication) -> {
            String target = "/";
            HttpSession session = request.getSession(false);
            if (session != null) {
                Object stashed = session.getAttribute(SafeRelativePath.RETURN_TO_SESSION_ATTR);
                session.removeAttribute(SafeRelativePath.RETURN_TO_SESSION_ATTR);
                if (stashed instanceof String path && SafeRelativePath.isValid(path)) {
                    target = path;
                }
            }
            response.sendRedirect(target);
        };
    }

    // Force PKCE even though this is a confidential client (defense in depth; brief requires PKCE), AND
    // force a credential prompt on every sign-in (prompt=login). RP-initiated logout clears the BFF SESSION
    // but Entra's own browser SSO session survives, so the next /oauth2/authorization/entra would otherwise
    // silently re-issue a token — wrong for the shared demo Super-Admin account handed to a reviewer on a
    // shared machine. prompt=login guarantees the password prompt regardless of IdP session state (#149).
    private OAuth2AuthorizationRequestResolver pkceResolver(ClientRegistrationRepository repo) {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(repo, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(authorizationRequestCustomizer());
        return resolver;
    }

    /** PKCE (code_challenge) + {@code prompt=login}, composed. Package-private so it's unit-testable (#149). */
    static Consumer<OAuth2AuthorizationRequest.Builder> authorizationRequestCustomizer() {
        Consumer<OAuth2AuthorizationRequest.Builder> pkce = OAuth2AuthorizationRequestCustomizers.withPkce();
        return b -> {
            pkce.accept(b);                                        // keep PKCE (code_challenge still emitted)
            b.additionalParameters(p -> p.put("prompt", "login")); // always prompt for credentials
        };
    }

    /** Default (no `auth` profile): permit everything so local/CI boots without Entra. */
    @Bean
    @Profile("!auth")
    SecurityFilterChain openChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(c -> c.disable());
        return http.build();
    }
}
