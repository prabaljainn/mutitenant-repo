package com.orochiverse.platform.iam.settings;

import java.util.Map;
import java.util.Set;

/**
 * Per-kind logic for the extensible {@code tenant_settings} store. One
 * implementation per {@link SettingsKind}. Wired by Spring as a bean
 * and discovered by {@link TenantSettingsService} via the bean
 * registry, so adding a new kind is: add an enum value, add a
 * {@code @Component} that implements this interface.
 *
 * <h2>Why an interface instead of a switch in the service?</h2>
 * Two reasons: (1) keeps kind-specific logic local — MQTT validation
 * doesn't have to know about DJI, and vice versa; (2) prevents a god
 * service file growing one branch per kind.
 *
 * <h2>What the service does, what the handler does</h2>
 * The service owns persistence (Mongo round-trips), audit writes, secret
 * masking, and ACL checks. The handler owns: <em>shape of the values
 * map</em> (allowed keys, types, defaults) and <em>connection test
 * implementation</em>. Each side stays single-purpose.
 */
public interface SettingsKindHandler {

    /** Which kind this handler covers. Used to register it in the bean map. */
    SettingsKind kind();

    /**
     * Keys whose values are credentials. The service:
     * <ul>
     *   <li><b>Read</b>: omits these from the response (the client gets a
     *       parallel {@code secrets} array listing which secret keys are
     *       stored).</li>
     *   <li><b>Write</b>: if a secret key is absent from the request
     *       body, the previously-stored value is kept (so the UI can
     *       PUT non-secret edits without re-typing the password).</li>
     * </ul>
     */
    Set<String> secretKeys();

    /**
     * Throws {@code UnprocessableException} if {@code values} doesn't
     * conform to this kind's schema. Called from
     * {@link TenantSettingsService#upsert} before writing.
     *
     * <p>Validation rules belong here — not in DTOs — because the schema
     * varies per kind and the service treats {@code values} as opaque.
     */
    void validate(Map<String, Object> values);

    /**
     * Connection test against the supplied values. The service has
     * already merged any stored secrets back in, so the handler sees a
     * complete set. Implementations should bound the test with a short
     * timeout (~3s) — this runs from the admin UI and the user is
     * watching.
     *
     * @return result {@code ok} (boolean), {@code latencyMs}, and an
     *         optional {@code error} message.
     */
    TestResult test(Map<String, Object> values);

    record TestResult(boolean ok, long latencyMs, String error) {
        public static TestResult ok(long latencyMs) {
            return new TestResult(true, latencyMs, null);
        }
        public static TestResult fail(String error, long latencyMs) {
            return new TestResult(false, latencyMs, error);
        }
    }
}
