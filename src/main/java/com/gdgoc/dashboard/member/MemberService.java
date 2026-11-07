package com.gdgoc.dashboard.member;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdgoc.dashboard.agreement.AgreementSignatureRepository;
import com.gdgoc.dashboard.agreement.SignatureStatus;
import com.gdgoc.dashboard.common.BadRequestException;
import com.gdgoc.dashboard.common.ConflictException;
import com.gdgoc.dashboard.common.ResourceNotFoundException;
import com.gdgoc.dashboard.letter.LetterService;
import com.gdgoc.dashboard.member.MemberDtos.*;
import com.gdgoc.dashboard.permission.GrantStatus;
import com.gdgoc.dashboard.permission.PermissionGrant;
import com.gdgoc.dashboard.permission.PermissionGrantRepository;
import com.gdgoc.dashboard.request.ChangeRequestStatus;
import com.gdgoc.dashboard.request.ChangeRequestType;
import com.gdgoc.dashboard.request.MemberChangeRequest;
import com.gdgoc.dashboard.request.MemberChangeRequestRepository;
import com.gdgoc.dashboard.request.RequestDtos.MemberChangeRequestDTO;
import com.gdgoc.dashboard.security.ClearanceEvaluator;
import com.gdgoc.dashboard.security.UserPrincipal;
import com.gdgoc.dashboard.team.Team;
import com.gdgoc.dashboard.team.TeamDtos.TeamMembershipHistoryDTO;
import com.gdgoc.dashboard.team.TeamMembership;
import com.gdgoc.dashboard.team.TeamMembershipRepository;
import com.gdgoc.dashboard.team.TeamRepository;
import com.gdgoc.dashboard.team.TeamRole;
import com.gdgoc.dashboard.team.TeamService;
import com.gdgoc.dashboard.team.TeamStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class MemberService {

    private final MemberRepository memberRepository;
    private final TeamRepository teamRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final TeamService teamService;
    private final PermissionGrantRepository permissionGrantRepository;
    private final MemberChangeRequestRepository changeRequestRepository;
    private final AgreementSignatureRepository agreementSignatureRepository;
    private final LetterService letterService;
    private final ClearanceEvaluator clearanceEvaluator;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public MemberService(
            MemberRepository memberRepository,
            TeamRepository teamRepository,
            TeamMembershipRepository teamMembershipRepository,
            TeamService teamService,
            PermissionGrantRepository permissionGrantRepository,
            MemberChangeRequestRepository changeRequestRepository,
            AgreementSignatureRepository agreementSignatureRepository,
            LetterService letterService,
            ClearanceEvaluator clearanceEvaluator,
            PasswordEncoder passwordEncoder,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.memberRepository = memberRepository;
        this.teamRepository = teamRepository;
        this.teamMembershipRepository = teamMembershipRepository;
        this.teamService = teamService;
        this.permissionGrantRepository = permissionGrantRepository;
        this.changeRequestRepository = changeRequestRepository;
        this.agreementSignatureRepository = agreementSignatureRepository;
        this.letterService = letterService;
        this.clearanceEvaluator = clearanceEvaluator;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<MemberDetailDTO> listMembers(MemberStatus status, Long teamId, RoleName role, String search) {
        List<Member> members = memberRepository.findAll();
        return members.stream()
                .filter(m -> status == null || m.getStatus() == status)
                .filter(m -> role == null || (m.getRoles() != null && m.getRoles().contains(role)))
                .filter(m -> {
                    if (teamId == null) return true;
                    return teamMembershipRepository.existsByTeamIdAndMemberIdAndActiveTrue(teamId, m.getId());
                })
                .filter(m -> {
                    if (search == null || search.isBlank()) return true;
                    String q = search.trim().toLowerCase(Locale.ROOT);
                    return (m.getFullName() != null && m.getFullName().toLowerCase(Locale.ROOT).contains(q))
                            || (m.getEmail() != null && m.getEmail().toLowerCase(Locale.ROOT).contains(q))
                            || (m.getRollNumber() != null && m.getRollNumber().toLowerCase(Locale.ROOT).contains(q));
                })
                .map(this::toDetailDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public MemberDetailDTO getMemberDetail(Long id) {
        Member member = memberRepository.findByIdWithRoles(id)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found: " + id));
        return toDetailDto(member);
    }

    @Transactional
    public MemberMutationResponseDTO addMemberOrStage(HrAddMemberRequestDTO req, Authentication authentication, UserPrincipal actor) {
        String normalizedEmail = req.email().trim().toLowerCase(Locale.ROOT);
        if (memberRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ConflictException("Member with email " + normalizedEmail + " already exists");
        }
        if (req.academicYear() != null && (req.academicYear() < 1 || req.academicYear() > 6)) {
            throw new BadRequestException("academicYear must be between 1 and 6");
        }
        if (req.teamId() != null) {
            Team team = teamRepository.findById(req.teamId())
                    .orElseThrow(() -> new ResourceNotFoundException("Team not found: " + req.teamId()));
            if (team.getStatus() == TeamStatus.DELETED) {
                throw new BadRequestException("Cannot assign member to a deleted team");
            }
        }

        if (clearanceEvaluator.canManageBioDataDirect(authentication)) {
            Member created = executeAddMember(req);
            return new MemberMutationResponseDTO("EXECUTED", null, toDetailDto(created), null);
        }

        MemberChangeRequest staged = MemberChangeRequest.builder()
                .targetMemberId(null)
                .initiatedById(actor.getId())
                .requestType(ChangeRequestType.ADD_MEMBER)
                .payloadJson(serializeJson(req))
                .targetTeamId(req.teamId())
                .reason(req.reason() != null ? req.reason() : "Staged HR member addition")
                .status(ChangeRequestStatus.PENDING_AUTHORIZATION)
                .hrLeadApproved(false)
                .teamLeadApproved(false)
                .build();
        staged = changeRequestRepository.save(staged);
        return new MemberMutationResponseDTO("PENDING_AUTHORIZATION", staged.getId(), null, toChangeRequestDto(staged));
    }

    @Transactional
    public Member executeAddMember(HrAddMemberRequestDTO req) {
        String normalizedEmail = req.email().trim().toLowerCase(Locale.ROOT);
        if (memberRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ConflictException("Member with email " + normalizedEmail + " already exists");
        }

        Set<RoleName> assignedRoles = (req.roles() == null || req.roles().isEmpty())
                ? new HashSet<>(Set.of(RoleName.TEAM_MEMBER))
                : new HashSet<>(req.roles());

        String rawPassword = (req.password() != null && !req.password().isBlank())
                ? req.password()
                : "WelcomeGDGoC2026!";

        Member member = Member.builder()
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .fullName(req.fullName().trim())
                .rollNumber(req.rollNumber())
                .department(req.department())
                .academicYear(req.academicYear())
                .phone(req.phone())
                .bio(req.bio())
                .skills(req.skills())
                .githubUrl(req.githubUrl())
                .linkedinUrl(req.linkedinUrl())
                .status(MemberStatus.ACTIVE)
                .roles(assignedRoles)
                .build();
        member = memberRepository.save(member);

        if (req.teamId() != null) {
            TeamRole tRole = req.teamRole() != null ? req.teamRole() : TeamRole.MEMBER;
            TeamMembership membership = TeamMembership.builder()
                    .teamId(req.teamId())
                    .memberId(member.getId())
                    .teamRole(tRole)
                    .active(true)
                    .joinedAt(Instant.now())
                    .build();
            teamMembershipRepository.save(membership);
        }

        return member;
    }

    @Transactional
    public MemberMutationResponseDTO updateBioDataOrStage(Long id, BioDataUpdateRequestDTO req, Authentication authentication, UserPrincipal actor) {
        Member target = memberRepository.findByIdWithRoles(id)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found: " + id));

        validateBioDataRequest(req);

        if (clearanceEvaluator.canManageBioDataDirect(authentication)) {
            Member updated = executeBioDataUpdate(target, req);
            return new MemberMutationResponseDTO("EXECUTED", null, toDetailDto(updated), null);
        }

        Long primaryTeamId = teamMembershipRepository.findByMemberIdAndActiveTrue(target.getId())
                .stream()
                .map(TeamMembership::getTeamId)
                .findFirst()
                .orElse(null);

        MemberChangeRequest staged = MemberChangeRequest.builder()
                .targetMemberId(target.getId())
                .initiatedById(actor.getId())
                .requestType(ChangeRequestType.BIO_DATA_UPDATE)
                .payloadJson(serializeJson(req))
                .currentTeamId(primaryTeamId)
                .reason(req.reason() != null ? req.reason() : "Staged HR bio-data update")
                .status(ChangeRequestStatus.PENDING_AUTHORIZATION)
                .hrLeadApproved(false)
                .teamLeadApproved(false)
                .build();
        staged = changeRequestRepository.save(staged);
        return new MemberMutationResponseDTO("PENDING_AUTHORIZATION", staged.getId(), null, toChangeRequestDto(staged));
    }

    public void validateBioDataRequest(BioDataUpdateRequestDTO req) {
        if (req.fullName() != null && req.fullName().isBlank()) {
            throw new BadRequestException("fullName cannot be blank");
        }
        if (req.academicYear() != null && (req.academicYear() < 1 || req.academicYear() > 6)) {
            throw new BadRequestException("academicYear must be between 1 and 6");
        }
    }

    @Transactional
    public Member executeBioDataUpdate(Member target, BioDataUpdateRequestDTO req) {
        if (req.fullName() != null && !req.fullName().isBlank()) {
            target.setFullName(req.fullName().trim());
        }
        if (req.rollNumber() != null) {
            target.setRollNumber(req.rollNumber());
        }
        if (req.department() != null) {
            target.setDepartment(req.department());
        }
        if (req.academicYear() != null) {
            target.setAcademicYear(req.academicYear());
        }
        if (req.phone() != null) {
            target.setPhone(req.phone());
        }
        if (req.bio() != null) {
            target.setBio(req.bio());
        }
        if (req.skills() != null) {
            target.setSkills(req.skills());
        }
        if (req.githubUrl() != null) {
            target.setGithubUrl(req.githubUrl());
        }
        if (req.linkedinUrl() != null) {
            target.setLinkedinUrl(req.linkedinUrl());
        }
        target.setUpdatedAt(Instant.now());
        return memberRepository.save(target);
    }

    @Transactional
    public MemberMutationResponseDTO removeMemberOrStage(Long id, String reason, Authentication authentication, UserPrincipal actor) {
        Member target = memberRepository.findByIdWithRoles(id)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found: " + id));

        if (clearanceEvaluator.canManageBioDataDirect(authentication)) {
            Member removed = executeRemoveMember(target, actor.getId());
            return new MemberMutationResponseDTO("EXECUTED", null, toDetailDto(removed), null);
        }

        Long primaryTeamId = teamMembershipRepository.findByMemberIdAndActiveTrue(target.getId())
                .stream()
                .map(TeamMembership::getTeamId)
                .findFirst()
                .orElse(null);

        MemberChangeRequest staged = MemberChangeRequest.builder()
                .targetMemberId(target.getId())
                .initiatedById(actor.getId())
                .requestType(ChangeRequestType.REMOVE_MEMBER)
                .currentTeamId(primaryTeamId)
                .reason(reason != null ? reason : "Staged HR member removal")
                .status(ChangeRequestStatus.PENDING_AUTHORIZATION)
                .hrLeadApproved(false)
                .teamLeadApproved(false)
                .build();
        staged = changeRequestRepository.save(staged);
        return new MemberMutationResponseDTO("PENDING_AUTHORIZATION", staged.getId(), null, toChangeRequestDto(staged));
    }

    @Transactional
    public Member executeRemoveMember(Member target, Long actorId) {
        Instant now = Instant.now();
        target.setStatus(MemberStatus.INACTIVE);
        target.setUpdatedAt(now);
        target = memberRepository.save(target);

        List<TeamMembership> activeMemberships = teamMembershipRepository.findByMemberIdAndActiveTrue(target.getId());
        for (TeamMembership m : activeMemberships) {
            m.setActive(false);
            m.setLeftAt(now);
            m.setExitReason("MEMBER_REMOVED");
        }
        teamMembershipRepository.saveAll(activeMemberships);

        revokeAllGrantsForMember(target.getId(), actorId, now);
        return target;
    }

    @Transactional
    public MemberDetailDTO terminateMember(Long id, TerminateMemberRequestDTO req, UserPrincipal actor) {
        Member target = memberRepository.findByIdWithRoles(id)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found: " + id));

        Instant termTime = (req != null && req.terminatedAt() != null) ? req.terminatedAt() : Instant.now();
        String reason = (req != null && req.reason() != null && !req.reason().isBlank())
                ? req.reason().trim()
                : "Administrative Termination";

        target.setStatus(MemberStatus.TERMINATED);
        target.setTerminatedAt(termTime);
        target.setTerminationReason(reason);
        target.setLetterWindowExpiresAt(termTime.plus(10, ChronoUnit.DAYS));
        target.getRoles().clear();
        target.setUpdatedAt(Instant.now());
        target = memberRepository.save(target);

        List<TeamMembership> activeMemberships = teamMembershipRepository.findByMemberIdAndActiveTrue(target.getId());
        for (TeamMembership m : activeMemberships) {
            m.setActive(false);
            m.setLeftAt(termTime);
            m.setExitReason("TERMINATED");
        }
        teamMembershipRepository.saveAll(activeMemberships);

        revokeAllGrantsForMember(target.getId(), actor.getId(), Instant.now());

        letterService.issueTerminationLetter(target, actor.getId(), reason, termTime);
        return toDetailDto(target);
    }

    private void revokeAllGrantsForMember(Long memberId, Long revokedById, Instant now) {
        List<PermissionGrant> grants = permissionGrantRepository.findByGranteeMemberIdAndRevokedFalse(memberId);
        for (PermissionGrant g : grants) {
            g.setRevoked(true);
            g.setStatus(GrantStatus.REVOKED);
            g.setRevokedAt(now);
            g.setRevokedById(revokedById);
        }
        permissionGrantRepository.saveAll(grants);
    }

    public MemberDetailDTO toDetailDto(Member m) {
        List<TeamMembershipHistoryDTO> activeTeams = teamMembershipRepository
                .findByMemberIdAndActiveTrue(m.getId())
                .stream()
                .map(teamService::toMembershipHistoryDto)
                .toList();

        List<TeamMembershipHistoryDTO> pastTeams = teamMembershipRepository
                .findByMemberIdAndActiveFalseOrderByLeftAtDesc(m.getId())
                .stream()
                .map(teamService::toMembershipHistoryDto)
                .toList();

        long signedCount = agreementSignatureRepository.countByMemberIdAndStatus(m.getId(), SignatureStatus.SIGNED);
        long totalIssued = agreementSignatureRepository.countByMemberId(m.getId());
        String pcaStatus = signedCount > 0 ? "SIGNED" : (totalIssued > 0 ? "PENDING_SIGNATURE" : "NOT_ISSUED");

        long totalAssigned = queryCount("SELECT COUNT(*) FROM tasks WHERE assignee_id = ? AND status NOT IN ('REJECTED', 'CANCELLED')", m.getId());
        long completed = queryCount("SELECT COUNT(*) FROM tasks WHERE assignee_id = ? AND status = 'COMPLETED'", m.getId());
        long inProgress = queryCount("SELECT COUNT(*) FROM tasks WHERE assignee_id = ? AND status IN ('ASSIGNED', 'IN_PROGRESS', 'BLOCKED', 'IN_REVIEW')", m.getId());
        long overdue = queryCount("SELECT COUNT(*) FROM tasks WHERE assignee_id = ? AND status NOT IN ('COMPLETED', 'REJECTED', 'CANCELLED') AND deadline < ?", m.getId(), Timestamp.from(Instant.now()));

        Set<RoleName> effectiveRoles = m.getStatus() == MemberStatus.TERMINATED
                ? Collections.emptySet()
                : m.getRoles();

        boolean isPcaSigned = "SIGNED".equalsIgnoreCase(pcaStatus);

        return new MemberDetailDTO(
                m.getId(),
                m.getEmail(),
                m.getFullName(),
                m.getRollNumber(),
                m.getDepartment(),
                m.getAcademicYear(),
                m.getPhone(),
                m.getBio(),
                m.getSkills(),
                m.getGithubUrl(),
                m.getLinkedinUrl(),
                m.getStatus(),
                effectiveRoles,
                activeTeams,
                pastTeams,
                pcaStatus,
                pcaStatus,
                isPcaSigned,
                isPcaSigned,
                signedCount,
                new MemberTaskSummaryDTO(totalAssigned, completed, inProgress, overdue),
                m.getTerminatedAt(),
                m.getTerminationReason(),
                m.getLetterWindowExpiresAt(),
                m.getCreatedAt(),
                m.getUpdatedAt()
        );
    }

    public MemberChangeRequestDTO toChangeRequestDto(MemberChangeRequest r) {
        return new MemberChangeRequestDTO(
                r.getId(),
                r.getTargetMemberId(),
                r.getInitiatedById(),
                r.getRequestType(),
                r.getPayloadJson(),
                r.getCurrentTeamId(),
                r.getTargetTeamId(),
                r.getReason(),
                r.getStatus(),
                r.isHrLeadApproved(),
                r.getHrLeadApprovedById(),
                r.getHrLeadApprovedAt(),
                r.isTeamLeadApproved(),
                r.getTeamLeadApprovedById(),
                r.getTeamLeadApprovedAt(),
                r.getGdgLeadOverrideById(),
                r.getReviewComment(),
                r.getExecutedMemberId(),
                r.getCreatedAt(),
                r.getExecutedAt()
        );
    }

    public String serializeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize request payload", e);
        }
    }

    private long queryCount(String sql, Object... args) {
        Long val = jdbcTemplate.queryForObject(sql, Long.class, args);
        return val != null ? val : 0L;
    }
}
