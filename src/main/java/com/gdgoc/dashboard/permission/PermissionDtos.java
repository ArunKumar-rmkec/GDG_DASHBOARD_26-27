package com.gdgoc.dashboard.permission;

import com.gdgoc.dashboard.member.RoleName;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public class PermissionDtos {

    public record CreatePermissionGrantRequestDTO(
            Long granteeMemberId,
            RoleName granteeRole,
            @NotNull PermissionCode permissionCode,
            Long scopeTeamId,
            Long scopeEventId,
            Long crossTeamTargetTeamId,
            Instant validFrom,
            Instant validUntil,
            String reason
    ) {}

    public record ApproveGrantRequestDTO(
            @NotNull Boolean approved,
            String comment
    ) {}

    public record PermissionGrantDTO(
            Long id,
            Long grantedById,
            Long granteeMemberId,
            RoleName granteeRole,
            PermissionCode permissionCode,
            Long scopeTeamId,
            Long scopeEventId,
            Long crossTeamTargetTeamId,
            Long approvedByTeamLeadId,
            Instant approvedAt,
            String approvalComment,
            Instant validFrom,
            Instant validUntil,
            GrantStatus status,
            boolean revoked,
            Instant revokedAt,
            Long revokedById,
            String reason,
            boolean currentlyActive,
            Instant createdAt
    ) {}
}
