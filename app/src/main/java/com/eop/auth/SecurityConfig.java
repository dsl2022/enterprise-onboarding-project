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
import org.springframework.security.web.SecurityFilterChain;
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
                        .defaultSuccessUrl("/", true))
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

    // Force PKCE even though this is a confidential client (defense in depth; brief requires PKCE).
    private OAuth2AuthorizationRequestResolver pkceResolver(ClientRegistrationRepository repo) {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(repo, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
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
