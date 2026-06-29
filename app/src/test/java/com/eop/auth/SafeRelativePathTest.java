package com.eop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SafeRelativePathTest {

    @Test
    void acceptsSameOriginRelativePaths() {
        assertThat(SafeRelativePath.isValid("/app")).isTrue();
        assertThat(SafeRelativePath.isValid("/app/dashboard")).isTrue();
        assertThat(SafeRelativePath.isValid("/app/review-queue?type=access")).isTrue();
    }

    @Test
    void rejectsOpenRedirectVectors() {
        assertThat(SafeRelativePath.isValid(null)).isFalse();
        assertThat(SafeRelativePath.isValid("")).isFalse();
        assertThat(SafeRelativePath.isValid("app/dashboard")).isFalse(); // not absolute
        assertThat(SafeRelativePath.isValid("//evil.com")).isFalse(); // protocol-relative
        assertThat(SafeRelativePath.isValid("https://evil.com")).isFalse();
        assertThat(SafeRelativePath.isValid("/\\evil.com")).isFalse();
        assertThat(SafeRelativePath.isValid("/path/with://embedded")).isFalse();
    }
}
