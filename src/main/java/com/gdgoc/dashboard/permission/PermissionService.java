package com.gdgoc.dashboard.permission;

import com.gdgoc.dashboard.common.BadRequestException;
import com.gdgoc.dashboard.common.ForbiddenException;
import com.gdgoc.dashboard.common.ResourceNotFoundException;
import com.gdgoc.dashboard.member.Member;
import com.gdgoc.dashboard.member.MemberRepository;
import com.gdgoc.dashboard.member.MemberStatus;
import com.gdgoc.dashboard.member.RoleName;
import com.gdgoc.dashboard.permission.PermissionDtos.*;
import com.gdgoc.dashboard.security.ClearanceEvaluator;
import com.gdgoc.dashboard.security.UserPrincipal;
import com.gdgoc.dashboard.team.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class PermissionService {

    private final PermissionGrantRepository permissionGrantRepository;
    private final MemberRepository memberRepository;
    private final TeamRepository teamRepository;
    private final ClearanceEvaluator clearanceEvaluator;

    public PermissionService(
            PermissionGrantRepository permissionGrantRepository,
            MemberRepository memberRepository,
            TeamRepository teamRepository,
            ClearanceEvaluator clearanceEvaluator
    ) {
        this.permissionGrantRepository = permissionGrantRepository;
        this.memberRepository = memberRepository;
        this.teamRepository = teamRepository;
        this.clearanceEvaluator = clearanceEvaluator;
    }

    @Transactional
    public PermissionGrantDTO createGrant(CreatePermissionGrantRequestDTO req, UserPrincipal actor) {
        if (req.permissionCode() == null) {
            throw new BadRequestException("permissionCode is required");
        }
        if (!req.permissionCode().isShareable()) {
            throw new BadRequestException("Permission " + req.permissionCode() + " cannot be shared or delegated (Permitted Access Sharing: None)");
        }
        if (req.granteeMemberId() == null && req.granteeRole() == null) {
            throw new BadRequestException("Either granteeMemberId or granteeRole must be provided");
        }

        Set<RoleName> requiredRoles = req.permissionCode().getRequiredTargetRoles();
        if (req.granteeMemberId() != null) {
            Member target = memberRepository.findByIdWithRoles(req.granteeMemberId())
                    .orElseThrow(() -> new ResourceNotFoundException("Grantee member not found: " + req.granteeMemberId()));
            if (target.getStatus() == MemberStatus.TERMINATED || target.getStatus() == MemberStatus.INACTIVE) {
                throw new BadRequestException("Cannot grant permissions to a terminated or inactive member");
            }
            if (!requiredRoles.isEmpty()) {
                boolean hasRequiredRole = target.getRoles().stream().anyMatch(requiredRoles::contains);
                if (!hasRequiredRole) {
                    throw new BadRequestException("Permission " + req.permissionCode() + " can only be shared with roles: " + requiredRoles);
                }
            }
        } else if (req.granteeRole() != null && !requiredRoles.isEmpty()) {
            if (!requiredRoles.contains(req.granteeRole())) {
                throw new BadRequestException("Permission " + req.permissionCode() + " can only be shared with roles: " + requiredRoles);
            }
        }

        Instant validFrom = req.validFrom() != null ? req.validFrom() : Instant.now();
        Instant validUntil = req.validUntil();
        if (validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new BadRequestException("validUntil must be strictly after validFrom");
        }

        if (req.scopeTeamId() != null && !teamRepository.existsById(req.scopeTeamId())) {
            throw new BadRequestException("Scope team not found: " + req.scopeTeamId());
        }
        if (req.crossTeamTargetTeamId() != null && !teamRepository.existsById(req.crossTeamTargetTeamId())) {
            throw new BadRequestException("Cross-team target team not found: " + req.crossTeamTargetTeamId());
        }

        GrantStatus initialStatus = GrantStatus.ACTIVE;
        if (req.crossTeamTargetTeamId() != null) {
            boolean isGdgLead = actor.hasRole(RoleName.GDG_LEAD);
            boolean isTargetTeamLead = clearanceEvaluator.isTeamLeadForTeam(actor, req.crossTeamTargetTeamId());
            if (!isGdgLead && !isTargetTeamLead) {
                initialStatus = GrantStatus.PENDING_TEAM_LEAD_APPROVAL;
            }
        }

        PermissionGrant grant = PermissionGrant.builder()
                .grantedById(actor.getId())
                .granteeMemberId(req.granteeMemberId())
                .granteeRole(req.granteeRole())
                .permissionCode(req.permissionCode())
                .scopeTeamId(req.scopeTeamId())
                .scopeEventId(req.scopeEventId())
                .crossTeamTargetTeamId(req.crossTeamTargetTeamId())
                .validFrom(validFrom)
                .validUntil(validUntil)
                .status(initialStatus)
                .revoked(false)
                .reason(req.reason())
                .build();

        grant = permissionGrantRepository.save(grant);
        return toDto(grant);
    }

    @Transactional
    public PermissionGrantDTO approveCrossTeamGrant(Long grantId, ApproveGrantRequestDTO req, UserPrincipal actor) {
        PermissionGrant grant = permissionGrantRepository.findById(grantId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission grant not found: " + grantId));

        Long targetTeamId = grant.getCrossTeamTargetTeamId() != null ? grant.getCrossTeamTargetTeamId() : grant.getScopeTeamId();
        if (!actor.hasRole(RoleName.GDG_LEAD) && !clearanceEvaluator.isTeamLeadForTeam(actor, targetTeamId)) {
            throw new ForbiddenException("Only the target team's TEAM_LEAD or a GDG_LEAD can approve this cross-team permission grant");
        }

        grant.setApprovedByTeamLeadId(actor.getId());
        grant.setApprovedAt(Instant.now());
        grant.setApprovalComment(req.comment());
        if (Boolean.TRUE.equals(req.approved())) {
            grant.setStatus(GrantStatus.ACTIVE);
        } else {
            grant.setStatus(GrantStatus.REJECTED);
        }

        grant = permissionGrantRepository.save(grant);
        return toDto(grant);
    }

    @Transactional
    public PermissionGrantDTO revokeGrant(Long grantId, UserPrincipal actor) {
        PermissionGrant grant = permissionGrantRepository.findById(grantId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission grant not found: " + grantId));

        if (!actor.hasRole(RoleName.GDG_LEAD) && !grant.getGrantedById().equals(actor.getId()) && !actor.isAnyLead()) {
            throw new ForbiddenException("Not authorized to revoke this permission grant");
        }

        grant.setRevoked(true);
        grant.setStatus(GrantStatus.REVOKED);
        grant.setRevokedAt(Instant.now());
        grant.setRevokedById(actor.getId());
        grant = permissionGrantRepository.save(grant);
        return toDto(grant);
    }

    @Transactional(readOnly = true)
    public List<PermissionGrantDTO> listGrants(Long granteeMemberId) {
        List<PermissionGrant> grants = granteeMemberId != null
                ? permissionGrantRepository.findByGranteeMemberIdOrderByCreatedAtDesc(granteeMemberId)
                : permissionGrantRepository.findAllByOrderByCreatedAtDesc();
        return grants.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<PermissionGrantDTO> listMyGrants(UserPrincipal actor) {
        return permissionGrantRepository.findByGranteeMemberIdOrderByCreatedAtDesc(actor.getId())
                .stream()
                .map(this::toDto)
                .toList();
    }

    private PermissionGrantDTO toDto(PermissionGrant g) {
        Instant now = Instant.now();
        boolean currentlyActive = g.getStatus() == GrantStatus.ACTIVE
                && !g.isRevoked()
                && !g.getValidFrom().isAfter(now)
                && (g.getValidUntil() == null || g.getValidUntil().isAfter(now));
        return new PermissionGrantDTO(
                g.getId(),
                g.getGrantedById(),
                g.getGranteeMemberId(),
                g.getGranteeRole(),
                g.getPermissionCode(),
                g.getScopeTeamId(),
                g.getScopeEventId(),
                g.getCrossTeamTargetTeamId(),
                g.getApprovedByTeamLeadId(),
                g.getApprovedAt(),
                g.getApprovalComment(),
                g.getValidFrom(),
                g.getValidUntil(),
                g.getStatus(),
                g.isRevoked(),
                g.getRevokedAt(),
                g.getRevokedById(),
                g.getReason(),
                currentlyActive,
                g.getCreatedAt()
        );
    }
}
