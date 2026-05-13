package com.orochiverse.platform.iam.settings;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One configuration record for one (tenant, kind) pair. Lives in
 * {@code iam_db.tenant_settings} alongside the tenants collection so a
 * tenant-scoped query can resolve everything in one DB.
 *
 * <p>The {@code _id} is the composite string {@code "<tenantId>:<kind>"}
 * — this gives us free upsert-by-natural-key semantics without
 * maintaining a separate unique index. (The compound index below makes
 * {@code findAllByTenantId} cheap for the "show me all settings for
 * this tenant" path.)
 *
 * <h2>Why a flexible {@code values} map?</h2>
 * Each {@link SettingsKind} has a different schema (MQTT has host/port,
 * DJI has region/appKey, …). Modelling them as separate collections
 * would force a new controller, repository, service, and migration per
 * kind. The map-of-strings approach trades static field types for
 * extensibility — and the {@link SettingsKindHandler} validates the
 * keys/types on every write, so the dynamism is bounded.
 *
 * <h2>Secrets</h2>
 * Secret values (MQTT password, DJI app secret) are stored verbatim
 * here in the M1 POC. Production should move them behind a secrets
 * manager or app-level envelope encryption with a master key; the
 * service-side mask-on-read behaviour already pretends they are
 * already-encrypted, so swapping the storage backend is local.
 *
 * @param id           composite {@code "<tenantId>:<kind>"}.
 * @param tenantId     tenant this row belongs to (denormalised from id
 *                     for indexed listing).
 * @param kind         which schema {@code values} follows.
 * @param values       configuration fields; keys + types per kind.
 * @param lastTestedAt last successful or attempted connection test;
 *                     {@code null} if never tested.
 * @param lastTestOk   {@code true} if the last test succeeded; {@code null}
 *                     if never tested.
 * @param lastTestError last test's error message; {@code null} on success
 *                     or before first test.
 * @param createdAt   first write.
 * @param updatedAt   last write or test.
 */
@Document(collection = "tenant_settings")
@CompoundIndex(name = "idx_tenant_settings_tenant_kind", def = "{'tenantId': 1, 'kind': 1}")
public record TenantSetting(
        @Id String id,
        String tenantId,
        SettingsKind kind,
        Map<String, Object> values,
        Instant lastTestedAt,
        Boolean lastTestOk,
        String lastTestError,
        Instant createdAt,
        Instant updatedAt) {

    public TenantSetting {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        values = Map.copyOf(values);
    }

    public static String compositeId(String tenantId, SettingsKind kind) {
        return tenantId + ":" + kind.name();
    }

    public static TenantSetting fresh(String tenantId, SettingsKind kind, Map<String, Object> values) {
        var now = Instant.now();
        return new TenantSetting(compositeId(tenantId, kind), tenantId, kind, values,
                null, null, null, now, now);
    }

    public TenantSetting withValues(Map<String, Object> newValues) {
        return new TenantSetting(id, tenantId, kind, newValues,
                lastTestedAt, lastTestOk, lastTestError, createdAt, Instant.now());
    }

    public TenantSetting withTestResult(boolean ok, String error) {
        var now = Instant.now();
        return new TenantSetting(id, tenantId, kind, values, now, ok, error, createdAt, now);
    }
}
