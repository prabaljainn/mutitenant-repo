package com.orochiverse.platform.iam.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.orochiverse.platform.common.audit.AuditEntry;
import com.orochiverse.platform.common.audit.AuditEntryRepository;
import com.orochiverse.platform.iam.admin.common.AdminExceptions.NotFoundException;
import com.orochiverse.platform.iam.settings.SettingsKindHandler.TestResult;
import com.orochiverse.platform.iam.tenants.TenantRepository;

/**
 * Unit tests focused on the cross-cutting service behaviour: secret
 * masking on read, secret-merging on write, and what makes it into the
 * audit row. The handlers themselves are covered separately — here we
 * use the real MQTT handler with a stub ConnectionTester so the logic
 * is exercised end-to-end without touching Mongo.
 */
class TenantSettingsServiceTest {

    private static final String TENANT = "acme";

    private TenantSettingsRepository repo;
    private TenantRepository tenants;
    private AuditEntryRepository audit;
    private ConnectionTester tester;
    private TenantSettingsService service;

    @BeforeEach
    void setUp() {
        repo = mock(TenantSettingsRepository.class);
        tenants = mock(TenantRepository.class);
        audit = mock(AuditEntryRepository.class);
        tester = mock(ConnectionTester.class);
        when(tenants.existsById(TENANT)).thenReturn(true);

        var mqtt = new MqttSettingsHandler(tester);
        var dji  = new DjiSettingsHandler(tester);
        service = new TenantSettingsService(repo, tenants, audit, List.of(mqtt, dji));
    }

    @Test
    void unknown_tenant_returns_404_on_every_op() {
        when(tenants.existsById("ghost")).thenReturn(false);
        assertThatThrownBy(() -> service.get("ghost", SettingsKind.MQTT))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.upsert("ghost", SettingsKind.MQTT,
                Map.of("host", "h", "port", 1883), "actor"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void blank_response_when_kind_has_no_row_yet() {
        when(repo.findByTenantIdAndKind(TENANT, SettingsKind.MQTT)).thenReturn(Optional.empty());
        var r = service.get(TENANT, SettingsKind.MQTT);
        assertThat(r.configured()).isFalse();
        assertThat(r.values()).isEmpty();
        assertThat(r.secrets()).isEmpty();
    }

    @Test
    void read_masks_secrets_and_advertises_which_secret_keys_are_stored() {
        var stored = TenantSetting.fresh(TENANT, SettingsKind.MQTT, Map.of(
                "host", "m.x.io", "port", 8883, "transport", "tls",
                "username", "u", "password", "pw"));
        when(repo.findByTenantIdAndKind(TENANT, SettingsKind.MQTT)).thenReturn(Optional.of(stored));

        var r = service.get(TENANT, SettingsKind.MQTT);
        assertThat(r.configured()).isTrue();
        assertThat(r.values()).doesNotContainKey("password");
        assertThat(r.values()).containsEntry("host", "m.x.io");
        assertThat(r.secrets()).containsExactly("password");
    }

    @Test
    void upsert_merges_stored_secret_when_request_omits_it() {
        var stored = TenantSetting.fresh(TENANT, SettingsKind.MQTT, Map.of(
                "host", "m.x.io", "port", 8883, "password", "old-secret"));
        when(repo.findByTenantIdAndKind(TENANT, SettingsKind.MQTT)).thenReturn(Optional.of(stored));
        when(repo.save(any(TenantSetting.class))).thenAnswer(inv -> inv.getArgument(0));

        // Request includes host/port but NO password key — service should
        // keep the existing one.
        service.upsert(TENANT, SettingsKind.MQTT,
                Map.of("host", "m.new.io", "port", 8883), "actor");

        var captor = ArgumentCaptor.forClass(TenantSetting.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().values()).containsEntry("password", "old-secret");
        assertThat(captor.getValue().values()).containsEntry("host", "m.new.io");
    }

    @Test
    void upsert_with_explicit_secret_overwrites() {
        var stored = TenantSetting.fresh(TENANT, SettingsKind.MQTT, Map.of(
                "host", "h", "port", 8883, "password", "old"));
        when(repo.findByTenantIdAndKind(TENANT, SettingsKind.MQTT)).thenReturn(Optional.of(stored));
        when(repo.save(any(TenantSetting.class))).thenAnswer(inv -> inv.getArgument(0));

        service.upsert(TENANT, SettingsKind.MQTT,
                Map.of("host", "h", "port", 8883, "password", "new"), "actor");

        var captor = ArgumentCaptor.forClass(TenantSetting.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().values()).containsEntry("password", "new");
    }

    @Test
    void upsert_validates_through_handler() {
        when(repo.findByTenantIdAndKind(TENANT, SettingsKind.MQTT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upsert(TENANT, SettingsKind.MQTT,
                Map.of("host", "h", "port", 999999), "actor"))
                .hasMessageContaining("between 1 and 65535");
    }

    @Test
    void upsert_writes_audit_row_listing_changed_keys() {
        when(repo.findByTenantIdAndKind(TENANT, SettingsKind.MQTT)).thenReturn(Optional.empty());
        when(repo.save(any(TenantSetting.class))).thenAnswer(inv -> inv.getArgument(0));

        service.upsert(TENANT, SettingsKind.MQTT,
                Map.of("host", "h", "port", 1883), "actor");

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).save(captor.capture());
        var meta = captor.getValue().metadata();
        assertThat(meta).containsEntry("tenantId", TENANT);
        assertThat(meta).containsEntry("kind", "MQTT");
        @SuppressWarnings("unchecked")
        var changed = (java.util.Collection<String>) meta.get("changed");
        assertThat(changed).contains("host", "port");
    }

    @Test
    void delete_404s_when_nothing_to_delete() {
        when(repo.findByTenantIdAndKind(TENANT, SettingsKind.MQTT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(TENANT, SettingsKind.MQTT, "actor"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void test_with_draft_runs_against_draft_with_secrets_merged() {
        var stored = TenantSetting.fresh(TENANT, SettingsKind.MQTT,
                Map.of("host", "h", "port", 1883, "password", "kept"));
        when(repo.findByTenantIdAndKind(TENANT, SettingsKind.MQTT)).thenReturn(Optional.of(stored));
        when(tester.tcpProbe("h.new", 1883)).thenReturn(TestResult.ok(15));
        when(repo.save(any(TenantSetting.class))).thenAnswer(inv -> inv.getArgument(0));

        // Draft changes host; doesn't re-send password. Handler.test
        // should receive the merged map (i.e. password=kept).
        TestResult r = service.test(TENANT, SettingsKind.MQTT,
                Map.of("host", "h.new", "port", 1883), "actor");
        assertThat(r.ok()).isTrue();
        verify(tester).tcpProbe("h.new", 1883);
        verify(repo, times(1)).save(any(TenantSetting.class)); // result persisted
    }

    @Test
    void test_against_empty_row_with_null_draft_404s_with_clear_message() {
        when(repo.findByTenantIdAndKind(TENANT, SettingsKind.MQTT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.test(TENANT, SettingsKind.MQTT, null, "actor"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("no stored settings");
    }

    @Test
    void enum_completeness_check_fires_at_construction_time() {
        // A handler-less kind at construction time is wrong by definition.
        var mqttOnly = new MqttSettingsHandler(tester);
        assertThatThrownBy(() -> new TenantSettingsService(repo, tenants, audit, List.of(mqttOnly)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SettingsKind.DJI.name());
    }
}
