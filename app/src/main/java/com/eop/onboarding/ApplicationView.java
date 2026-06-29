package com.eop.onboarding;

/** Internal: the projected {@link Application} plus its version, so the controller can set the ETag. */
public record ApplicationView(Application application, int version) {
}
