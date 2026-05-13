/**
 * Cross-cutting infrastructure shared by all modules:
 * security, tenant context, multi-tenant Mongo wiring, audit, exception
 * handling. Anything in {@code iam} or {@code tenant} may depend on this
 * package; this package may not depend on either of them.
 */
package com.orochiverse.platform.common;
