package com.eop.arch;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Module-boundary guard, scaffolded in Phase 2 so every later module inherits it. v0's modules
 * ({@code auth}, {@code graph}, {@code wif}, {@code health}) are already cycle-free; as v1 adds
 * {@code request}, {@code authz}, {@code onboarding}, ... this rule fails the build the moment two
 * modules form a dependency cycle — the first symptom of a leaked boundary.
 */
@AnalyzeClasses(packages = "com.eop", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    @ArchTest
    static final ArchRule modules_are_free_of_cycles =
            slices().matching("com.eop.(*)..").should().beFreeOfCycles();
}
