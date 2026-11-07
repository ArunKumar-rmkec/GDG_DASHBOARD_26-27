package com.gdgoc.dashboard.collaboration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public class CollaborationDtos {

    public record CreateCollaborationRequestDTO(
            @NotBlank String title,
            String description,
            CollaborationType collaborationType,
            Long leadTeamId,
            @NotEmpty List<Long> participatingTeamIds,
            Instant deadline
    ) {}

    public record ApproveCollaborationTeamRequestDTO(
            @NotNull Long teamId,
            @NotNull Boolean approved,
            String comment
    ) {}

    public record CollaborationTeamApprovalDTO(
            Long teamId,
            String teamName,
            ApprovalStatus approvalStatus,
            Long approvedById,
            String comment,
            Instant approvedAt
    ) {}

    public record CollaborationDTO(
            Long id,
            String title,
            String description,
            CollaborationType collaborationType,
            Long leadTeamId,
            Long createdById,
            CollaborationStatus status,
            Instant deadline,
            List<Long> participatingTeamIds,
            List<CollaborationTeamApprovalDTO> teamApprovals,
            Instant createdAt
    ) {}
}
