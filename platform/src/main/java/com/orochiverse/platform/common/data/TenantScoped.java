package com.orochiverse.platform.common.data;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a repository (or other data-access type) as operating against the
 * <em>current</em> tenant's database — resolved through
 * {@link com.orochiverse.platform.common.tenant.TenantMongoTemplateRegistry}.
 *
 * <p>Tenant-scoped repositories must NOT be Spring Data {@code MongoRepository}
 * interfaces (those bind to the autoconfigured {@code MongoTemplate}, which
 * points at {@code iam_db}, which would silently leak tenant data into the
 * shared DB). Hand-roll them as plain Spring components that call
 * {@code registry.forCurrentTenant()} per operation.
 *
 * <p>Phase 1.4 ships only IAM repositories; tenant-scoped repos arrive with
 * the GCS modules in M2+. The annotation exists now so the
 * {@code RepositoryDisciplineTest} can reason about it.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface TenantScoped {}
