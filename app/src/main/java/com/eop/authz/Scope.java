package com.eop.authz;

/**
 * The scope at which a role grants a permission.
 * <ul>
 *   <li>{@code ALL} — unrestricted (matrix {@code ✔}).</li>
 *   <li>{@code OWN} — only resources the principal owns (matrix {@code ✔(own)}); enforced by ABAC.</li>
 *   <li>{@code NONE} — not granted (matrix {@code —}).</li>
 * </ul>
 * Under multi-role union the <b>most permissive</b> scope wins: any {@code ALL} beats {@code OWN};
 * {@code OWN} applies only when every granting role is {@code OWN}.
 */
public enum Scope {
    NONE,
    OWN,
    ALL;

    /** Most-permissive combiner for the union of roles. */
    public Scope mostPermissive(Scope other) {
        return this.ordinal() >= other.ordinal() ? this : other;
    }
}
