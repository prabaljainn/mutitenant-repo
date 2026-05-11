package com.orochiverse.platform.iam.users;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
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
}
