package com.eop.platform;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the public intro/landing page at the clean URL {@code /intro}, forwarding to the
 * self-contained static page {@code static/intro.html} — mirroring how {@code /} serves
 * {@code static/index.html}. It is fully public (no auth): SecurityConfig ends with
 * {@code anyRequest().permitAll()}, and {@code /intro} is not under {@code /api/**} or
 * {@code /auth/me}. Reachable via CloudFront's default behaviour (→ the BFF/ALB), independent
 * of the {@code /app} S3 origin.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/intro").setViewName("forward:/intro.html");
    }
}
