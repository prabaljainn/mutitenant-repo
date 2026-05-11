package com.orochiverse.platform.iam.admin.stats;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.orochiverse.platform.iam.admin.stats.StatsDtos.OverviewStats;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Aggregate counters for the admin Overview screen. One endpoint that
 * returns everything the dashboard renders above the fold.
 *
 * <p>Open to any operator — there is nothing tenant-specific or
 * sensitive in the numbers, and operator-support reads this same page.
 */
@RestController
@RequestMapping("/admin/api/stats")
@ConditionalOnProperty(prefix = "spring.data.mongodb", name = "uri")
@Tag(name = "Operator: Stats", description = "Aggregate counters for the admin "
        + "Overview dashboard. One call returns tenant count, tenant-user "
        + "count, and pending invites.")
public class StatsAdminController {

    private final StatsAdminService service;

    public StatsAdminController(StatsAdminService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasRole('OPERATOR')")
    public OverviewStats overview() {
        return service.overview();
    }
}
