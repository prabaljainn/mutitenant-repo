package com.orochiverse.platform.common.data;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a repository (or other data-access type) as operating against the
 * shared {@code iam_db} — i.e. data that is global to the platform: users,
 * tenants, operator assignments, audit log.
 *
 * <p>Counterpart of {@link TenantScoped}. The annotation is informational
 * (used by code review and {@code RepositoryDisciplineTest}) rather than
 * runtime-enforced — Spring's autoconfigured {@code MongoTemplate} already
 * points at {@code iam_db}, so any {@code MongoRepository} interface
 * resolves to that DB by default.
 *
 * <p>If you find yourself wanting to put this on something that would also
 * make sense as {@link TenantScoped}, you have a design problem: the data
 * is either platform-wide or per-tenant, never both.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface IamScoped {}
