package com.gdgoc.dashboard.auth;

import com.gdgoc.dashboard.member.MemberStatus;
import com.gdgoc.dashboard.member.RoleName;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public class AuthDtos {

    public record RegisterRequestDTO(
            @NotBlank @Email String email,
            @NotBlank String password,
            @NotBlank String fullName,
            String rollNumber,
            String department,
            Integer academicYear,
            String phone,
            Set<RoleName> roles
    ) {}

    public record LoginRequestDTO(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    public record GoogleAuthRequestDTO(
            @NotBlank String idToken,
            String email,
            String fullName,
            String googleSub
    ) {}

    public record MemberSummaryDTO(
            Long id,
            String email,
            String fullName,
            String rollNumber,
            String department,
            Integer academicYear,
            MemberStatus status,
            Set<RoleName> roles,
            List<Long> teamIds,
            Instant terminatedAt,
            Instant letterWindowExpiresAt
    ) {}

    public record AuthResponseDTO(
            String accessToken,
            String tokenType,
            MemberSummaryDTO member
    ) {}

    public record AuthMeResponseDTO(
            Long id,
            String email,
            String fullName,
            String rollNumber,
            String department,
            Integer academicYear,
            MemberStatus status,
            Set<RoleName> roles,
            List<Long> teamIds,
            Instant terminatedAt,
            Instant letterWindowExpiresAt
    ) {}
}
