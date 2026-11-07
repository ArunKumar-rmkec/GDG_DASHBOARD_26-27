package com.gdgoc.dashboard.collaboration;

import com.gdgoc.dashboard.collaboration.CollaborationDtos.*;
import com.gdgoc.dashboard.common.BadRequestException;
import com.gdgoc.dashboard.common.ForbiddenException;
import com.gdgoc.dashboard.common.ResourceNotFoundException;
import com.gdgoc.dashboard.member.RoleName;
import com.gdgoc.dashboard.security.ClearanceEvaluator;
import com.gdgoc.dashboard.security.UserPrincipal;
import com.gdgoc.dashboard.team.Team;
import com.gdgoc.dashboard.team.TeamRepository;
import com.gdgoc.dashboard.team.TeamStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class CollaborationService {

    private final CollaborationRepository collaborationRepository;
    private final TeamRepository teamRepository;
    private final ClearanceEvaluator clearanceEvaluator;

    public CollaborationService(
            CollaborationRepository collaborationRepository,
            TeamRepository teamRepository,
            ClearanceEvaluator clearanceEvaluator
    ) {
        this.collaborationRepository = collaborationRepository;
        this.teamRepository = teamRepository;
        this.clearanceEvaluator = clearanceEvaluator;
    }

    @Transactional
    public CollaborationDTO createCollaboration(CreateCollaborationRequestDTO req, UserPrincipal actor) {
        if (req.participatingTeamIds() == null || req.participatingTeamIds().isEmpty()) {
            throw new BadRequestException("participatingTeamIds must not be empty");
        }

        Set<Long> distinctTeamIds = new LinkedHashSet<>();
        for (Long tId : req.participatingTeamIds()) {
            if (tId != null) {
                distinctTeamIds.add(tId);
            }
        }
        Long resolvedLeadTeamId = req.leadTeamId();
        if (resolvedLeadTeamId != null) {
            distinctTeamIds.add(resolvedLeadTeamId);
        } else if (!distinctTeamIds.isEmpty()) {
            resolvedLeadTeamId = distinctTeamIds.iterator().next();
        }

        CollaborationType type = req.collaborationType() != null ? req.collaborationType() : CollaborationType.CROSS_TEAM;
        if (type == CollaborationType.CROSS_TEAM && distinctTeamIds.size() < 2) {
            throw new BadRequestException("CROSS_TEAM collaboration requires at least 2 distinct participating teams");
        }
        if (distinctTeamIds.isEmpty()) {
            throw new BadRequestException("At least 1 valid participating team is required");
        }

        Map<Long, Team> verifiedTeams = new LinkedHashMap<>();
        for (Long teamId : distinctTeamIds) {
            Team team = teamRepository.findById(teamId)
                    .orElseThrow(() -> new ResourceNotFoundException("Participating team not found: " + teamId));
            if (team.getStatus() == TeamStatus.DELETED) {
                throw new BadRequestException("Cannot create collaboration with deleted team: " + teamId);
            }
            verifiedTeams.put(teamId, team);
        }

        Collaboration collaboration = Collaboration.builder()
                .title(req.title().trim())
                .description(req.description())
                .collaborationType(type)
                .leadTeamId(resolvedLeadTeamId)
                .createdById(actor.getId())
                .status(CollaborationStatus.PENDING_APPROVAL)
                .deadline(req.deadline())
                .build();

        for (Long teamId : distinctTeamIds) {
            boolean autoApprove = teamId.equals(resolvedLeadTeamId);
            CollaborationTeamApproval approval = CollaborationTeamApproval.builder()
                    .collaboration(collaboration)
                    .teamId(teamId)
                    .approvalStatus(autoApprove ? ApprovalStatus.APPROVED : ApprovalStatus.PENDING)
                    .approvedById(autoApprove ? actor.getId() : null)
                    .comment(autoApprove ? "Lead team auto-approved on creation" : null)
                    .approvedAt(autoApprove ? Instant.now() : null)
                    .build();
            collaboration.getTeamApprovals().add(approval);
        }

        boolean allApproved = collaboration.getTeamApprovals().stream()
                .allMatch(a -> a.getApprovalStatus() == ApprovalStatus.APPROVED);
        if (allApproved) {
            collaboration.setStatus(CollaborationStatus.ACTIVE);
        }

        collaboration = collaborationRepository.save(collaboration);
        return toDto(collaboration);
    }

    @Transactional
    public CollaborationDTO approveTeamParticipation(Long collaborationId, ApproveCollaborationTeamRequestDTO req, UserPrincipal actor) {
        Collaboration collaboration = collaborationRepository.findById(collaborationId)
                .orElseThrow(() -> new ResourceNotFoundException("Collaboration not found: " + collaborationId));

        CollaborationTeamApproval targetApproval = collaboration.getTeamApprovals().stream()
                .filter(a -> a.getTeamId().equals(req.teamId()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Team " + req.teamId() + " is not a participating team in collaboration " + collaborationId));

        if (!actor.hasRole(RoleName.GDG_LEAD) && !clearanceEvaluator.isTeamLeadForTeam(actor, req.teamId())) {
            throw new ForbiddenException("Only the TEAM_LEAD of team " + req.teamId() + " or a GDG_LEAD can approve participation for that team");
        }

        targetApproval.setApprovedById(actor.getId());
        targetApproval.setComment(req.comment());
        targetApproval.setApprovedAt(Instant.now());

        if (Boolean.TRUE.equals(req.approved())) {
            targetApproval.setApprovalStatus(ApprovalStatus.APPROVED);
            boolean allApproved = collaboration.getTeamApprovals().stream()
                    .allMatch(a -> a.getApprovalStatus() == ApprovalStatus.APPROVED);
            if (allApproved) {
                collaboration.setStatus(CollaborationStatus.ACTIVE);
            }
        } else {
            targetApproval.setApprovalStatus(ApprovalStatus.REJECTED);
            collaboration.setStatus(CollaborationStatus.REJECTED);
        }

        collaboration = collaborationRepository.save(collaboration);
        return toDto(collaboration);
    }

    @Transactional(readOnly = true)
    public List<CollaborationDTO> listCollaborations(Long teamId, CollaborationType collaborationType, CollaborationStatus status) {
        return collaborationRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(c -> collaborationType == null || c.getCollaborationType() == collaborationType)
                .filter(c -> status == null || c.getStatus() == status)
                .filter(c -> teamId == null || c.getTeamApprovals().stream().anyMatch(a -> a.getTeamId().equals(teamId)))
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public CollaborationDTO getCollaboration(Long id) {
        Collaboration collaboration = collaborationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Collaboration not found: " + id));
        return toDto(collaboration);
    }

    private CollaborationDTO toDto(Collaboration c) {
        List<Long> participatingIds = c.getTeamApprovals().stream()
                .map(CollaborationTeamApproval::getTeamId)
                .toList();
        List<CollaborationTeamApprovalDTO> approvalDtos = c.getTeamApprovals().stream()
                .map(a -> {
                    String teamName = teamRepository.findById(a.getTeamId())
                            .map(Team::getName)
                            .orElse("Team " + a.getTeamId());
                    return new CollaborationTeamApprovalDTO(
                            a.getTeamId(),
                            teamName,
                            a.getApprovalStatus(),
                            a.getApprovedById(),
                            a.getComment(),
                            a.getApprovedAt()
                    );
                })
                .toList();

        return new CollaborationDTO(
                c.getId(),
                c.getTitle(),
                c.getDescription(),
                c.getCollaborationType(),
                c.getLeadTeamId(),
                c.getCreatedById(),
                c.getStatus(),
                c.getDeadline(),
                participatingIds,
                approvalDtos,
                c.getCreatedAt()
        );
    }
}
