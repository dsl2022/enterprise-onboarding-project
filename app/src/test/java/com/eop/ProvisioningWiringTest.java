package com.eop;

import static org.assertj.core.api.Assertions.assertThat;

import com.eop.directory.AppRegistrationProvisioner;
import com.eop.directory.GraphProvisioner;
import com.eop.directory.GroupMembershipProvisioner;
import com.eop.directory.SimulatedGroupProvisioner;
import com.eop.wif.WifAssertionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Deployment-wiring guard. Boots the full context in the exact deploy combination that crash-looped the
 * task: <b>onboarding real (simulate=false), access still simulated</b>. Unit/Testcontainers tests run with
 * the {@code matchIfMissing=true} simulated defaults, so {@code simulate=false} wiring was never exercised —
 * the shared {@code eop.provisioning.simulate} flag could disable the access simulator (whose real impl is
 * 5b) and leave {@code AccessProvisioningService} with an unsatisfied {@code GroupMembershipProvisioner},
 * failing context init only on a real deploy. Per-vertical flags fix it; this test catches the class.
 *
 * <p>{@link GraphProvisioner} needs the WIF token bean (normally {@code wif.enabled=true}-gated, off in
 * tests), so it's mocked — we assert WIRING, not Graph calls.
 */
@SpringBootTest(properties = {
        "eop.provisioning.onboarding.simulate=false", // real onboarding (the state that crashed)
        "eop.provisioning.access.simulate=true"        // access still simulated (5b not built)
})
@ActiveProfiles("data")
@Import(TestcontainersConfig.class)
class ProvisioningWiringTest {

    @MockBean
    WifAssertionService wif;

    @Autowired
    AppRegistrationProvisioner appProvisioner;

    @Autowired
    GroupMembershipProvisioner groupProvisioner;

    @Test
    void onboarding_real_access_simulated_context_wires() {
        // Context started (no UnsatisfiedDependencyException) AND the right beans are active per vertical.
        assertThat(appProvisioner).isInstanceOf(GraphProvisioner.class);
        assertThat(groupProvisioner).isInstanceOf(SimulatedGroupProvisioner.class);
    }
}
