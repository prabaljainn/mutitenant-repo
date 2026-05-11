package com.orochiverse.platform.common.security.jwks;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.orochiverse.platform.common.security.keys.JwtKeyProvider;

/**
 * Verifies that {@code /.well-known/jwks.json} is publicly reachable, returns
 * a JWK Set whose modulus matches the in-process key provider, and is
 * cacheable for an hour.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class JwksEndpointIT {

    @LocalServerPort int port;
    @Autowired JwtKeyProvider keyProvider;

    @Test
    @SuppressWarnings("unchecked")
    void jwks_endpoint_returns_the_active_public_key() {
        var response = new TestRestTemplate().getForEntity(
                "http://localhost:" + port + "/.well-known/jwks.json", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("max-age=3600");

        Map<String, Object> body = response.getBody();
        assertThat(body).containsKey("keys");

        var keys = (java.util.List<Map<String, Object>>) body.get("keys");
        assertThat(keys).hasSize(1);
        Map<String, Object> jwk = keys.get(0);

        assertThat(jwk).containsEntry("kty", "RSA");
        assertThat(jwk).containsEntry("alg", "RS256");
        assertThat(jwk).containsEntry("use", "sig");
        assertThat(jwk).containsEntry("kid", keyProvider.activeKeyId());

        // Reconstruct the RSA public key from the JWK n/e and assert it
        // matches the in-process provider. That proves the JWKS payload is
        // actually serving the verification key, not a stale/empty stub.
        RSAPublicKey reconstructed = rebuildRsa((String) jwk.get("n"), (String) jwk.get("e"));
        assertThat(reconstructed.getModulus()).isEqualTo(keyProvider.verificationKey().getModulus());
        assertThat(reconstructed.getPublicExponent())
                .isEqualTo(keyProvider.verificationKey().getPublicExponent());
    }

    private static RSAPublicKey rebuildRsa(String nB64Url, String eB64Url) {
        var dec = Base64.getUrlDecoder();
        var modulus = new BigInteger(1, dec.decode(nB64Url));
        var exponent = new BigInteger(1, dec.decode(eB64Url));
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));
        } catch (Exception e) {
            throw new AssertionError("failed to reconstruct RSA key from JWK", e);
        }
    }
}
