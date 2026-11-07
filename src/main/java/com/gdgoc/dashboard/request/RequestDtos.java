package com.gdgoc.dashboard.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public class RequestDtos {

    public record TeamChangeRequestDTO(
            Long currentTeamId,
            @NotNull Long targetTeamId,
            @NotBlank String reason
    ) {}

    public record ApproveChangeRequestDTO(
            @NotNull Boolean approved,
            String comment
    ) {}

    public record MemberChangeRequestDTO(
            Long id,
            Long targetMemberId,
            Long initiatedById,
            ChangeRequestType requestType,
            String payloadJson,
            Long currentTeamId,
            Long targetTeamId,
            String reason,
            ChangeRequestStatus status,
            boolean hrLeadApproved,
            Long hrLeadApprovedById,
            Instant hrLeadApprovedAt,
            boolean teamLeadApproved,
            Long teamLeadApprovedById,
            Instant teamLeadApprovedAt,
            Long gdgLeadOverrideById,
            String reviewComment,
            Long executedMemberId,
            Instant createdAt,
            Instant executedAt
    ) {}
}
