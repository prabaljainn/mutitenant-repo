package com.orochiverse.platform.iam.settings;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.orochiverse.platform.iam.admin.common.AdminExceptions.UnprocessableException;

/**
 * MQTT broker configuration: where this tenant's drones publish
 * telemetry. Validates the field shape, exposes {@code password} as a
 * secret, and tests by opening a TCP socket to host:port.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@code host} — broker DNS or IP. Required.</li>
 *   <li>{@code port} — integer 1–65535. Required.</li>
 *   <li>{@code transport} — {@code tls} / {@code ws} / {@code tcp}.</li>
 *   <li>{@code topicPrefix} — string prepended to drone-side topics.</li>
 *   <li>{@code username} — broker username.</li>
 *   <li>{@code password} — broker password (secret).</li>
 * </ul>
 */
@Component
public class MqttSettingsHandler implements SettingsKindHandler {

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "host", "port", "transport", "topicPrefix", "username", "password");
    private static final Set<String> ALLOWED_TRANSPORTS = Set.of("tls", "ws", "tcp");

    private final ConnectionTester tester;

    public MqttSettingsHandler(ConnectionTester tester) {
        this.tester = tester;
    }

    @Override
    public SettingsKind kind() {
        return SettingsKind.MQTT;
    }

    @Override
    public Set<String> secretKeys() {
        return Set.of("password");
    }

    @Override
    public void validate(Map<String, Object> values) {
        // Whitelist keys — typos like "hostName" should fail loudly, not
        // get silently persisted next to "host".
        for (String key : values.keySet()) {
            if (!ALLOWED_KEYS.contains(key)) {
                throw new UnprocessableException("unknown MQTT field: " + key);
            }
        }
        requireNonBlank(values, "host");
        Object portRaw = values.get("port");
        if (portRaw == null) {
            throw new UnprocessableException("port is required");
        }
        int port = coerceInt(portRaw, "port");
        if (port < 1 || port > 65535) {
            throw new UnprocessableException("port must be between 1 and 65535");
        }
        String transport = String.valueOf(values.getOrDefault("transport", "tls"));
        if (!ALLOWED_TRANSPORTS.contains(transport)) {
            throw new UnprocessableException(
                    "transport must be one of " + ALLOWED_TRANSPORTS + ", got " + transport);
        }
    }

    @Override
    public TestResult test(Map<String, Object> values) {
        String host = String.valueOf(values.getOrDefault("host", ""));
        int port = coerceInt(values.getOrDefault("port", 0), "port");
        return tester.tcpProbe(host, port);
    }

    private static void requireNonBlank(Map<String, Object> values, String key) {
        Object v = values.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new UnprocessableException(key + " is required");
        }
    }

    private static int coerceInt(Object raw, String field) {
        if (raw instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw new UnprocessableException(field + " must be a number");
        }
    }
}
