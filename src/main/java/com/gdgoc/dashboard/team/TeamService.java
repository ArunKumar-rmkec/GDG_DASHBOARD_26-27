package com.gdgoc.dashboard.team;

import com.gdgoc.dashboard.collaboration.CollaborationTeamApprovalRepository;
import com.gdgoc.dashboard.common.BadRequestException;
import com.gdgoc.dashboard.common.ConflictException;
import com.gdgoc.dashboard.common.ResourceNotFoundException;
import com.gdgoc.dashboard.member.Member;
import com.gdgoc.dashboard.member.MemberRepository;
import com.gdgoc.dashboard.member.MemberStatus;
import com.gdgoc.dashboard.member.RoleName;
import com.gdgoc.dashboard.security.UserPrincipal;
import com.gdgoc.dashboard.team.TeamDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final TeamConsensusRequestRepository consensusRequestRepository;
    private final TeamConsensusVoteRepository consensusVoteRepository;
    private final MemberRepository memberRepository;
    private final CollaborationTeamApprovalRepository collaborationTeamApprovalRepository;
    private final JdbcTemplate jdbcTemplate;

    public TeamService(
            TeamRepository teamRepository,
            TeamMembershipRepository teamMembershipRepository,
            TeamConsensusRequestRepository consensusRequestRepository,
            TeamConsensusVoteRepository consensusVoteRepository,
            MemberRepository memberRepository,
            CollaborationTeamApprovalRepository collaborationTeamApprovalRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.teamRepository = teamRepository;
        this.teamMembershipRepository = teamMembershipRepository;
        this.consensusRequestRepository = consensusRequestRepository;
        this.consensusVoteRepository = consensusVoteRepository;
        this.memberRepository = memberRepository;
        this.collaborationTeamApprovalRepository = collaborationTeamApprovalRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public TeamConsensusRequestDTO initiateConsensusRequest(CreateConsensusRequestDTO req, UserPrincipal actor) {
        if (req.actionType() == null) {
            throw new BadRequestException("actionType is required");
        }

        switch (req.actionType()) {
            case CREATE_TEAM -> {
                if (req.proposedName() == null || req.proposedName().isBlank()) {
                    throw new BadRequestException("proposedName is required for CREATE_TEAM");
                }
                if (teamRepository.existsByNameIgnoreCaseAndStatus(req.proposedName().trim(), TeamStatus.ACTIVE)) {
                    throw new ConflictException("An active team with name '" + req.proposedName().trim() + "' already exists");
                }
                if (req.parentTeamId() != null) {
                    Team parent = teamRepository.findById(req.parentTeamId())
                            .orElseThrow(() -> new ResourceNotFoundException("Parent team not found: " + req.parentTeamId()));
                    if (parent.getStatus() == TeamStatus.DELETED) {
                        throw new BadRequestException("Parent team is deleted");
                    }
                }
            }
            case DELETE_TEAM -> {
                if (req.targetTeamId() == null) {
                    throw new BadRequestException("targetTeamId is required for DELETE_TEAM");
                }
                Team target = teamRepository.findById(req.targetTeamId())
                        .orElseThrow(() -> new ResourceNotFoundException("Target team not found: " + req.targetTeamId()));
                if (target.getStatus() == TeamStatus.DELETED) {
                    throw new ConflictException("Target team is already deleted");
                }
            }
            case RENAME_TEAM -> {
                if (req.targetTeamId() == null) {
                    throw new BadRequestException("targetTeamId is required for RENAME_TEAM");
                }
                if (req.proposedName() == null || req.proposedName().isBlank()) {
                    throw new BadRequestException("proposedName is required for RENAME_TEAM");
                }
                Team target = teamRepository.findById(req.targetTeamId())
                        .orElseThrow(() -> new ResourceNotFoundException("Target team not found: " + req.targetTeamId()));
                if (target.getStatus() == TeamStatus.DELETED) {
                    throw new BadRequestException("Cannot rename a deleted team");
                }
                Optional<Team> existingSameName = teamRepository.findByNameIgnoreCaseAndStatus(req.proposedName().trim(), TeamStatus.ACTIVE);
                if (existingSameName.isPresent() && !existingSameName.get().getId().equals(target.getId())) {
                    throw new ConflictException("Another active team with name '" + req.proposedName().trim() + "' already exists");
                }
            }
        }

        TeamConsensusRequest request = TeamConsensusRequest.builder()
                .actionType(req.actionType())
                .targetTeamId(req.targetTeamId())
                .proposedName(req.proposedName() != null ? req.proposedName().trim() : null)
                .proposedDescription(req.proposedDescription())
                .parentTeamId(req.parentTeamId())
                .initiatedById(actor.getId())
                .status(ConsensusStatus.PENDING)
                .approvalCount(1)
                .requiredApprovals(3)
                .build();

        TeamConsensusVote initialVote = TeamConsensusVote.builder()
                .request(request)
                .gdgLeadId(actor.getId())
                .approved(true)
                .comment("Initiated consensus request")
                .votedAt(Instant.now())
                .build();
        request.getVotes().add(initialVote);

        request = consensusRequestRepository.save(request);
        return toConsensusDto(request);
    }

    @Transactional
    public TeamConsensusRequestDTO voteOnConsensusRequest(Long requestId, ConsensusVoteRequestDTO voteReq, UserPrincipal actor) {
        TeamConsensusRequest request = consensusRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Consensus request not found: " + requestId));

        if (request.getStatus() != ConsensusStatus.PENDING) {
            throw new ConflictException("Consensus request is already " + request.getStatus());
        }

        if (consensusVoteRepository.existsByRequest_IdAndGdgLeadId(requestId, actor.getId())) {
            throw new ConflictException("Duplicate vote: GDG Lead has already voted on this consensus request");
        }

        TeamConsensusVote vote = TeamConsensusVote.builder()
                .request(request)
                .gdgLeadId(actor.getId())
                .approved(Boolean.TRUE.equals(voteReq.approved()))
                .comment(voteReq.comment())
                .votedAt(Instant.now())
                .build();
        request.getVotes().add(vote);

        if (Boolean.FALSE.equals(voteReq.approved())) {
            request.setStatus(ConsensusStatus.REJECTED);
            request = consensusRequestRepository.save(request);
            return toConsensusDto(request);
        }

        long distinctApprovedLeads = request.getVotes().stream()
                .filter(TeamConsensusVote::isApproved)
                .map(TeamConsensusVote::getGdgLeadId)
                .distinct()
                .count();
        request.setApprovalCount((int) distinctApprovedLeads);

        if (distinctApprovedLeads >= request.getRequiredApprovals()) {
            executeConsensusAction(request);
            request.setStatus(ConsensusStatus.EXECUTED);
            request.setExecutedAt(Instant.now());
        }

        request = consensusRequestRepository.save(request);
        return toConsensusDto(request);
    }

    private void executeConsensusAction(TeamConsensusRequest request) {
        switch (request.getActionType()) {
            case CREATE_TEAM -> {
                String cleanName = request.getProposedName().trim();
                Team team = Team.builder()
                        .name(cleanName)
                        .slug(slugify(cleanName))
                        .description(request.getProposedDescription())
                        .parentTeamId(request.getParentTeamId())
                        .status(TeamStatus.ACTIVE)
                        .build();
                team = teamRepository.save(team);
                request.setExecutedTeamId(team.getId());
            }
            case RENAME_TEAM -> {
                Team team = teamRepository.findById(request.getTargetTeamId())
                        .orElseThrow(() -> new ResourceNotFoundException("Target team not found: " + request.getTargetTeamId()));
                String cleanName = request.getProposedName().trim();
                team.setName(cleanName);
                team.setSlug(slugify(cleanName));
                if (request.getProposedDescription() != null) {
                    team.setDescription(request.getProposedDescription());
                }
                team.setUpdatedAt(Instant.now());
                team = teamRepository.save(team);
                request.setExecutedTeamId(team.getId());
            }
            case DELETE_TEAM -> {
                Team team = teamRepository.findById(request.getTargetTeamId())
                        .orElseThrow(() -> new ResourceNotFoundException("Target team not found: " + request.getTargetTeamId()));
                Instant now = Instant.now();
                team.setStatus(TeamStatus.DELETED);
                team.setDeletedAt(now);
                team.setUpdatedAt(now);
                teamRepository.save(team);

                List<TeamMembership> activeMemberships = teamMembershipRepository.findByTeamIdAndActiveTrue(team.getId());
                for (TeamMembership m : activeMemberships) {
                    m.setActive(false);
                    m.setLeftAt(now);
                    m.setExitReason("TEAM_DELETED");
                }
                teamMembershipRepository.saveAll(activeMemberships);
                request.setExecutedTeamId(team.getId());
            }
        }
    }

    @Transactional(readOnly = true)
    public List<TeamConsensusRequestDTO> listConsensusRequests(ConsensusStatus status) {
        List<TeamConsensusRequest> requests = status != null
                ? consensusRequestRepository.findByStatusOrderByCreatedAtDesc(status)
                : consensusRequestRepository.findAllByOrderByCreatedAtDesc();
        return requests.stream().map(this::toConsensusDto).toList();
    }

    @Transactional(readOnly = true)
    public TeamConsensusRequestDTO getConsensusRequest(Long id) {
        TeamConsensusRequest request = consensusRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Consensus request not found: " + id));
        return toConsensusDto(request);
    }

    @Transactional(readOnly = true)
    public List<TeamDTO> listTeams(boolean includeDeleted, Long parentTeamId) {
        List<Team> teams = includeDeleted
                ? teamRepository.findAll()
                : teamRepository.findByStatusOrderByIdAsc(TeamStatus.ACTIVE);
        return teams.stream()
                .filter(t -> parentTeamId == null || parentTeamId.equals(t.getParentTeamId()))
                .map(this::toTeamDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public TeamDTO getTeam(Long id) {
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found: " + id));
        return toTeamDto(team);
    }

    @Transactional
    public TeamMembershipHistoryDTO assignMemberToTeam(Long teamId, AssignTeamMemberRequestDTO req) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found: " + teamId));
        if (team.getStatus() == TeamStatus.DELETED) {
            throw new BadRequestException("Cannot assign member to a deleted team");
        }

        Member member = memberRepository.findByIdWithRoles(req.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Member not found: " + req.memberId()));
        if (member.getStatus() == MemberStatus.TERMINATED || member.getStatus() == MemberStatus.INACTIVE) {
            throw new BadRequestException("Cannot assign a terminated or inactive member to a team");
        }

        TeamRole targetRole = req.teamRole() != null ? req.teamRole() : TeamRole.MEMBER;
        Optional<TeamMembership> existingOpt = teamMembershipRepository.findFirstByTeamIdAndMemberIdAndActiveTrue(teamId, member.getId());

        TeamMembership membership;
        if (existingOpt.isPresent()) {
            membership = existingOpt.get();
            if (membership.getTeamRole() == targetRole) {
                throw new ConflictException("Member is already an active member of this team with role " + targetRole);
            }
            membership.setTeamRole(targetRole);
            membership = teamMembershipRepository.save(membership);
        } else {
            membership = TeamMembership.builder()
                    .teamId(teamId)
                    .memberId(member.getId())
                    .teamRole(targetRole)
                    .active(true)
                    .joinedAt(Instant.now())
                    .build();
            membership = teamMembershipRepository.save(membership);
        }

        if (targetRole == TeamRole.TEAM_LEAD && !member.getRoles().contains(RoleName.TEAM_LEAD)) {
            member.getRoles().add(RoleName.TEAM_LEAD);
            memberRepository.save(member);
        }

        return toMembershipHistoryDto(membership, team.getName(), member);
    }

    @Transactional
    public TeamMembershipHistoryDTO removeMemberFromTeam(Long teamId, Long memberId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found: " + teamId));

        TeamMembership membership = teamMembershipRepository.findFirstByTeamIdAndMemberIdAndActiveTrue(teamId, memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Active team membership not found for member " + memberId + " in team " + teamId));

        membership.setActive(false);
        membership.setLeftAt(Instant.now());
        membership.setExitReason("REMOVED_FROM_TEAM");
        membership = teamMembershipRepository.save(membership);

        Member member = memberRepository.findById(memberId).orElse(null);
        return toMembershipHistoryDto(membership, team.getName(), member);
    }

    @Transactional(readOnly = true)
    public List<TeamStatsDTO> getAllTeamStats(boolean includeDeleted) {
        List<Team> teams = includeDeleted
                ? teamRepository.findAll()
                : teamRepository.findByStatusOrderByIdAsc(TeamStatus.ACTIVE);
        return teams.stream().map(this::buildTeamStats).toList();
    }

    @Transactional(readOnly = true)
    public TeamStatsDTO getTeamStats(Long teamId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found: " + teamId));
        return buildTeamStats(team);
    }

    private TeamStatsDTO buildTeamStats(Team team) {
        Long teamId = team.getId();
        long activeMembers = teamMembershipRepository.countByTeamIdAndActiveTrue(teamId);
        long pastMembers = teamMembershipRepository.countByTeamIdAndActiveFalse(teamId);
        long subTeams = teamRepository.countByParentTeamIdAndStatus(teamId, TeamStatus.ACTIVE);

        long totalTasks = queryCount(
                "SELECT COUNT(*) FROM tasks WHERE target_team_id = ? AND status NOT IN ('REJECTED', 'CANCELLED')",
                teamId
        );
        long completedTasks = queryCount(
                "SELECT COUNT(*) FROM tasks WHERE target_team_id = ? AND status = 'COMPLETED'",
                teamId
        );
        long inProgressTasks = queryCount(
                "SELECT COUNT(*) FROM tasks WHERE target_team_id = ? AND status IN ('ASSIGNED', 'IN_PROGRESS', 'BLOCKED', 'IN_REVIEW')",
                teamId
        );
        long overdueTasks = queryCount(
                "SELECT COUNT(*) FROM tasks WHERE target_team_id = ? AND status NOT IN ('COMPLETED', 'REJECTED', 'CANCELLED') AND deadline < ?",
                teamId,
                Timestamp.from(Instant.now())
        );

        double completionRate = totalTasks == 0 ? 0.0 : Math.round((completedTasks * 10000.0) / totalTasks) / 100.0;
        long activeCollaborations = collaborationTeamApprovalRepository.countActiveCollaborationsForTeam(teamId);

        return new TeamStatsDTO(
                teamId,
                team.getName(),
                team.getParentTeamId(),
                team.getStatus(),
                activeMembers,
                pastMembers,
                subTeams,
                totalTasks,
                completedTasks,
                inProgressTasks,
                overdueTasks,
                completionRate,
                activeCollaborations
        );
    }

    private long queryCount(String sql, Object... args) {
        Long val = jdbcTemplate.queryForObject(sql, Long.class, args);
        return val != null ? val : 0L;
    }

    @Transactional(readOnly = true)
    public List<TeamMembershipHistoryDTO> getAllPastMembers(Long teamId, Long memberId, String exitReason) {
        List<TeamMembership> past = teamId != null
                ? teamMembershipRepository.findByTeamIdAndActiveFalseOrderByLeftAtDesc(teamId)
                : teamMembershipRepository.findByActiveFalseOrderByLeftAtDesc();

        return past.stream()
                .filter(m -> memberId == null || memberId.equals(m.getMemberId()))
                .filter(m -> exitReason == null || exitReason.equalsIgnoreCase(m.getExitReason()))
                .map(this::toMembershipHistoryDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TeamMembershipHistoryDTO> getTeamPastMembers(Long teamId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found: " + teamId));
        return teamMembershipRepository.findByTeamIdAndActiveFalseOrderByLeftAtDesc(team.getId())
                .stream()
                .map(m -> toMembershipHistoryDto(m, team.getName(), memberRepository.findById(m.getMemberId()).orElse(null)))
                .toList();
    }

    public TeamMembershipHistoryDTO toMembershipHistoryDto(TeamMembership m) {
        String teamName = teamRepository.findById(m.getTeamId()).map(Team::getName).orElse("Team " + m.getTeamId());
        Member member = memberRepository.findById(m.getMemberId()).orElse(null);
        return toMembershipHistoryDto(m, teamName, member);
    }

    private TeamMembershipHistoryDTO toMembershipHistoryDto(TeamMembership m, String teamName, Member member) {
        return new TeamMembershipHistoryDTO(
                m.getId(),
                m.getTeamId(),
                teamName,
                m.getMemberId(),
                member != null ? member.getFullName() : "Member " + m.getMemberId(),
                member != null ? member.getEmail() : null,
                m.getTeamRole(),
                m.isActive(),
                m.getJoinedAt(),
                m.getLeftAt(),
                m.getExitReason()
        );
    }

    private TeamDTO toTeamDto(Team team) {
        List<TeamMembershipHistoryDTO> activeMembers = teamMembershipRepository
                .findByTeamIdAndActiveTrueOrderByJoinedAtAsc(team.getId())
                .stream()
                .map(m -> toMembershipHistoryDto(m, team.getName(), memberRepository.findById(m.getMemberId()).orElse(null)))
                .toList();

        List<Long> subTeamIds = teamRepository.findByParentTeamIdAndStatusOrderByIdAsc(team.getId(), TeamStatus.ACTIVE)
                .stream()
                .map(Team::getId)
                .toList();

        return new TeamDTO(
                team.getId(),
                team.getName(),
                team.getSlug(),
                team.getDescription(),
                team.getParentTeamId(),
                team.getStatus(),
                activeMembers.size(),
                subTeamIds,
                activeMembers,
                team.getCreatedAt(),
                team.getDeletedAt()
        );
    }

    private TeamConsensusRequestDTO toConsensusDto(TeamConsensusRequest r) {
        List<ConsensusVoteDTO> voteDtos = r.getVotes().stream()
                .map(v -> {
                    String leadName = memberRepository.findById(v.getGdgLeadId())
                            .map(Member::getFullName)
                            .orElse("Lead " + v.getGdgLeadId());
                    return new ConsensusVoteDTO(
                            v.getId(),
                            v.getGdgLeadId(),
                            leadName,
                            v.isApproved(),
                            v.getComment(),
                            v.getVotedAt()
                    );
                })
                .toList();

        return new TeamConsensusRequestDTO(
                r.getId(),
                r.getActionType(),
                r.getTargetTeamId(),
                r.getProposedName(),
                r.getProposedDescription(),
                r.getParentTeamId(),
                r.getInitiatedById(),
                r.getStatus(),
                r.getApprovalCount(),
                r.getRequiredApprovals(),
                r.getExecutedTeamId(),
                r.getCreatedAt(),
                r.getExecutedAt(),
                voteDtos
        );
    }

    private String slugify(String input) {
        String base = input.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return base.isEmpty() ? "team-" + System.currentTimeMillis() : base;
    }
}
