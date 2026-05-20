package com.orochiverse.platform.iam.users;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import com.orochiverse.platform.common.data.IamScoped;
import com.orochiverse.platform.common.security.principals.UserKind;

/**
 * Spring Data Mongo repository for {@link User} living in {@code iam_db.users}.
 *
 * <p>Spring's autoconfigured {@code MongoTemplate} (driven by
 * {@code spring.data.mongodb.uri}) targets {@code iam_db}, so this interface
 * automatically resolves there. Tenant-scoped data must NOT use
 * {@code MongoRepository}; see {@link com.orochiverse.platform.common.data.TenantScoped}.
 */
@Repository
@IamScoped
public interface UserRepository extends MongoRepository<User, String> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<User> findAllByKindAndStatus(UserKind kind, UserStatus status);

    /** Operator-side listing — find all tenant users for a given tenant. */
    List<User> findAllByTenantIdAndStatus(String tenantId, UserStatus status);

    long countByTenantId(String tenantId);

    long countByKindAndStatus(UserKind kind, UserStatus status);

    long countByStatus(UserStatus status);

    /** Tenant-scoped variants — power overview counts for SUPPORT operators. */
    long countByKindAndStatusAndTenantIdIn(UserKind kind, UserStatus status,
                                           Collection<String> tenantIds);

    long countByKindAndTenantIdIn(UserKind kind, Collection<String> tenantIds);

    /**
     * Cross-tenant search across operators + tenant users. Soft-deleted
     * rows are excluded (their email is scrambled so they wouldn't match
     * a real email lookup anyway, but a name-substring search would still
     * surface them without this filter).
     *
     * <p>{@code ?0} is treated as a regex by Mongo. Callers MUST escape
     * user input with {@link java.util.regex.Pattern#quote(String)} to
     * keep the search literal — otherwise a query of {@code "."} would
     * match every user.
     */
    @Query("{ status: { $ne: 'DELETED' }, $or: ["
            + " { email:     { $regex: ?0, $options: 'i' } },"
            + " { firstName: { $regex: ?0, $options: 'i' } },"
            + " { lastName:  { $regex: ?0, $options: 'i' } } ] }")
    List<User> searchAll(String pattern, Pageable pageable);

    /**
     * SUPPORT-scoped variant: only tenant users in the supplied tenant set.
     * Operators are excluded entirely — SUPPORT cannot enumerate other
     * operators via this surface.
     */
    @Query("{ status: { $ne: 'DELETED' }, kind: 'TENANT_USER', tenantId: { $in: ?1 }, $or: ["
            + " { email:     { $regex: ?0, $options: 'i' } },"
            + " { firstName: { $regex: ?0, $options: 'i' } },"
            + " { lastName:  { $regex: ?0, $options: 'i' } } ] }")
    List<User> searchScopedToTenantUsers(String pattern, Collection<String> tenantIds, Pageable pageable);
}
