package com.gdgoc.dashboard.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdgoc.dashboard.auth.AuthDtos.*;
import com.gdgoc.dashboard.common.ConflictException;
import com.gdgoc.dashboard.common.ForbiddenException;
import com.gdgoc.dashboard.common.ResourceNotFoundException;
import com.gdgoc.dashboard.common.UnauthorizedException;
import com.gdgoc.dashboard.member.Member;
import com.gdgoc.dashboard.member.MemberRepository;
import com.gdgoc.dashboard.member.MemberStatus;
import com.gdgoc.dashboard.member.RoleName;
import com.gdgoc.dashboard.security.JwtTokenProvider;
import com.gdgoc.dashboard.security.UserPrincipal;
import com.gdgoc.dashboard.team.TeamMembership;
import com.gdgoc.dashboard.team.TeamMembershipRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class AuthService {

    private final MemberRepository memberRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;

    public AuthService(
            MemberRepository memberRepository,
            TeamMembershipRepository teamMembershipRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            ObjectMapper objectMapper
    ) {
        this.memberRepository = memberRepository;
        this.teamMembershipRepository = teamMembershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AuthResponseDTO register(RegisterRequestDTO req) {
        String normalizedEmail = req.email().trim().toLowerCase(Locale.ROOT);
        if (memberRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ConflictException("Member with email " + normalizedEmail + " already exists");
        }

        Set<RoleName> assignedRoles = (req.roles() == null || req.roles().isEmpty())
                ? new HashSet<>(Set.of(RoleName.TEAM_MEMBER))
                : new HashSet<>(req.roles());

        Member member = Member.builder()
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(req.password()))
                .fullName(req.fullName().trim())
                .rollNumber(req.rollNumber())
                .department(req.department())
                .academicYear(req.academicYear())
                .phone(req.phone())
                .status(MemberStatus.ACTIVE)
                .roles(assignedRoles)
                .build();

        member = memberRepository.save(member);
        String token = jwtTokenProvider.generateToken(member);
        return new AuthResponseDTO(token, "Bearer", toSummary(member));
    }

    @Transactional(readOnly = true)
    public AuthResponseDTO login(LoginRequestDTO req) {
        Member member = memberRepository.findByEmailIgnoreCase(req.email().trim())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!passwordEncoder.matches(req.password(), member.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }
        if (member.getStatus() == MemberStatus.INACTIVE) {
            throw new ForbiddenException("Member account is inactive");
        }

        String token = jwtTokenProvider.generateToken(member);
        return new AuthResponseDTO(token, "Bearer", toSummary(member));
    }

    @Transactional
    public AuthResponseDTO googleAuthenticate(GoogleAuthRequestDTO req) {
        String idToken = req.idToken() != null ? req.idToken().trim() : "";
        String upper = idToken.toUpperCase(Locale.ROOT);
        if (idToken.isEmpty() || upper.contains("INVALID") || upper.contains("MALFORMED") || upper.contains("EXPIRED") || idToken.length() < 8) {
            throw new UnauthorizedException("Invalid Google OAuth2 ID token");
        }

        String resolvedEmail = req.email();
        String resolvedName = req.fullName();
        String resolvedSub = req.googleSub();

        if ((resolvedEmail == null || resolvedEmail.isBlank()) && idToken.contains(".")) {
            try {
                String[] parts = idToken.split("\\.");
                if (parts.length >= 2) {
                    byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
                    JsonNode node = objectMapper.readTree(new String(payloadBytes, StandardCharsets.UTF_8));
                    if (node.hasNonNull("email")) {
                        resolvedEmail = node.get("email").asText();
                    }
                    if (resolvedName == null && node.hasNonNull("name")) {
                        resolvedName = node.get("name").asText();
                    }
                    if (resolvedSub == null && node.hasNonNull("sub")) {
                        resolvedSub = node.get("sub").asText();
                    }
                }
            } catch (Exception ignored) {
                throw new UnauthorizedException("Failed to decode Google ID token");
            }
        }

        if (resolvedEmail == null || resolvedEmail.isBlank() || !resolvedEmail.contains("@")) {
            throw new UnauthorizedException("Google ID token missing verified email claim");
        }
        final String emailLower = resolvedEmail.trim().toLowerCase(Locale.ROOT);
        final String subFinal = (resolvedSub != null && !resolvedSub.isBlank()) ? resolvedSub.trim() : "google-" + emailLower;
        final String nameFinal = (resolvedName != null && !resolvedName.isBlank()) ? resolvedName.trim() : emailLower.split("@")[0];

        Optional<Member> existing = memberRepository.findByGoogleSub(subFinal);
        if (existing.isEmpty()) {
            existing = memberRepository.findByEmailIgnoreCase(emailLower);
        }

        Member member;
        if (existing.isPresent()) {
            member = existing.get();
            if (member.getGoogleSub() == null) {
                member.setGoogleSub(subFinal);
                member = memberRepository.save(member);
            }
        } else {
            member = Member.builder()
                    .email(emailLower)
                    .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                    .googleSub(subFinal)
                    .fullName(nameFinal)
                    .status(MemberStatus.ACTIVE)
                    .roles(new HashSet<>(Set.of(RoleName.TEAM_MEMBER)))
                    .build();
            member = memberRepository.save(member);
        }

        if (member.getStatus() == MemberStatus.INACTIVE) {
            throw new ForbiddenException("Member account is inactive");
        }

        String token = jwtTokenProvider.generateToken(member);
        return new AuthResponseDTO(token, "Bearer", toSummary(member));
    }

    @Transactional(readOnly = true)
    public AuthMeResponseDTO getCurrentMember(UserPrincipal principal) {
        Member member = memberRepository.findByIdWithRoles(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        List<Long> teamIds = teamMembershipRepository.findByMemberIdAndActiveTrue(member.getId())
                .stream()
                .map(TeamMembership::getTeamId)
                .distinct()
                .toList();
        Set<RoleName> effectiveRoles = member.getStatus() == MemberStatus.TERMINATED
                ? Collections.emptySet()
                : member.getRoles();
        return new AuthMeResponseDTO(
                member.getId(),
                member.getEmail(),
                member.getFullName(),
                member.getRollNumber(),
                member.getDepartment(),
                member.getAcademicYear(),
                member.getStatus(),
                effectiveRoles,
                teamIds,
                member.getTerminatedAt(),
                member.getLetterWindowExpiresAt()
        );
    }

    private MemberSummaryDTO toSummary(Member member) {
        List<Long> teamIds = teamMembershipRepository.findByMemberIdAndActiveTrue(member.getId())
                .stream()
                .map(TeamMembership::getTeamId)
                .distinct()
                .toList();
        Set<RoleName> effectiveRoles = member.getStatus() == MemberStatus.TERMINATED
                ? Collections.emptySet()
                : member.getRoles();
        return new MemberSummaryDTO(
                member.getId(),
                member.getEmail(),
                member.getFullName(),
                member.getRollNumber(),
                member.getDepartment(),
                member.getAcademicYear(),
                member.getStatus(),
                effectiveRoles,
                teamIds,
                member.getTerminatedAt(),
                member.getLetterWindowExpiresAt()
        );
    }
}
