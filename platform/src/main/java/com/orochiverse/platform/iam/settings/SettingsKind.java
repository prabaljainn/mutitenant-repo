package com.orochiverse.platform.iam.settings;

/**
 * Discriminator for the per-tenant settings store. A "kind" is one
 * coherent group of fields that get configured together — MQTT broker
 * credentials, DJI app keys, future Slack webhook, etc. Each kind has
 * exactly one {@link SettingsKindHandler} implementation that owns its
 * validation, secret-handling, and connection-test logic.
 *
 * <p>Adding a kind: append a value here, implement
 * {@code SettingsKindHandler} as a {@code @Component}, and that's it —
 * no new endpoints, no controller changes. The generic CRUD surface
 * (under {@code /admin/api/tenants/{id}/settings/{kind}}) dispatches on
 * this enum.
 */
public enum SettingsKind {

    /** MQTT broker for tenant drone telemetry. */
    MQTT,

    /** DJI Cloud / Pilot integration credentials and endpoint. */
    DJI
}
