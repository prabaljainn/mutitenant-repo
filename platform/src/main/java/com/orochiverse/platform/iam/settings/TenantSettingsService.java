package com.orochiverse.platform.iam.settings;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.orochiverse.platform.common.audit.AuditAction;
import com.orochiverse.platform.common.audit.AuditEntry;
import com.orochiverse.platform.common.audit.AuditEntryRepository;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.NotFoundException;
import com.orochiverse.platform.iam.settings.SettingsKindHandler.TestResult;
import com.orochiverse.platform.iam.settings.TenantSettingsDtos.SettingsResponse;
import com.orochiverse.platform.iam.tenants.TenantRepository;

/**
 * The extensible per-tenant settings store: read, upsert, delete, test.
 * Owns persistence + audit + secret handling; delegates per-kind
 * validation and test execution to {@link SettingsKindHandler}s.
 *
 * <h2>Read path</h2>
 * For an unconfigured (tenant, kind), returns an empty
 * {@link SettingsResponse} with {@code configured=false} rather than
 * 404 — the UI binds form fields directly, and an empty record is the
 * natural blank-form state.
 *
 * <h2>Write path</h2>
 * Validates first (handler), then merges secrets (any secret key
 * absent from the request body is taken from the stored row, so the
 * UI can edit non-secret fields without round-tripping secrets), then
 * persists. Audit row records which keys changed — secret values are
 * never logged, only their key names.
 *
 * <h2>Test path</h2>
 * Re-validates first so callers can't bypass field checks by going
 * straight to /test. Result is persisted on the row so subsequent
 * reads show the connection status without re-running the probe.
 */
@Service
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class TenantSettingsService {

    private static final Logger log = LoggerFactory.getLogger(TenantSettingsService.class);

    private final TenantSettingsRepository settings;
    private final TenantRepository tenants;
    private final AuditEntryRepository audit;
    private final EnumMap<SettingsKind, SettingsKindHandler> handlers;

    public TenantSettingsService(TenantSettingsRepository settings,
                                 TenantRepository tenants,
                                 AuditEntryRepository audit,
                                 List<SettingsKindHandler> handlerList) {
        this.settings = settings;
        this.tenants = tenants;
        this.audit = audit;
        this.handlers = new EnumMap<>(SettingsKind.class);
        for (SettingsKindHandler h : handlerList) {
            this.handlers.put(h.kind(), h);
        }
        // Each enum value must have exactly one handler. Catch missing
        // wiring at startup so production never sees a "no handler for
        // kind X" runtime error.
        for (SettingsKind kind : SettingsKind.values()) {
            if (!this.handlers.containsKey(kind)) {
                throw new IllegalStateException("no SettingsKindHandler bean for kind " + kind);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Read
    // ─────────────────────────────────────────────────────────────────────

    public SettingsResponse get(String tenantId, SettingsKind kind) {
        requireTenantExists(tenantId);
        return settings.findByTenantIdAndKind(tenantId, kind)
                .map(s -> toResponse(s, handlers.get(kind)))
                .orElseGet(() -> blank(tenantId, kind));
    }

    public List<SettingsResponse> list(String tenantId) {
        requireTenantExists(tenantId);
        return settings.findAllByTenantId(tenantId).stream()
                .map(s -> toResponse(s, handlers.get(s.kind())))
                .toList();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Upsert
    // ─────────────────────────────────────────────────────────────────────

    public SettingsResponse upsert(String tenantId, SettingsKind kind,
                                   Map<String, Object> incomingValues, String actorUserId) {
        requireTenantExists(tenantId);
        SettingsKindHandler handler = handlers.get(kind);

        Optional<TenantSetting> existing = settings.findByTenantIdAndKind(tenantId, kind);

        // Secret-merging: a missing secret key in the request body means
        // "keep the existing value". An explicit null clears it.
        Map<String, Object> merged = new LinkedHashMap<>(incomingValues);
        if (existing.isPresent()) {
            Map<String, Object> stored = existing.get().values();
            for (String secretKey : handler.secretKeys()) {
                if (!incomingValues.containsKey(secretKey) && stored.containsKey(secretKey)) {
                    merged.put(secretKey, stored.get(secretKey));
                }
            }
        }

        handler.validate(merged);

        TenantSetting toSave = existing
                .map(s -> s.withValues(merged))
                .orElseGet(() -> TenantSetting.fresh(tenantId, kind, merged));
        TenantSetting saved = settings.save(toSave);

        // Audit: list which keys changed (names only, never values, never
        // secrets). For a fresh row, that's every key; for an update,
        // diff against existing.
        Set<String> changedKeys = diffKeys(existing.map(TenantSetting::values), merged, handler.secretKeys());
        audit.save(AuditEntry.of(AuditAction.TENANT_SETTING_UPDATED, actorUserId, tenantId,
                Map.of("tenantId", tenantId, "kind", kind.name(), "changed", changedKeys)));
        log.info("tenant setting upserted tenant={} kind={} changed={} actor={}",
                tenantId, kind, changedKeys, actorUserId);

        return toResponse(saved, handler);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Delete
    // ─────────────────────────────────────────────────────────────────────

    public void delete(String tenantId, SettingsKind kind, String actorUserId) {
        requireTenantExists(tenantId);
        TenantSetting existing = settings.findByTenantIdAndKind(tenantId, kind)
                .orElseThrow(() -> new NotFoundException(
                        "settings " + kind + " for tenant " + tenantId + " not found"));
        settings.delete(existing);
        audit.save(AuditEntry.of(AuditAction.TENANT_SETTING_DELETED, actorUserId, tenantId,
                Map.of("tenantId", tenantId, "kind", kind.name())));
        log.info("tenant setting deleted tenant={} kind={} actor={}", tenantId, kind, actorUserId);
    }

    /** Called by {@code TenantsAdminService.softDelete} to clean up settings rows. */
    public long deleteAllForTenant(String tenantId) {
        return settings.deleteAllByTenantId(tenantId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Runs a connection test. If {@code draftValues} is non-null the
     * test uses those (with stored secrets merged in); otherwise the
     * stored values. Stores the result on the row so the UI's "last
     * tested" chip reflects reality.
     */
    public TestResult test(String tenantId, SettingsKind kind, Map<String, Object> draftValues,
                           String actorUserId) {
        requireTenantExists(tenantId);
        SettingsKindHandler handler = handlers.get(kind);
        Optional<TenantSetting> existing = settings.findByTenantIdAndKind(tenantId, kind);

        Map<String, Object> probeValues;
        if (draftValues != null) {
            // Merge in stored secrets the same way upsert does.
            probeValues = new LinkedHashMap<>(draftValues);
            if (existing.isPresent()) {
                Map<String, Object> stored = existing.get().values();
                for (String secretKey : handler.secretKeys()) {
                    if (!draftValues.containsKey(secretKey) && stored.containsKey(secretKey)) {
                        probeValues.put(secretKey, stored.get(secretKey));
                    }
                }
            }
            handler.validate(probeValues);
        } else {
            probeValues = existing.map(TenantSetting::values)
                    .orElseThrow(() -> new NotFoundException(
                            "no stored settings to test — provide values in the request body"));
        }

        TestResult result = handler.test(probeValues);

        // Persist the result if we have a row. Don't create a row just
        // for a test — saving requires upsert.
        existing.ifPresent(s -> settings.save(s.withTestResult(result.ok(), result.error())));

        audit.save(AuditEntry.of(AuditAction.TENANT_SETTING_TESTED, actorUserId, tenantId,
                Map.of("tenantId", tenantId, "kind", kind.name(),
                        "ok", result.ok(),
                        "latencyMs", result.latencyMs())));
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private void requireTenantExists(String tenantId) {
        if (!tenants.existsById(tenantId)) {
            throw new NotFoundException("tenant " + tenantId + " not found");
        }
    }

    private SettingsResponse toResponse(TenantSetting s, SettingsKindHandler handler) {
        Set<String> secretKeys = handler.secretKeys();
        Map<String, Object> visible = new LinkedHashMap<>(s.values());
        Set<String> storedSecrets = new java.util.LinkedHashSet<>();
        for (String key : secretKeys) {
            if (visible.remove(key) != null) {
                storedSecrets.add(key);
            }
        }
        return new SettingsResponse(s.tenantId(), s.kind(), true, visible,
                storedSecrets, s.lastTestedAt(), s.lastTestOk(), s.lastTestError(), s.updatedAt());
    }

    private static SettingsResponse blank(String tenantId, SettingsKind kind) {
        return new SettingsResponse(tenantId, kind, false, Map.of(), Set.of(),
                null, null, null, null);
    }

    /** Names of keys that differ between two maps, with secret values reduced to their key names only. */
    private static Set<String> diffKeys(Optional<Map<String, Object>> oldValues,
                                        Map<String, Object> newValues,
                                        Set<String> secretKeys) {
        var changed = new java.util.LinkedHashSet<String>();
        Map<String, Object> old = oldValues.orElseGet(HashMap::new);
        for (var e : newValues.entrySet()) {
            Object before = old.get(e.getKey());
            if (!java.util.Objects.equals(before, e.getValue())) {
                changed.add(e.getKey());
            }
        }
        for (String k : old.keySet()) {
            if (!newValues.containsKey(k)) changed.add(k);
        }
        // Don't return secret values in the audit row — keys are fine.
        // (We already only return key names, but this line is explicit
        // documentation that secrets are not exposed via audit.)
        return changed;
    }
}
