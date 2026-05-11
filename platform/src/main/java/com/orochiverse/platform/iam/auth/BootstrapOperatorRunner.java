package com.orochiverse.platform.iam.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.iam.users.User;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.iam.users.UserStatus;

/**
 * Seeds the very first {@code OPERATOR_ADMIN} user when the platform boots
 * against an empty {@code iam_db.users}. Without this you'd need a separate
 * out-of-band script to create the first account, which is exactly the
 * kind of friction that makes onboarding painful.
 *
 * <h2>Trigger conditions (all required)</h2>
 * <ol>
 *   <li>{@code platform.bootstrap.operator.email} property — set via env
 *       var {@code PLATFORM_BOOTSTRAP_OPERATOR_EMAIL} (Spring relaxed
 *       binding) or in {@code application-dev.yml}.</li>
 *   <li>{@code platform.bootstrap.operator.password} property — env var
 *       {@code PLATFORM_BOOTSTRAP_OPERATOR_PASSWORD}.</li>
 *   <li>No user exists in {@code iam_db.users} with
 *       {@code kind=OPERATOR}.</li>
 * </ol>
 *
 * <p>If any condition is missing the runner logs at INFO why it's skipping
 * and exits — it never silently does nothing. Once a real operator exists,
 * the runner becomes a no-op forever.
 *
 * <p>Guarded by {@code @ConditionalOnProperty(spring.data.mongodb.uri)}
 * so the {@code test} profile (no Mongo) skips it.
 *
 * <h2>Why an env-var seed and not a CLI command?</h2>
 * Containers / Kubernetes deployments naturally surface env vars; a CLI
 * command would mean a separate operator workflow. The env-var approach
 * also means the bootstrap creds rotate trivially: pop the env vars once
 * the first admin exists.
 */
@Component
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class BootstrapOperatorRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapOperatorRunner.class);

    private final UserRepository users;
    private final PasswordHashing passwords;
    private final String bootstrapEmail;
    private final String bootstrapPassword;

    public BootstrapOperatorRunner(
            UserRepository users,
            PasswordHashing passwords,
            @Value("${platform.bootstrap.operator.email:}") String bootstrapEmail,
            @Value("${platform.bootstrap.operator.password:}") String bootstrapPassword) {
        this.users = users;
        this.passwords = passwords;
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapEmail.isBlank() || bootstrapPassword.isBlank()) {
            log.info("Bootstrap operator skipped: platform.bootstrap.operator.email/password not "
                    + "set (env vars PLATFORM_BOOTSTRAP_OPERATOR_EMAIL / "
                    + "PLATFORM_BOOTSTRAP_OPERATOR_PASSWORD). This is normal once the first "
                    + "operator exists.");
            return;
        }

        // findAllByKindAndStatus is cheap (filtered + indexed). countByKind
        // would be a larger refactor.
        boolean operatorExists = !users.findAllByKindAndStatus(
                com.orochiverse.platform.common.security.principals.UserKind.OPERATOR,
                UserStatus.ACTIVE).isEmpty()
                || !users.findAllByKindAndStatus(
                com.orochiverse.platform.common.security.principals.UserKind.OPERATOR,
                UserStatus.INVITED).isEmpty();

        if (operatorExists) {
            log.info("Bootstrap operator skipped: at least one OPERATOR user already exists.");
            return;
        }

        // Use a deterministic id so re-runs after a Mongo wipe don't pile
        // up duplicate-looking documents in audit / linked tables.
        String id = "bootstrap-operator-admin";
        var user = new User(
                id,
                bootstrapEmail,
                passwords.hash(bootstrapPassword),
                "Bootstrap",
                "Admin",
                UserStatus.ACTIVE,
                com.orochiverse.platform.common.security.principals.UserKind.OPERATOR,
                OperatorRole.OPERATOR_ADMIN,
                null, null, 0, null,
                java.time.Instant.now(), java.time.Instant.now());
        users.save(user);

        log.warn("Bootstrap operator created — email={} role=OPERATOR_ADMIN id={}. "
                + "Rotate the bootstrap password immediately and unset the env vars.",
                bootstrapEmail, id);
    }
}
