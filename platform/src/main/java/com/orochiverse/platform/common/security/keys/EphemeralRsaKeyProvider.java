package com.orochiverse.platform.common.security.keys;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates a fresh RSA-2048 keypair when the bean is constructed and holds
 * it for the JVM's lifetime. Restarting the platform invalidates every
 * outstanding token, which is the correct behavior in dev / test — it forces
 * developers to re-login and prevents any "stale token from yesterday's
 * session" surprises.
 *
 * <p>Logs a clearly-labeled WARN on startup so this provider can never be
 * confused with the production {@link FileRsaKeyProvider} in log greps.
 */
public final class EphemeralRsaKeyProvider implements JwtKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(EphemeralRsaKeyProvider.class);
    private static final int KEY_SIZE_BITS = 2048;

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final String keyId;

    public EphemeralRsaKeyProvider() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(KEY_SIZE_BITS);
            KeyPair kp = gen.generateKeyPair();
            this.privateKey = (RSAPrivateKey) kp.getPrivate();
            this.publicKey = (RSAPublicKey) kp.getPublic();
            this.keyId = "ephemeral-" + UUID.randomUUID();
            log.warn("EphemeralRsaKeyProvider active — RSA-{} keypair generated for this JVM only "
                    + "(kid={}). Tokens will not survive a restart. "
                    + "Configure platform.security.jwt.private-key-path in production.",
                    KEY_SIZE_BITS, keyId);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA must be available in every JRE", e);
        }
    }

    @Override
    public RSAPrivateKey signingKey() {
        return privateKey;
    }

    @Override
    public RSAPublicKey verificationKey() {
        return publicKey;
    }

    @Override
    public String activeKeyId() {
        return keyId;
    }
}
