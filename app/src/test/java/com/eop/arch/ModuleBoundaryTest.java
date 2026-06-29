package com.eop.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Module-boundary guards. v0's modules ({@code auth}, {@code graph}, {@code wif}, {@code health}) are
 * cycle-free; as v1 adds {@code request}, {@code authz}, ... these rules fail the build the moment a
 * boundary leaks — a cycle, OR a one-way dependency the layering forbids (which a cycle check alone
 * misses, e.g. {@code request → graph}).
 */
@AnalyzeClasses(packages = "com.eop", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    @ArchTest
    static final ArchRule modules_are_free_of_cycles =
            slices().matching("com.eop.(*)..").should().beFreeOfCycles();

    /**
     * {@code authz} is the pure foundation — it depends on no other eop module (only the JDK + Spring).
     * Everything else may depend on it; it depends on nothing of ours.
     */
    @ArchTest
    static final ArchRule authz_is_a_pure_foundation =
            classes().that().resideInAPackage("com.eop.authz..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage("com.eop.authz..", "java..", "org.springframework..");

    /**
     * The {@code request} engine depends ONLY on {@code authz} + {@code platform} (plus the JDK and
     * framework) — never on a sibling module. The design note's architectural invariant, now guarded.
     */
    @ArchTest
    static final ArchRule request_depends_only_on_authz_and_platform =
            classes().that().resideInAPackage("com.eop.request..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage("com.eop.request..", "com.eop.authz..", "com.eop.platform..",
                            "java..", "jakarta..", "org.springframework..", "org.hibernate..",
                            "com.fasterxml..");

    /**
     * Only the type modules ({@code onboarding}, {@code review}, and Phase-5 {@code access}) may use the
     * request engine — so a future module can't read requests raw and bypass the controller-layer read
     * ABAC. Add {@code access} here when Phase 5 lands.
     */
    @ArchTest
    static final ArchRule only_type_modules_use_the_request_engine =
            classes().that().resideInAPackage("com.eop.request..")
                    .should().onlyHaveDependentClassesThat()
                    .resideInAnyPackage("com.eop.request..", "com.eop.onboarding..", "com.eop.review..");

    /** {@code onboarding} drives the engine + provisions via the directory port; no other module. */
    @ArchTest
    static final ArchRule onboarding_module_boundary =
            classes().that().resideInAPackage("com.eop.onboarding..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage("com.eop.onboarding..", "com.eop.request..", "com.eop.authz..",
                            "com.eop.platform..", "com.eop.directory..", "java..", "jakarta..",
                            "org.springframework..", "com.fasterxml..");

    /** {@code directory} is a leaf provisioning port — it depends on no other eop module. */
    @ArchTest
    static final ArchRule directory_is_a_leaf_port =
            classes().that().resideInAPackage("com.eop.directory..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage("com.eop.directory..", "java..", "org.slf4j..", "org.springframework..");
}
