package com.gdgoc.dashboard.letter;

import com.gdgoc.dashboard.member.MemberStatus;
import com.gdgoc.dashboard.member.RoleName;
import com.gdgoc.dashboard.team.TeamDtos.TeamMembershipHistoryDTO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public class LetterDtos {

    public record IssueLetterRequestDTO(
            @NotNull Long memberId,
            @NotNull LetterType letterType,
            @NotBlank String title,
            @NotBlank String contentHtml
    ) {}

    public record MemberLetterDTO(
            Long id,
            Long memberId,
            String memberName,
            String memberEmail,
            LetterType letterType,
            String title,
            String contentHtml,
            String storageKey,
            Long issuedById,
            Instant issuedAt,
            int downloadCount,
            Instant lastDownloadedAt
    ) {}

    public record SignedUrlResponseDTO(
            Long letterId,
            String token,
            String signedUrl,
            Instant expiresAt
    ) {}

    public record MemberProfileDTO(
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
            List<Long> teamIds,
            Instant terminatedAt,
            String terminationReason,
            Instant letterWindowExpiresAt,
            boolean letterDownloadWindowActive,
            long lettersCount
    ) {}
}
