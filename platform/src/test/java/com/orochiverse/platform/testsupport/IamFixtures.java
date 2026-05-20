package com.orochiverse.platform.testsupport;

import java.time.Instant;
import java.util.UUID;

import com.orochiverse.platform.common.security.passwords.PasswordHashing;
import com.orochiverse.platform.common.security.principals.OperatorRole;
import com.orochiverse.platform.common.security.principals.TenantRole;
import com.orochiverse.platform.common.security.principals.UserKind;
import com.orochiverse.platform.iam.tenants.Tenant;
import com.orochiverse.platform.iam.tenants.TenantRepository;
import com.orochiverse.platform.iam.users.User;
import com.orochiverse.platform.iam.users.UserRepository;
import com.orochiverse.platform.iam.users.UserStatus;

/**
 * Fluent builders for the {@link User} / {@link Tenant} fixtures every IT
 * needs at {@code @BeforeEach}.
 *
 * <h2>Naming convention</h2>
 * IDs and emails are derived from the {@code suffix} parameter so two
 * concurrent test runs hitting the same dev Mongo won't collide. Tests
 * are responsible for cleanup via the returned {@link User#id()}.
 */
public final class IamFixtures {

    public static final String DEFAULT_PASSWORD = "Sup3rSecret!";

    private IamFixtures() {}

    public static String randomSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public static OperatorBuilder operator(String suffix) {
        return new OperatorBuilder(suffix);
    }

    public static TenantUserBuilder tenantUser(String suffix, String tenantId) {
        return new TenantUserBuilder(suffix, tenantId);
    }

    public static TenantBuilder tenant(String idPrefix, String suffix) {
        return new TenantBuilder(idPrefix, suffix);
    }

    // ────────────────────────────────────────────────────────────────────
    // Operator
    // ────────────────────────────────────────────────────────────────────

    public static final class OperatorBuilder {
        private final String suffix;
        private String id;
        private String email;
        private String password = DEFAULT_PASSWORD;
        private String firstName = "Op";
        private String lastName = "Admin";
        private OperatorRole role = OperatorRole.OPERATOR_ADMIN;
        private UserStatus status = UserStatus.ACTIVE;

        OperatorBuilder(String suffix) {
            this.suffix = suffix;
            this.id = "op-" + suffix;
            this.email = "op-" + suffix + "@orochi.example";
        }

        public OperatorBuilder id(String v) { this.id = v; return this; }
        public OperatorBuilder email(String v) { this.email = v; return this; }
        public OperatorBuilder password(String v) { this.password = v; return this; }
        public OperatorBuilder firstName(String v) { this.firstName = v; return this; }
        public OperatorBuilder lastName(String v) { this.lastName = v; return this; }
        public OperatorBuilder role(OperatorRole v) { this.role = v; return this; }
        public OperatorBuilder status(UserStatus v) { this.status = v; return this; }
        public OperatorBuilder noPassword() { this.password = null; return this; }

        public User save(UserRepository users, PasswordHashing passwords) {
            var now = Instant.now();
            String hash = password == null ? null : passwords.hash(password);
            var u = new User(id, email, hash, firstName, lastName,
                    status, UserKind.OPERATOR, role, null, null, 0, null, now, now);
            users.save(u);
            return u;
        }

        public User build() {
            var now = Instant.now();
            return new User(id, email, password == null ? null : password,
                    firstName, lastName, status, UserKind.OPERATOR, role,
                    null, null, 0, null, now, now);
        }

        public String suffix() { return suffix; }
    }

    // ────────────────────────────────────────────────────────────────────
    // Tenant user
    // ────────────────────────────────────────────────────────────────────

    public static final class TenantUserBuilder {
        private final String suffix;
        private final String tenantId;
        private String id;
        private String email;
        private String password = DEFAULT_PASSWORD;
        private String firstName = "Tenant";
        private String lastName = "User";
        private TenantRole role = TenantRole.ADMIN;
        private UserStatus status = UserStatus.ACTIVE;

        TenantUserBuilder(String suffix, String tenantId) {
            this.suffix = suffix;
            this.tenantId = tenantId;
            this.id = "tu-" + suffix;
            this.email = "tu-" + suffix + "@tenant.example";
        }

        public TenantUserBuilder id(String v) { this.id = v; return this; }
        public TenantUserBuilder email(String v) { this.email = v; return this; }
        public TenantUserBuilder password(String v) { this.password = v; return this; }
        public TenantUserBuilder firstName(String v) { this.firstName = v; return this; }
        public TenantUserBuilder lastName(String v) { this.lastName = v; return this; }
        public TenantUserBuilder role(TenantRole v) { this.role = v; return this; }
        public TenantUserBuilder status(UserStatus v) { this.status = v; return this; }
        public TenantUserBuilder noPassword() { this.password = null; return this; }

        public User save(UserRepository users, PasswordHashing passwords) {
            var now = Instant.now();
            String hash = password == null ? null : passwords.hash(password);
            var u = new User(id, email, hash, firstName, lastName,
                    status, UserKind.TENANT_USER, null,
                    tenantId, role, 0, null, now, now);
            users.save(u);
            return u;
        }

        public User build() {
            var now = Instant.now();
            return new User(id, email, password,
                    firstName, lastName, status, UserKind.TENANT_USER, null,
                    tenantId, role, 0, null, now, now);
        }

        public String suffix() { return suffix; }
    }

    // ────────────────────────────────────────────────────────────────────
    // Tenant
    // ────────────────────────────────────────────────────────────────────

    public static final class TenantBuilder {
        private String id;
        private String name;
        private String createdBy = "system";
        private String ownerUserId;

        TenantBuilder(String idPrefix, String suffix) {
            this.id = idPrefix + suffix;
            this.name = "Acme " + suffix;
        }

        public TenantBuilder id(String v) { this.id = v; return this; }
        public TenantBuilder name(String v) { this.name = v; return this; }
        public TenantBuilder createdBy(String v) { this.createdBy = v; return this; }
        public TenantBuilder ownerUserId(String v) { this.ownerUserId = v; return this; }

        public Tenant save(TenantRepository tenants) {
            var t = Tenant.create(id, name, createdBy);
            if (ownerUserId != null) {
                t = t.withOwner(ownerUserId);
            }
            tenants.save(t);
            return t;
        }
    }
}
