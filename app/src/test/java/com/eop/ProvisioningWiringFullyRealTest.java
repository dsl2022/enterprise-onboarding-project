package com.eop;

import static org.assertj.core.api.Assertions.assertThat;

import com.eop.directory.AppRegistrationProvisioner;
import com.eop.directory.GraphGroupMembershipProvisioner;
import com.eop.directory.GraphProvisioner;
import com.eop.directory.GroupMembershipProvisioner;
import com.eop.wif.WifAssertionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The symmetric wiring guard for 5b: boots the context with BOTH verticals real
 * ({@code onboarding.simulate=false} AND {@code access.simulate=false}) and asserts both real provisioners
 * wire. This closes the symmetric form of the 4b shared-flag crash — a future flip of the access vertical
 * to real can never silently leave a vertical without its provisioner bean. (WIF is mocked, since
 * {@code wif.enabled} is off in tests; we assert WIRING, not Graph calls.)
 */
@SpringBootTest(properties = {
        "eop.provisioning.onboarding.simulate=false",
        "eop.provisioning.access.simulate=false"
})
@ActiveProfiles("data")
@Import(TestcontainersConfig.class)
class ProvisioningWiringFullyRealTest {

    @MockBean
    WifAssertionService wif;

    @Autowired
    AppRegistrationProvisioner appProvisioner;

    @Autowired
    GroupMembershipProvisioner groupProvisioner;

    @Test
    void both_verticals_real_context_wires() {
        assertThat(appProvisioner).isInstanceOf(GraphProvisioner.class);
        assertThat(groupProvisioner).isInstanceOf(GraphGroupMembershipProvisioner.class);
    }
}
