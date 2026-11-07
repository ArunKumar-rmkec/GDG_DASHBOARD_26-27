package com.gdgoc.dashboard.security;

import com.gdgoc.dashboard.event.EventLeadAssignmentRepository;
import com.gdgoc.dashboard.event.EventRole;
import com.gdgoc.dashboard.member.MemberStatus;
import com.gdgoc.dashboard.member.RoleName;
import com.gdgoc.dashboard.permission.GrantStatus;
import com.gdgoc.dashboard.permission.PermissionCode;
import com.gdgoc.dashboard.permission.PermissionGrant;
import com.gdgoc.dashboard.permission.PermissionGrantRepository;
import com.gdgoc.dashboard.team.Team;
import com.gdgoc.dashboard.team.TeamMembership;
import com.gdgoc.dashboard.team.TeamMembershipRepository;
import com.gdgoc.dashboard.team.TeamRepository;
import com.gdgoc.dashboard.team.TeamRole;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component("clearance")
public class ClearanceEvaluator {

    private final PermissionGrantRepository permissionGrantRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final TeamRepository teamRepository;
    private final EventLeadAssignmentRepository eventLeadAssignmentRepository;

    public ClearanceEvaluator(
            PermissionGrantRepository permissionGrantRepository,
            TeamMembershipRepository teamMembershipRepository,
            TeamRepository teamRepository,
            EventLeadAssignmentRepository eventLeadAssignmentRepository
    ) {
        this.permissionGrantRepository = permissionGrantRepository;
        this.teamMembershipRepository = teamMembershipRepository;
        this.teamRepository = teamRepository;
        this.eventLeadAssignmentRepository = eventLeadAssignmentRepository;
    }

    public UserPrincipal getPrincipal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return null;
        }
        return principal;
    }

    public boolean isActive(Authentication authentication) {
        UserPrincipal user = getPrincipal(authentication);
        return user != null && user.getStatus() == MemberStatus.ACTIVE;
    }

    public boolean isGdgLead(Authentication authentication) {
        UserPrincipal user = getPrincipal(authentication);
        return user != null && user.hasRole(RoleName.GDG_LEAD);
    }

    public boolean isAnyLead(Authentication authentication) {
        UserPrincipal user = getPrincipal(authentication);
        return user != null && user.isAnyLead();
    }

    public boolean canGrantPermissions(Authentication authentication) {
        // Sheet 1 Row 3: Lead roles ONLY, Permitted Access Sharing = None
        return isAnyLead(authentication);
    }

    public boolean canManageTeamMembers(Authentication authentication, Long teamId) {
        UserPrincipal user = getPrincipal(authentication);
        if (user == null || user.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        if (user.hasRole(RoleName.GDG_LEAD) || user.hasRole(RoleName.HR_LEAD)) {
            return true;
        }
        return isTeamLeadForTeam(user, teamId);
    }

    public boolean isTeamLeadForTeam(UserPrincipal user, Long teamId) {
        if (user == null || user.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        if (user.hasRole(RoleName.GDG_LEAD)) {
            return true;
        }
        if (teamId == null) {
            return user.hasRole(RoleName.TEAM_LEAD);
        }

        // Check direct membership on teamId
        Optional<TeamMembership> directMembership = teamMembershipRepository.findFirstByTeamIdAndMemberIdAndActiveTrue(teamId, user.getId());
        if (directMembership.isPresent()) {
            TeamRole tRole = directMembership.get().getTeamRole();
            if (tRole == TeamRole.TEAM_LEAD || tRole == TeamRole.SUB_TEAM_LEAD || user.hasRole(RoleName.TEAM_LEAD)) {
                return true;
            }
        }

        // Check parent team membership if teamId is a sub-team
        Optional<Team> teamOpt = teamRepository.findById(teamId);
        if (teamOpt.isPresent() && teamOpt.get().getParentTeamId() != null) {
            Long parentId = teamOpt.get().getParentTeamId();
            Optional<TeamMembership> parentMembership = teamMembershipRepository.findFirstByTeamIdAndMemberIdAndActiveTrue(parentId, user.getId());
            if (parentMembership.isPresent()) {
                TeamRole pRole = parentMembership.get().getTeamRole();
                if (pRole == TeamRole.TEAM_LEAD || user.hasRole(RoleName.TEAM_LEAD)) {
                    return true;
                }
            }
        }

        // Fallback if user has TEAM_LEAD system role and is NOT bound exclusively to another team while target team has no dedicated lead
        if (user.hasRole(RoleName.TEAM_LEAD)) {
            List<TeamMembership> userMemberships = teamMembershipRepository.findByMemberIdAndActiveTrue(user.getId());
            List<TeamMembership> teamMemberships = teamMembershipRepository.findByTeamIdAndActiveTrue(teamId);
            boolean teamHasLead = teamMemberships.stream().anyMatch(m -> m.getTeamRole() == TeamRole.TEAM_LEAD);
            if (userMemberships.isEmpty() && !teamHasLead) {
                return true;
            }
        }

        return false;
    }

    public boolean canManageBioDataDirect(Authentication authentication) {
        UserPrincipal user = getPrincipal(authentication);
        return user != null && (user.hasRole(RoleName.GDG_LEAD) || user.hasRole(RoleName.HR_LEAD));
    }

    public boolean canInitiateBioDataOrMemberChange(Authentication authentication) {
        UserPrincipal user = getPrincipal(authentication);
        if (user == null || user.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        if (canManageBioDataDirect(authentication) || user.hasRole(RoleName.HR_MEMBER)) {
            return true;
        }
        return hasActiveGrant(user.getId(), user.getRoles(), PermissionCode.BIO_DATA_EDIT_HR_TEAM, null, null)
                || hasActiveGrant(user.getId(), user.getRoles(), PermissionCode.DASHBOARD_ACTION_ACCESS, null, null);
    }

    public boolean canApproveChangeRequest(Authentication authentication) {
        UserPrincipal user = getPrincipal(authentication);
        if (user == null || user.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        return user.hasRole(RoleName.GDG_LEAD)
                || user.hasRole(RoleName.HR_LEAD)
                || user.hasRole(RoleName.TEAM_LEAD);
    }

    public boolean canTerminateMember(Authentication authentication) {
        UserPrincipal user = getPrincipal(authentication);
        return user != null && (user.hasRole(RoleName.GDG_LEAD) || user.hasRole(RoleName.HR_LEAD));
    }

    public boolean canViewTeamStats(Authentication authentication) {
        UserPrincipal user = getPrincipal(authentication);
        if (user == null || user.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        if (user.isAnyLead() || user.hasRole(RoleName.HR_MEMBER)) {
            return true;
        }
        return hasActiveGrant(user.getId(), user.getRoles(), PermissionCode.VIEW_TEAM_STATS, null, null)
                || hasActiveGrant(user.getId(), user.getRoles(), PermissionCode.DASHBOARD_ACTION_ACCESS, null, null);
    }

    public boolean canViewPastMembers(Authentication authentication) {
        UserPrincipal user = getPrincipal(authentication);
        if (user == null || user.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        if (user.isAnyLead() || user.hasRole(RoleName.HR_MEMBER)) {
            return true;
        }
        return hasActiveGrant(user.getId(), user.getRoles(), PermissionCode.VIEW_PAST_MEMBERS, null, null)
                || hasActiveGrant(user.getId(), user.getRoles(), PermissionCode.DASHBOARD_ACTION_ACCESS, null, null);
    }

    public boolean canMonitorCollaborations(Authentication authentication) {
        UserPrincipal user = getPrincipal(authentication);
        if (user == null || user.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        if (user.isAnyLead() || user.hasRole(RoleName.HR_MEMBER)) {
            return true;
        }
        return hasActiveGrant(user.getId(), user.getRoles(), PermissionCode.MONITOR_COLLABORATIONS, null, null)
                || hasActiveGrant(user.getId(), user.getRoles(), PermissionCode.DASHBOARD_ACTION_ACCESS, null, null);
    }

    public boolean canCreateCollaboration(Authentication authentication) {
        UserPrincipal user = getPrincipal(authentication);
        if (user == null || user.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        if (user.isAnyLead() || user.hasRole(RoleName.HR_MEMBER)) {
            return true;
        }
        return hasActiveGrant(user.getId(), user.getRoles(), PermissionCode.CREATE_CROSS_TEAM_COLLAB, null, null)
                || hasActiveGrant(user.getId(), user.getRoles(), PermissionCode.DASHBOARD_ACTION_ACCESS, null, null);
    }

    public boolean canViewMemberDetails(Authentication authentication) {
        UserPrincipal user = getPrincipal(authentication);
        if (user == null || user.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        if (user.isAnyLead() || user.hasRole(RoleName.HR_MEMBER)) {
            return true;
        }
        return hasActiveGrant(user.getId(), user.getRoles(), PermissionCode.VIEW_MEMBER_DETAILS, null, null)
                || hasActiveGrant(user.getId(), user.getRoles(), PermissionCode.DASHBOARD_ACTION_ACCESS, null, null);
    }

    public boolean canManageAgreements(Authentication authentication) {
        // Sheet 1 Row 11: GDG_LEAD and HR_LEAD ONLY, Permitted Access Sharing = None
        UserPrincipal user = getPrincipal(authentication);
        return user != null && (user.hasRole(RoleName.GDG_LEAD) || user.hasRole(RoleName.HR_LEAD));
    }

    public boolean canIssueLetters(Authentication authentication) {
        UserPrincipal user = getPrincipal(authentication);
        return user != null && (user.hasRole(RoleName.GDG_LEAD) || user.hasRole(RoleName.HR_LEAD));
    }

    public boolean canManageEvents(Authentication authentication) {
        UserPrincipal user = getPrincipal(authentication);
        if (user == null || user.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        if (user.hasRole(RoleName.GDG_LEAD) || user.hasRole(RoleName.EVENT_LEAD) || user.hasRole(RoleName.EVENT_TEAM_MEMBER)) {
            return true;
        }
        if (hasActiveGrant(user.getId(), user.getRoles(), PermissionCode.EVENT_MANAGEMENT, null, null)
                || hasActiveGrant(user.getId(), user.getRoles(), PermissionCode.DASHBOARD_ACTION_ACCESS, null, null)) {
            return true;
        }
        return eventLeadAssignmentRepository.existsByMemberId(user.getId());
    }

    public boolean canManageEvent(Authentication authentication, Long eventId) {
        UserPrincipal user = getPrincipal(authentication);
        if (user == null || user.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        if (user.hasRole(RoleName.GDG_LEAD) || user.hasRole(RoleName.EVENT_LEAD) || user.hasRole(RoleName.EVENT_TEAM_MEMBER)) {
            return true;
        }
        if (hasActiveGrant(user.getId(), user.getRoles(), PermissionCode.EVENT_MANAGEMENT, null, eventId)
                || hasActiveGrant(user.getId(), user.getRoles(), PermissionCode.DASHBOARD_ACTION_ACCESS, null, eventId)) {
            return true;
        }
        if (eventId != null && eventLeadAssignmentRepository.existsByEventIdAndMemberId(eventId, user.getId())) {
            return true;
        }
        return false;
    }

    public boolean canAssignEventLead(Authentication authentication, Long eventId) {
        UserPrincipal user = getPrincipal(authentication);
        if (user == null || user.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        if (user.hasRole(RoleName.GDG_LEAD) || user.hasRole(RoleName.EVENT_LEAD)) {
            return true;
        }
        if (hasActiveGrant(user.getId(), user.getRoles(), PermissionCode.EVENT_MANAGEMENT, null, eventId)) {
            return true;
        }
        if (eventId != null && eventLeadAssignmentRepository.existsByEventIdAndMemberIdAndEventRole(eventId, user.getId(), EventRole.EVENT_LEAD)) {
            return true;
        }
        return false;
    }

    public boolean hasActiveGrant(Long memberId, Set<RoleName> memberRoles, PermissionCode code, Long teamId, Long eventId) {
        if (memberId == null) {
            return false;
        }
        Set<RoleName> safeRoles = (memberRoles == null || memberRoles.isEmpty())
                ? Set.of(RoleName.TEAM_MEMBER)
                : memberRoles;
        Instant now = Instant.now();
        List<PermissionGrant> grants = permissionGrantRepository.findActiveGrantsForMemberOrRoles(memberId, safeRoles);
        return grants.stream().anyMatch(g ->
                g.getPermissionCode() == code
                && g.getStatus() == GrantStatus.ACTIVE
                && !g.isRevoked()
                && !g.getValidFrom().isAfter(now)
                && (g.getValidUntil() == null || g.getValidUntil().isAfter(now))
                && (teamId == null || g.getScopeTeamId() == null || g.getScopeTeamId().equals(teamId)
                    || (g.getCrossTeamTargetTeamId() != null && g.getCrossTeamTargetTeamId().equals(teamId)))
                && (eventId == null || g.getScopeEventId() == null || g.getScopeEventId().equals(eventId))
        );
    }
}
