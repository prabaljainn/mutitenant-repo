package com.orochiverse.platform.iam.admin.users;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.orochiverse.platform.iam.admin.common.OperatorVisibility;
import com.orochiverse.platform.iam.admin.users.UserSearchDtos.UserSearchResult;
import com.orochiverse.platform.iam.users.User;
import com.orochiverse.platform.iam.users.UserRepository;

/**
 * Cross-tenant user lookup for the {@code /admin/api/users/search} surface.
 *
 * <p>Two visibility modes:
 * <ul>
 *   <li>ADMIN — unrestricted: operators + tenant users from every tenant.</li>
 *   <li>SUPPORT — only tenant users from the operator's assigned tenants;
 *       operators are never returned. Mirrors the existing per-tenant
 *       visibility model rather than introducing a separate "operator
 *       directory" permission.</li>
 * </ul>
 *
 * <p>Soft-deleted users are filtered at the repository layer.
 */
@Service
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
public class UsersAdminService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    /** Block 1-char queries — they'd return ~the whole user table. */
    static final int MIN_QUERY_LENGTH = 2;

    private final UserRepository users;
    private final OperatorVisibility visibility;

    public UsersAdminService(UserRepository users, OperatorVisibility visibility) {
        this.users = users;
        this.visibility = visibility;
    }

    public List<UserSearchResult> search(String q, Integer limit) {
        String trimmed = q == null ? "" : q.trim();
        if (trimmed.length() < MIN_QUERY_LENGTH) {
            return List.of();
        }
        int cap = Math.min(limit == null ? DEFAULT_LIMIT : Math.max(1, limit), MAX_LIMIT);
        // Pattern.quote wraps in \Q…\E so user input like "." or "["
        // matches literally instead of as regex meta.
        String regex = Pattern.quote(trimmed);
        var pageable = PageRequest.of(0, cap);

        Set<String> allowedTenantIds = visibility.visibleTenantIdsOrUnrestricted();
        List<User> rows;
        if (allowedTenantIds == null) {
            rows = users.searchAll(regex, pageable);
        } else if (allowedTenantIds.isEmpty()) {
            return List.of();
        } else {
            rows = users.searchScopedToTenantUsers(regex, allowedTenantIds, pageable);
        }
        return rows.stream().map(UserSearchResult::from).toList();
    }
}
