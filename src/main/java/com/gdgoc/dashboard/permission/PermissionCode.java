package com.gdgoc.dashboard.permission;

import com.gdgoc.dashboard.member.RoleName;

import java.util.Set;

public enum PermissionCode {
    // Shareable Permissions
    BIO_DATA_EDIT_HR_TEAM(true, Set.of(RoleName.HR_MEMBER)),
    VIEW_TEAM_STATS(true, Set.of()), // Across All Teams
    VIEW_PAST_MEMBERS(true, Set.of()), // Across All Teams
    MONITOR_COLLABORATIONS(true, Set.of()), // Across All Teams
    VIEW_MEMBER_DETAILS(true, Set.of(RoleName.HR_MEMBER)),
    CREATE_CROSS_TEAM_COLLAB(true, Set.of(RoleName.HR_MEMBER)),
    ASSIGN_TASK_INTRA_TEAM(true, Set.of()),
    ASSIGN_TASK_CROSS_TEAM(true, Set.of()),
    DASHBOARD_ACTION_ACCESS(true, Set.of()),
    EVENT_MANAGEMENT(true, Set.of(RoleName.EVENT_TEAM_MEMBER)),

    // Non-Shareable Permissions (Permitted Access Sharing: None / Requires All 3 Leads)
    SUPER_ADMIN(false, Set.of()),
    GRANT_PERMISSIONS(false, Set.of()),
    TEAM_LIFECYCLE_CONSENSUS(false, Set.of()),
    PROJECT_CONTRIBUTOR_AGREEMENT(false, Set.of()),
    ASSIGN_TASK_TO_TEAM_LEAD(false, Set.of());

    private final boolean shareable;
    private final Set<RoleName> requiredTargetRoles;

    PermissionCode(boolean shareable, Set<RoleName> requiredTargetRoles) {
        this.shareable = shareable;
        this.requiredTargetRoles = requiredTargetRoles;
    }

    public boolean isShareable() {
        return shareable;
    }

    public Set<RoleName> getRequiredTargetRoles() {
        return requiredTargetRoles;
    }
}
