package com.eop.wif;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.eop.TestcontainersConfig;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

/**
 * Phase 8 / #77 — the HA landmine: with ≥2 tasks cold-starting, the issuer-key acquisition must be
 * SINGLE-WRITER, or each task generates a different key (last-write-wins on the secret + the published
 * JWKS) and the loser's assertions fail at Entra. Two {@link IssuerKeyService} instances (= two tasks)
 * share one secret store + one real Postgres (the advisory lock); concurrent {@code signingKey()} must
 * generate exactly once and converge on a single kid.
 */
@SpringBootTest
@ActiveProfiles("data")
@Import(TestcontainersConfig.class)
class IssuerKeySingleWriterTest {

    @Autowired
    DataSource dataSource;

    /** An in-memory stand-in for Secrets Manager: last-write-wins, ResourceNotFound until first put. */
    private SecretsManagerClient fakeSecrets(AtomicReference<String> store, AtomicInteger puts) {
        SecretsManagerClient secrets = mock(SecretsManagerClient.class);
        when(secrets.getSecretValue(any(GetSecretValueRequest.class))).thenAnswer(inv -> {
            String v = store.get();
            if (v == null) {
                throw ResourceNotFoundException.builder().message("no version yet").build();
            }
            return GetSecretValueResponse.builder().secretString(v).build();
        });
        when(secrets.putSecretValue(any(PutSecretValueRequest.class))).thenAnswer(inv -> {
            PutSecretValueRequest req = inv.getArgument(0);
            store.set(req.secretString());
            puts.incrementAndGet();
            return PutSecretValueResponse.builder().build();
        });
        return secrets;
    }

    @Test
    void concurrent_cold_start_generates_once_and_converges_on_one_kid() throws Exception {
        AtomicReference<String> store = new AtomicReference<>(null); // empty secret → cold start
        AtomicInteger puts = new AtomicInteger();
        SecretsManagerClient secrets = fakeSecrets(store, puts);

        IssuerProperties props = new IssuerProperties();
        props.setSigningSecretName("test-issuer-" + UUID.randomUUID());

        // two "tasks": separate IssuerKeyService instances, shared secret store + shared Postgres lock
        IssuerKeyService taskA = new IssuerKeyService(secrets, props, dataSource);
        IssuerKeyService taskB = new IssuerKeyService(secrets, props, dataSource);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> kidA = () -> taskA.signingKey().getKeyID();
            Callable<String> kidB = () -> taskB.signingKey().getKeyID();
            Future<String> fa = pool.submit(kidA);
            Future<String> fb = pool.submit(kidB);

            assertThat(fa.get()).isEqualTo(fb.get());  // both tasks converge on ONE kid
            assertThat(puts.get()).isEqualTo(1);        // generated exactly once — the other LOADED it
        } finally {
            pool.shutdownNow();
        }
    }
}
