package com.gdgoc.dashboard.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdgoc.dashboard.common.BadRequestException;
import com.gdgoc.dashboard.common.ConflictException;
import com.gdgoc.dashboard.common.ForbiddenException;
import com.gdgoc.dashboard.common.ResourceNotFoundException;
import com.gdgoc.dashboard.member.Member;
import com.gdgoc.dashboard.member.MemberDtos.BioDataUpdateRequestDTO;
import com.gdgoc.dashboard.member.MemberDtos.HrAddMemberRequestDTO;
import com.gdgoc.dashboard.member.MemberRepository;
import com.gdgoc.dashboard.member.MemberService;
import com.gdgoc.dashboard.member.RoleName;
import com.gdgoc.dashboard.request.RequestDtos.*;
import com.gdgoc.dashboard.security.ClearanceEvaluator;
import com.gdgoc.dashboard.security.UserPrincipal;
import com.gdgoc.dashboard.team.Team;
import com.gdgoc.dashboard.team.TeamMembership;
import com.gdgoc.dashboard.team.TeamMembershipRepository;
import com.gdgoc.dashboard.team.TeamRepository;
import com.gdgoc.dashboard.team.TeamRole;
import com.gdgoc.dashboard.team.TeamStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class ChangeRequestService {

    private final MemberChangeRequestRepository changeRequestRepository;
    private final MemberRepository memberRepository;
    private final MemberService memberService;
    private final TeamRepository teamRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final ClearanceEvaluator clearanceEvaluator;
    private final ObjectMapper objectMapper;

    public ChangeRequestService(
            MemberChangeRequestRepository changeRequestRepository,
            MemberRepository memberRepository,
            MemberService memberService,
            TeamRepository teamRepository,
            TeamMembershipRepository teamMembershipRepository,
            ClearanceEvaluator clearanceEvaluator,
            ObjectMapper objectMapper
    ) {
        this.changeRequestRepository = changeRequestRepository;
        this.memberRepository = memberRepository;
        this.memberService = memberService;
        this.teamRepository = teamRepository;
        this.teamMembershipRepository = teamMembershipRepository;
        this.clearanceEvaluator = clearanceEvaluator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MemberChangeRequestDTO createSelfServiceBioDataRequest(BioDataUpdateRequestDTO req, UserPrincipal actor) {
        memberService.validateBioDataRequest(req);

        Long currentTeamId = teamMembershipRepository.findByMemberIdAndActiveTrue(actor.getId())
                .stream()
                .map(TeamMembership::getTeamId)
                .findFirst()
                .orElse(null);

        MemberChangeRequest request = MemberChangeRequest.builder()
                .targetMemberId(actor.getId())
                .initiatedById(actor.getId())
                .requestType(ChangeRequestType.BIO_DATA_UPDATE)
                .payloadJson(memberService.serializeJson(req))
                .currentTeamId(currentTeamId)
                .reason(req.reason() != null ? req.reason() : "Member self-service bio-data update request")
                .status(ChangeRequestStatus.PENDING_AUTHORIZATION)
                .hrLeadApproved(false)
                .teamLeadApproved(false)
                .build();
        request = changeRequestRepository.save(request);
        return memberService.toChangeRequestDto(request);
    }

    @Transactional
    public MemberChangeRequestDTO createSelfServiceTeamChangeRequest(TeamChangeRequestDTO req, UserPrincipal actor) {
        if (req.reason() == null || req.reason().trim().isEmpty()) {
            throw new BadRequestException("A non-blank reason is mandatory when requesting a team change");
        }
        if (req.targetTeamId() == null) {
            throw new BadRequestException("targetTeamId is required");
        }

        Team targetTeam = teamRepository.findById(req.targetTeamId())
                .orElseThrow(() -> new ResourceNotFoundException("Target team not found: " + req.targetTeamId()));
        if (targetTeam.getStatus() == TeamStatus.DELETED) {
            throw new BadRequestException("Cannot request transfer to a deleted team");
        }

        Long resolvedCurrentTeamId = req.currentTeamId();
        if (resolvedCurrentTeamId == null) {
            resolvedCurrentTeamId = teamMembershipRepository.findByMemberIdAndActiveTrue(actor.getId())
                    .stream()
                    .map(TeamMembership::getTeamId)
                    .findFirst()
                    .orElse(null);
        }

        if (resolvedCurrentTeamId != null && resolvedCurrentTeamId.equals(req.targetTeamId())) {
            throw new BadRequestException("targetTeamId must be different from currentTeamId");
        }

        MemberChangeRequest request = MemberChangeRequest.builder()
                .targetMemberId(actor.getId())
                .initiatedById(actor.getId())
                .requestType(ChangeRequestType.TEAM_TRANSFER)
                .currentTeamId(resolvedCurrentTeamId)
                .targetTeamId(req.targetTeamId())
                .reason(req.reason().trim())
                .status(ChangeRequestStatus.PENDING_AUTHORIZATION)
                .hrLeadApproved(false)
                .teamLeadApproved(false)
                .build();
        request = changeRequestRepository.save(request);
        return memberService.toChangeRequestDto(request);
    }

    @Transactional
    public MemberChangeRequestDTO approveOrRejectChangeRequest(Long id, ApproveChangeRequestDTO req, UserPrincipal actor) {
        MemberChangeRequest request = changeRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Change request not found: " + id));

        if (request.getStatus() == ChangeRequestStatus.EXECUTED || request.getStatus() == ChangeRequestStatus.REJECTED) {
            throw new ConflictException("Change request has already been " + request.getStatus());
        }

        boolean isGdgLead = actor.hasRole(RoleName.GDG_LEAD);
        boolean isHrLead = actor.hasRole(RoleName.HR_LEAD);
        boolean isTeamLead = actor.hasRole(RoleName.TEAM_LEAD);

        if (!isGdgLead && !isHrLead && !isTeamLead) {
            throw new ForbiddenException("Only GDG_LEAD, HR_LEAD, or TEAM_LEAD can approve or reject change requests");
        }

        if (!isGdgLead && !isHrLead && isTeamLead) {
            if (!isTeamLeadInScopeForRequest(actor, request)) {
                throw new ForbiddenException("TEAM_LEAD is not authorized for this member's team scope");
            }
        }

        request.setReviewComment(req.comment());

        if (Boolean.FALSE.equals(req.approved())) {
            request.setStatus(ChangeRequestStatus.REJECTED);
            request = changeRequestRepository.save(request);
            return memberService.toChangeRequestDto(request);
        }

        Instant now = Instant.now();
        if (isGdgLead) {
            request.setGdgLeadOverrideById(actor.getId());
            request.setHrLeadApproved(true);
            request.setTeamLeadApproved(true);
        } else {
            if (isHrLead) {
                request.setHrLeadApproved(true);
                request.setHrLeadApprovedById(actor.getId());
                request.setHrLeadApprovedAt(now);
            }
            if (isTeamLead && isTeamLeadInScopeForRequest(actor, request)) {
                request.setTeamLeadApproved(true);
                request.setTeamLeadApprovedById(actor.getId());
                request.setTeamLeadApprovedAt(now);
            }
        }

        if (request.isHrLeadApproved() && request.isTeamLeadApproved()) {
            executeApprovedChangeRequest(request, actor.getId());
            request.setStatus(ChangeRequestStatus.EXECUTED);
            request.setExecutedAt(now);
        } else {
            request.setStatus(ChangeRequestStatus.PARTIALLY_APPROVED);
        }

        request = changeRequestRepository.save(request);
        return memberService.toChangeRequestDto(request);
    }

    private boolean isTeamLeadInScopeForRequest(UserPrincipal actor, MemberChangeRequest request) {
        if (request.getTargetTeamId() == null && request.getCurrentTeamId() == null) {
            if (request.getTargetMemberId() != null) {
                List<TeamMembership> targetActive = teamMembershipRepository.findByMemberIdAndActiveTrue(request.getTargetMemberId());
                if (!targetActive.isEmpty()) {
                    return targetActive.stream().anyMatch(m -> clearanceEvaluator.isTeamLeadForTeam(actor, m.getTeamId()));
                }
            }
            return true;
        }
        if (request.getTargetTeamId() != null && clearanceEvaluator.isTeamLeadForTeam(actor, request.getTargetTeamId())) {
            return true;
        }
        if (request.getCurrentTeamId() != null && clearanceEvaluator.isTeamLeadForTeam(actor, request.getCurrentTeamId())) {
            return true;
        }
        return false;
    }

    private void executeApprovedChangeRequest(MemberChangeRequest request, Long actorId) {
        switch (request.getRequestType()) {
            case ADD_MEMBER -> {
                HrAddMemberRequestDTO addDto = deserialize(request.getPayloadJson(), HrAddMemberRequestDTO.class);
                Member created = memberService.executeAddMember(addDto);
                request.setExecutedMemberId(created.getId());
            }
            case BIO_DATA_UPDATE -> {
                Member target = memberRepository.findByIdWithRoles(request.getTargetMemberId())
                        .orElseThrow(() -> new ResourceNotFoundException("Target member not found: " + request.getTargetMemberId()));
                BioDataUpdateRequestDTO bioDto = deserialize(request.getPayloadJson(), BioDataUpdateRequestDTO.class);
                Member updated = memberService.executeBioDataUpdate(target, bioDto);
                request.setExecutedMemberId(updated.getId());
            }
            case REMOVE_MEMBER -> {
                Member target = memberRepository.findByIdWithRoles(request.getTargetMemberId())
                        .orElseThrow(() -> new ResourceNotFoundException("Target member not found: " + request.getTargetMemberId()));
                Member removed = memberService.executeRemoveMember(target, actorId);
                request.setExecutedMemberId(removed.getId());
            }
            case TEAM_TRANSFER -> {
                Member target = memberRepository.findByIdWithRoles(request.getTargetMemberId())
                        .orElseThrow(() -> new ResourceNotFoundException("Target member not found: " + request.getTargetMemberId()));
                Instant now = Instant.now();

                if (request.getCurrentTeamId() != null) {
                    Optional<TeamMembership> currentMembership = teamMembershipRepository
                            .findFirstByTeamIdAndMemberIdAndActiveTrue(request.getCurrentTeamId(), target.getId());
                    if (currentMembership.isPresent()) {
                        TeamMembership cm = currentMembership.get();
                        cm.setActive(false);
                        cm.setLeftAt(now);
                        cm.setExitReason("TEAM_TRANSFER");
                        teamMembershipRepository.save(cm);
                    }
                } else {
                    List<TeamMembership> active = teamMembershipRepository.findByMemberIdAndActiveTrue(target.getId());
                    for (TeamMembership cm : active) {
                        cm.setActive(false);
                        cm.setLeftAt(now);
                        cm.setExitReason("TEAM_TRANSFER");
                    }
                    teamMembershipRepository.saveAll(active);
                }

                if (request.getTargetTeamId() != null) {
                    Optional<TeamMembership> existingTarget = teamMembershipRepository
                            .findFirstByTeamIdAndMemberIdAndActiveTrue(request.getTargetTeamId(), target.getId());
                    if (existingTarget.isEmpty()) {
                        TeamMembership newMembership = TeamMembership.builder()
                                .teamId(request.getTargetTeamId())
                                .memberId(target.getId())
                                .teamRole(TeamRole.MEMBER)
                                .active(true)
                                .joinedAt(now)
                                .build();
                        teamMembershipRepository.save(newMembership);
                    }
                }
                request.setExecutedMemberId(target.getId());
            }
        }
    }

    @Transactional(readOnly = true)
    public List<MemberChangeRequestDTO> listChangeRequests(
            ChangeRequestStatus status,
            ChangeRequestType requestType,
            Long targetMemberId,
            UserPrincipal actor
    ) {
        boolean isHrOrLead = actor.isAnyLead() || actor.hasRole(RoleName.HR_MEMBER);
        List<MemberChangeRequest> baseList = isHrOrLead
                ? changeRequestRepository.findAllByOrderByCreatedAtDesc()
                : changeRequestRepository.findByInitiatedByIdOrTargetMemberIdOrderByCreatedAtDesc(actor.getId(), actor.getId());

        return baseList.stream()
                .filter(r -> status == null || r.getStatus() == status)
                .filter(r -> requestType == null || r.getRequestType() == requestType)
                .filter(r -> targetMemberId == null || targetMemberId.equals(r.getTargetMemberId()))
                .map(memberService::toChangeRequestDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public MemberChangeRequestDTO getChangeRequest(Long id, UserPrincipal actor) {
        MemberChangeRequest r = changeRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Change request not found: " + id));

        boolean isHrOrLead = actor.isAnyLead() || actor.hasRole(RoleName.HR_MEMBER);
        boolean isOwn = actor.getId().equals(r.getInitiatedById()) || actor.getId().equals(r.getTargetMemberId());
        if (!isHrOrLead && !isOwn) {
            throw new ForbiddenException("Not authorized to view this change request");
        }
        return memberService.toChangeRequestDto(r);
    }

    private <T> T deserialize(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize change request payload", e);
        }
    }
}
