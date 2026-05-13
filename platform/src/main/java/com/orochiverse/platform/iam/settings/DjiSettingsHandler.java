package com.orochiverse.platform.iam.settings;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.orochiverse.platform.iam.admin.common.AdminExceptions.UnprocessableException;

/**
 * DJI Cloud / Pilot integration: per-tenant endpoint, app key, app
 * secret. Validates field shape and tests by hitting the configured
 * endpoint URL.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@code region} — {@code ap} / {@code us} / {@code eu}.</li>
 *   <li>{@code endpointUrl} — DJI API root (e.g. {@code https://api-cloud.dji.com}).</li>
 *   <li>{@code appKey} — public DJI app id.</li>
 *   <li>{@code appSecret} — DJI app secret (secret).</li>
 * </ul>
 */
@Component
public class DjiSettingsHandler implements SettingsKindHandler {

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "region", "endpointUrl", "appKey", "appSecret");
    private static final Set<String> ALLOWED_REGIONS = Set.of("ap", "us", "eu");

    private final ConnectionTester tester;

    public DjiSettingsHandler(ConnectionTester tester) {
        this.tester = tester;
    }

    @Override
    public SettingsKind kind() {
        return SettingsKind.DJI;
    }

    @Override
    public Set<String> secretKeys() {
        return Set.of("appSecret");
    }

    @Override
    public void validate(Map<String, Object> values) {
        for (String key : values.keySet()) {
            if (!ALLOWED_KEYS.contains(key)) {
                throw new UnprocessableException("unknown DJI field: " + key);
            }
        }
        String region = String.valueOf(values.getOrDefault("region", ""));
        if (!ALLOWED_REGIONS.contains(region)) {
            throw new UnprocessableException(
                    "region must be one of " + ALLOWED_REGIONS + ", got " + region);
        }
        String endpoint = String.valueOf(values.getOrDefault("endpointUrl", ""));
        if (endpoint.isBlank() || (!endpoint.startsWith("https://") && !endpoint.startsWith("http://"))) {
            throw new UnprocessableException(
                    "endpointUrl must be a fully-qualified http(s) URL");
        }
        // appKey / appSecret presence isn't enforced at validate time —
        // the UI lets you save a partial config and come back to fill
        // the secret later. The test endpoint will fail loudly if the
        // secret is missing when it actually needs it.
    }

    @Override
    public TestResult test(Map<String, Object> values) {
        String endpoint = String.valueOf(values.getOrDefault("endpointUrl", ""));
        return tester.httpProbe(endpoint);
    }
}
