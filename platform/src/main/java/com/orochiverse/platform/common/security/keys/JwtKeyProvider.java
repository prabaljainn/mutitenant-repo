package com.orochiverse.platform.common.security.keys;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Single source of truth for the RSA keypair used to sign and verify access
 * tokens, plus the {@code kid} that ties them to the matching entry in the
 * JWKS endpoint.
 *
 * <p>Two implementations exist:
 * <ul>
 *   <li>{@link EphemeralRsaKeyProvider} — generates a fresh 2048-bit keypair
 *       on startup. Used in dev / test / integration profiles. Tokens issued
 *       in one process do not survive a restart, which is exactly what you
 *       want locally (no stale dev tokens).</li>
 *   <li>{@link FileRsaKeyProvider} — loads a PEM-encoded keypair from disk
 *       (PKCS#8 private, X.509 SubjectPublicKeyInfo public). Used in prod;
 *       paths come from {@code PLATFORM_JWT_PRIVATE_KEY_PATH} /
 *       {@code PLATFORM_JWT_PUBLIC_KEY_PATH} env vars.</li>
 * </ul>
 *
 * <p>Selection is wired in {@link JwtKeysConfig}. Phase 1.5 ships with a
 * single active key; key rotation (multiple {@code kid}s in the JWKS, with a
 * preferred signer) is deliberately deferred — the provider already exposes
 * a {@link #activeKeyId()} method so adding a {@code List&lt;Jwk&gt; allKeys()}
 * later is a non-breaking change.
 */
public interface JwtKeyProvider {

    /** Private key used to <em>sign</em> newly-issued access tokens. */
    RSAPrivateKey signingKey();

    /** Public key used to <em>verify</em> incoming access tokens. */
    RSAPublicKey verificationKey();

    /**
     * The {@code kid} (key ID) that goes into the JWT header and identifies
     * the corresponding entry in {@code /.well-known/jwks.json}. Stable for
     * the lifetime of this provider; ephemeral providers regenerate it on
     * every JVM start.
     */
    String activeKeyId();
}
