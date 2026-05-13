package com.orochiverse.platform.common.security.jwks;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orochiverse.platform.common.security.keys.JwtKeyProvider;

import io.jsonwebtoken.security.Jwks;
import io.jsonwebtoken.security.RsaPublicJwk;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Serves the JSON Web Key Set so that anything verifying our access tokens
 * — the platform itself, GCS in M2+, or future external integrations — can
 * fetch the public key without an IAM round-trip per request.
 *
 * <p>The endpoint deliberately uses the standard {@code /.well-known/jwks.json}
 * path so off-the-shelf JWT libraries (Spring resource-server, Auth0,
 * Nimbus) recognize it without configuration.
 *
 * <p>Cacheable for 1 hour — long enough to absorb a deployment burst, short
 * enough that a key rotation propagates within the operational SLA. Phase
 * 1.5 only ever publishes one key; rotation lands later.
 */
@RestController
@Tag(name = "JWKS", description = "Public JSON Web Key Set for verifying access tokens. Public, cacheable, no auth.")
public class JwksController {

    private final JwtKeyProvider keys;

    public JwksController(JwtKeyProvider keys) {
        this.keys = keys;
    }

    @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        RsaPublicJwk jwk = Jwks.builder()
                .key(keys.verificationKey())
                .id(keys.activeKeyId())
                .build();

        // jjwt's Jwk implements Map<String,Object> — copy it so we can add
        // the optional `alg` / `use` fields that some verifiers (Spring
        // resource-server in particular) inspect when picking a parser.
        Map<String, Object> enriched = new LinkedHashMap<>(jwk);
        enriched.putIfAbsent("alg", "RS256");
        enriched.putIfAbsent("use", "sig");

        Map<String, Object> body = Map.of("keys", List.of(enriched));

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(body);
    }
}
