package com.gdgoc.dashboard.team;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public class TeamDtos {

    public record CreateConsensusRequestDTO(
            @NotNull ConsensusActionType actionType,
            Long targetTeamId,
            String proposedName,
            String proposedDescription,
            Long parentTeamId
    ) {}

    public record ConsensusVoteRequestDTO(
            @NotNull Boolean approved,
            String comment
    ) {}

    public record ConsensusVoteDTO(
            Long id,
            Long gdgLeadId,
            String gdgLeadName,
            boolean approved,
            String comment,
            Instant votedAt
    ) {}

    public record TeamConsensusRequestDTO(
            Long id,
            ConsensusActionType actionType,
            Long targetTeamId,
            String proposedName,
            String proposedDescription,
            Long parentTeamId,
            Long initiatedById,
            ConsensusStatus status,
            int approvalCount,
            int requiredApprovals,
            Long executedTeamId,
            Instant createdAt,
            Instant executedAt,
            List<ConsensusVoteDTO> approvals
    ) {}

    public record AssignTeamMemberRequestDTO(
            @NotNull Long memberId,
            TeamRole teamRole
    ) {}

    public record TeamMembershipHistoryDTO(
            Long membershipId,
            Long teamId,
            String teamName,
            Long memberId,
            String memberName,
            String memberEmail,
            TeamRole teamRole,
            boolean active,
            Instant joinedAt,
            Instant leftAt,
            String exitReason
    ) {}

    public record TeamDTO(
            Long id,
            String name,
            String slug,
            String description,
            Long parentTeamId,
            TeamStatus status,
            long activeMemberCount,
            List<Long> subTeamIds,
            List<TeamMembershipHistoryDTO> members,
            Instant createdAt,
            Instant deletedAt
    ) {}

    public record TeamStatsDTO(
            Long teamId,
            String teamName,
            Long parentTeamId,
            TeamStatus status,
            long activeMemberCount,
            long pastMemberCount,
            long subTeamCount,
            long totalTasks,
            long completedTasks,
            long inProgressTasks,
            long overdueTasks,
            double taskCompletionRate,
            long activeCollaborations
    ) {}
}
