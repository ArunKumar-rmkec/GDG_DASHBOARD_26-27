package com.gdgoc.dashboard.member;

public enum RoleName {
    GDG_LEAD,
    HR_LEAD,
    TEAM_LEAD,
    EVENT_LEAD,
    HR_MEMBER,
    EVENT_TEAM_MEMBER,
    TEAM_MEMBER;

    public boolean isLeadRole() {
        return this == GDG_LEAD || this == HR_LEAD || this == TEAM_LEAD || this == EVENT_LEAD;
    }
}
