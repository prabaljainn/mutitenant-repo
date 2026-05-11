package com.orochiverse.platform.iam.settings;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.NotNull;

/**
 * DTOs for {@code /admin/api/tenants/{id}/settings/...}. Shapes are
 * deliberately generic — {@code values} is a free-form map keyed by
 * whatever the corresponding {@link SettingsKindHandler} accepts. The
 * shape per-kind lives in the handler, not in a per-kind DTO.
 */
public final class TenantSettingsDtos {

    private TenantSettingsDtos() {}

    /**
     * One settings record as the UI sees it.
     *
     * <p>{@code configured} is true once any PUT has happened. The UI
     * uses this to render the "not configured" pill on a fresh kind
     * without forcing the client to distinguish 404 from empty.
     *
     * <p>{@code values} omits secret fields; {@code secrets} lists which
     * secret keys are currently stored so the UI can show "•••••••••" in
     * those inputs instead of going dark.
     */
    public record SettingsResponse(
            String tenantId,
            SettingsKind kind,
            boolean configured,
            Map<String, Object> values,
            Set<String> secrets,
            Instant lastTestedAt,
            Boolean lastTestOk,
            String lastTestError,
            Instant updatedAt) {}

    /**
     * PUT body. Secret fields can be omitted to keep the existing
     * value; an explicit {@code null} clears the secret.
     *
     * <p>Distinguishing "absent" from "null" in JSON requires either a
     * sentinel or the explicit {@code Optional}-like discriminator. We
     * take the simple path: if the secret key is missing from the
     * deserialised map, treat as "keep"; if present (including null),
     * treat as "set to this value".
     */
    public record UpsertSettingsRequest(@NotNull Map<String, Object> values) {}

    /**
     * Optional POST body for the {@code /test} endpoint. If provided,
     * the connection test runs against these values (the "save draft
     * before testing" path). If omitted (empty body), the test runs
     * against what's currently stored.
     */
    public record TestSettingsRequest(Map<String, Object> values) {}

    public record TestSettingsResponse(boolean ok, long latencyMs, String error) {}

    /** GET {@code /settings} returns one entry per kind the tenant has touched. */
    public record SettingsListResponse(List<SettingsResponse> items) {}
}
