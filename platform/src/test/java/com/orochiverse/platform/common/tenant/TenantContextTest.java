package com.orochiverse.platform.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the TenantContext / Scoped Value semantics. No Spring,
 * no Mongo. Verifies that the binding is in scope where it should be and out
 * of scope where it shouldn't.
 */
class TenantContextTest {

    @Test
    void unbound_by_default() {
        assertThat(TenantContext.isBound()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
        assertThatThrownBy(TenantContext::requireCurrent)
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void runIn_binds_for_the_dynamic_extent() {
        var captured = new AtomicReference<String>();
        TenantContext.runIn("acme", () -> {
            assertThat(TenantContext.isBound()).isTrue();
            captured.set(TenantContext.requireCurrent());
        });
        assertThat(captured.get()).isEqualTo("acme");
        // Once we exit the scope, the binding is gone.
        assertThat(TenantContext.isBound()).isFalse();
    }

    @Test
    void callIn_returns_a_value() throws Exception {
        String got = TenantContext.callIn("vega", () -> "tenant=" + TenantContext.requireCurrent());
        assertThat(got).isEqualTo("tenant=vega");
    }

    @Test
    void callIn_propagates_typed_checked_exception() {
        assertThatThrownBy(() -> TenantContext.<String, IOException>callIn("acme", () -> {
                    throw new IOException("boom");
                }))
                .isInstanceOf(IOException.class)
                .hasMessage("boom");
    }

    @Test
    void nested_runIn_overrides_for_inner_scope_only() {
        var inner = new AtomicReference<String>();
        var outer = new AtomicReference<String>();

        TenantContext.runIn("outer", () -> {
            outer.set(TenantContext.requireCurrent());
            TenantContext.runIn("inner", () -> inner.set(TenantContext.requireCurrent()));
            // After the inner scope exits, the outer binding is restored.
            assertThat(TenantContext.requireCurrent()).isEqualTo("outer");
        });

        assertThat(outer.get()).isEqualTo("outer");
        assertThat(inner.get()).isEqualTo("inner");
    }

    @Test
    void invalid_tenant_id_rejected_at_binding_time() {
        assertThatThrownBy(() -> TenantContext.runIn("Bad Id", () -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exception_inside_scope_still_clears_binding() {
        try {
            TenantContext.runIn("acme", () -> {
                throw new RuntimeException("boom");
            });
        } catch (RuntimeException expected) {
            // ignore
        }
        assertThat(TenantContext.isBound()).isFalse();
    }
}
