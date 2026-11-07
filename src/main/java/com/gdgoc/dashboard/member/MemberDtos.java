package com.gdgoc.dashboard.member;

import com.gdgoc.dashboard.request.RequestDtos.MemberChangeRequestDTO;
import com.gdgoc.dashboard.team.TeamDtos.TeamMembershipHistoryDTO;
import com.gdgoc.dashboard.team.TeamRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public class MemberDtos {

    public record HrAddMemberRequestDTO(
            @NotBlank @Email String email,
            String password,
            @NotBlank String fullName,
            String rollNumber,
            String department,
            Integer academicYear,
            String phone,
            String bio,
            String skills,
            String githubUrl,
            String linkedinUrl,
            Set<RoleName> roles,
            Long teamId,
            TeamRole teamRole,
            String reason
    ) {}

    public record BioDataUpdateRequestDTO(
            String fullName,
            String rollNumber,
            String department,
            Integer academicYear,
            String phone,
            String bio,
            String skills,
            String githubUrl,
            String linkedinUrl,
            String reason
    ) {}

    public record TerminateMemberRequestDTO(
            String reason,
            Instant terminatedAt
    ) {}

    public record MemberTaskSummaryDTO(
            long totalAssignedTasks,
            long completedTasks,
            long inProgressTasks,
            long overdueTasks
    ) {}

    public record MemberDetailDTO(
            Long id,
            String email,
            String fullName,
            String rollNumber,
            String department,
            Integer academicYear,
            String phone,
            String bio,
            String skills,
            String githubUrl,
            String linkedinUrl,
            MemberStatus status,
            Set<RoleName> roles,
            List<TeamMembershipHistoryDTO> activeTeams,
            List<TeamMembershipHistoryDTO> pastTeams,
            String pcaComplianceStatus,
            String pcaStatus,
            Boolean pcaSigned,
            Boolean pcaCompliant,
            long signedAgreementsCount,
            MemberTaskSummaryDTO taskSummary,
            Instant terminatedAt,
            String terminationReason,
            Instant letterWindowExpiresAt,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record MemberMutationResponseDTO(
            String executionStatus,
            Long changeRequestId,
            MemberDetailDTO member,
            MemberChangeRequestDTO changeRequest
    ) {}
}
