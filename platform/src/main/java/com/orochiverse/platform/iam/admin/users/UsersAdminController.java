package com.orochiverse.platform.iam.admin.users;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orochiverse.platform.iam.admin.users.UserSearchDtos.UserSearchResult;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * {@code GET /admin/api/users/search?q=…&limit=…} — cross-tenant user
 * lookup keyed by email or name. Returns operators + tenant users in one
 * pass so support can find any user matching whatever a customer pasted
 * into a ticket.
 *
 * <p>SUPPORT operators see only tenant users for their assigned tenants
 * — never operators, never users from other tenants. ADMIN sees everything.
 * Scoping is enforced server-side in {@link UsersAdminService}.
 */
@RestController
@RequestMapping("/admin/api/users")
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
@Tag(name = "Operator: Users", description = "Cross-tenant user search "
        + "across operators and tenant users. Scoped server-side by role.")
public class UsersAdminController {

    private final UsersAdminService service;

    public UsersAdminController(UsersAdminService service) {
        this.service = service;
    }

    @GetMapping("/search")
    @PreAuthorize("hasRole('OPERATOR')")
    public List<UserSearchResult> search(@RequestParam("q") String q,
                                         @RequestParam(value = "limit", required = false) Integer limit) {
        return service.search(q, limit);
    }
}
