package com.orochiverse.platform.common.tenant;

import java.util.Optional;

/**
 * Carries the currently-active tenant ID for the duration of a request (or any
 * other unit of work) using a Java 25 {@link ScopedValue}.
 *
 * <h2>Why {@code ScopedValue} and not {@code ThreadLocal}?</h2>
 * <ul>
 *   <li><b>No leak risk.</b> A {@code ScopedValue} binding lives only for the
 *       duration of the {@code run}/{@code call} block; there is no
 *       {@code remove()} to forget. With virtual threads this matters: pooling
 *       semantics don't apply, but stray {@code ThreadLocal} state can still
 *       cross test/request boundaries through frameworks that reuse carrier
 *       threads or invoke continuations.</li>
 *   <li><b>Immutable inside the scope.</b> Code in the scope cannot
 *       {@code set()} a different value — it must open a nested binding,
 *       which is explicit and reviewable.</li>
 *   <li><b>Cheap.</b> Bindings are stack-shaped and don't pay the
 *       per-thread-local map cost.</li>
 * </ul>
 *
 * <h2>Cross-thread propagation</h2>
 * Scoped values are <em>not</em> auto-inherited by arbitrary background
 * threads. To propagate them to forked tasks, use
 * {@code StructuredTaskScope} (Java 25 preview). For Spring's {@code @Async}
 * we'll wire a {@code TaskDecorator} in Phase 1.9 that captures the current
 * tenant ID and re-binds it in the async thread.
 */
public final class TenantContext {

    /** Internal storage. Not exposed directly so all access goes through this class. */
    static final ScopedValue<String> CURRENT = ScopedValue.newInstance();

    private TenantContext() {}

    /**
     * Returns the active tenant ID or throws {@link MissingTenantContextException}.
     * Use this in tenant-scoped code that has no meaning outside a tenant context.
     */
    public static String requireCurrent() {
        if (!CURRENT.isBound()) {
            throw new MissingTenantContextException();
        }
        return CURRENT.get();
    }

    /**
     * Returns the active tenant ID if one is bound, otherwise empty.
     * Use this in code that has legitimate cross-tenant or admin paths
     * (e.g. operator-level APIs).
     */
    public static Optional<String> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /** True iff a tenant ID is currently bound on this thread's scope stack. */
    public static boolean isBound() {
        return CURRENT.isBound();
    }

    /**
     * Run {@code work} inside a tenant-bound scope. The binding is in place for
     * the entire dynamic extent of the call and is cleared automatically when
     * {@code work} returns or throws.
     */
    public static void runIn(String tenantId, Runnable work) {
        ScopedValue.where(CURRENT, TenantId.requireValid(tenantId)).run(work);
    }

    /**
     * Like {@link #runIn} but returns a value.
     *
     * The {@link ScopedValue.CallableOp} signature lets callers declare the
     * specific checked exception their work throws, instead of forcing a
     * blanket {@code throws Exception} on every caller.
     *
     * @throws X whatever {@code work} throws (no wrapping)
     */
    public static <R, X extends Throwable> R callIn(String tenantId, ScopedValue.CallableOp<R, X> work) throws X {
        return ScopedValue.where(CURRENT, TenantId.requireValid(tenantId)).call(work);
    }
}
