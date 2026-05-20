package com.orochiverse.platform.iam.admin.tenants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.orochiverse.platform.common.audit.AuditEntryRepository;
import com.orochiverse.platform.common.tenant.TenantDatabaseProvisioner;
import com.orochiverse.platform.common.tenant.TenantId;
import com.orochiverse.platform.iam.admin.common.OperatorVisibility;
import com.orochiverse.platform.iam.settings.TenantSettingsService;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.users.UserRepository;

/**
 * Unit tests for the tenant-id slug generator. The full create flow is
 * covered by {@link TenantsAdminControllerIT}; this exercises the slug
 * logic in isolation so failure messages point at the specific edge case.
 */
class TenantsAdminServiceTest {

    @Test
    void slugify_lowercases_and_hyphenates() {
        assertThat(TenantsAdminService.slugify("Acme Corp")).isEqualTo("acme-corp");
        assertThat(TenantsAdminService.slugify("Hello, World!!")).isEqualTo("hello-world");
        assertThat(TenantsAdminService.slugify("  spaces  everywhere  ")).isEqualTo("spaces-everywhere");
        assertThat(TenantsAdminService.slugify("under_scores_too")).isEqualTo("under-scores-too");
    }

    @Test
    void slugify_returns_empty_for_unusable_input() {
        assertThat(TenantsAdminService.slugify(null)).isEmpty();
        assertThat(TenantsAdminService.slugify("")).isEmpty();
        assertThat(TenantsAdminService.slugify("!!!")).isEmpty();
        assertThat(TenantsAdminService.slugify("   ")).isEmpty();
    }

    @Test
    void slugify_caps_length_and_trims_trailing_hyphen() {
        assertThat(TenantsAdminService.slugify("a".repeat(60))).hasSize(40);
        // A name that slugs to a value ending in `-` at the cap gets that
        // hyphen stripped, so the suffix join doesn't produce `--`.
        String pathological = "x".repeat(40) + "!!!" + "y";
        assertThat(TenantsAdminService.slugify(pathological)).doesNotEndWith("-");
    }

    @Test
    void slugify_output_is_a_valid_tenant_id() {
        for (String name : new String[] {
                "Acme Corp", "Über Café 2026", "skyhawk!!", "  spaces  ", "MIXED_case-NAME"
        }) {
            String slug = TenantsAdminService.slugify(name);
            if (!slug.isEmpty()) {
                TenantId.requireValid(slug);
            }
        }
    }

    @Test
    void generateUniqueId_uses_bare_slug_when_free() {
        var tenants = mock(TenantRepository.class);
        when(tenants.existsById("acme-corp")).thenReturn(false);

        assertThat(newService(tenants).generateUniqueId("Acme Corp")).isEqualTo("acme-corp");
    }

    @Test
    void generateUniqueId_appends_suffix_when_bare_slug_is_taken() {
        var tenants = mock(TenantRepository.class);
        when(tenants.existsById("acme-corp")).thenReturn(true);
        when(tenants.existsById(argThat(s -> s != null && s.startsWith("acme-corp-"))))
                .thenReturn(false);

        String id = newService(tenants).generateUniqueId("Acme Corp");
        assertThat(id).matches("^acme-corp-[a-z0-9]{4}$");
    }

    @Test
    void generateUniqueId_falls_back_to_tenant_prefix_for_empty_slug() {
        var tenants = mock(TenantRepository.class);
        when(tenants.existsById("tenant")).thenReturn(false);

        // All-symbol name slugs to empty — service substitutes "tenant".
        assertThat(newService(tenants).generateUniqueId("!!!")).isEqualTo("tenant");
    }

    @Test
    void generateUniqueId_eventually_throws_if_all_attempts_collide() {
        var tenants = mock(TenantRepository.class);
        when(tenants.existsById(anyString())).thenReturn(true);

        assertThatThrownBy(() -> newService(tenants).generateUniqueId("Acme"))
                .hasMessageContaining("could not allocate a unique tenant id");
    }

    @SuppressWarnings("unchecked")
    private static TenantsAdminService newService(TenantRepository tenants) {
        var users = mock(UserRepository.class);
        var provisioner = mock(TenantDatabaseProvisioner.class);
        var audit = mock(AuditEntryRepository.class);
        var visibility = mock(OperatorVisibility.class);
        var settings = (ObjectProvider<TenantSettingsService>) mock(ObjectProvider.class);
        return new TenantsAdminService(tenants, users, provisioner, audit, visibility, settings);
    }
}
