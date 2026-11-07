package com.gdgoc.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdgoc.dashboard.auth.AuthDtos.LoginRequestDTO;
import com.gdgoc.dashboard.auth.AuthDtos.RegisterRequestDTO;
import com.gdgoc.dashboard.letter.LetterDtos.IssueLetterRequestDTO;
import com.gdgoc.dashboard.letter.LetterType;
import com.gdgoc.dashboard.letter.SignedUrlService;
import com.gdgoc.dashboard.member.Member;
import com.gdgoc.dashboard.member.MemberDtos.TerminateMemberRequestDTO;
import com.gdgoc.dashboard.member.MemberRepository;
import com.gdgoc.dashboard.member.MemberStatus;
import com.gdgoc.dashboard.member.RoleName;
import com.gdgoc.dashboard.permission.PermissionCode;
import com.gdgoc.dashboard.permission.PermissionDtos.ApproveGrantRequestDTO;
import com.gdgoc.dashboard.permission.PermissionDtos.CreatePermissionGrantRequestDTO;
import com.gdgoc.dashboard.team.Team;
import com.gdgoc.dashboard.team.TeamMembership;
import com.gdgoc.dashboard.team.TeamMembershipRepository;
import com.gdgoc.dashboard.team.TeamRepository;
import com.gdgoc.dashboard.team.TeamRole;
import com.gdgoc.dashboard.team.TeamStatus;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class ChallengerM1SecurityBoundaryAdversarialTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMembershipRepository teamMembershipRepository;

    @Autowired
    private SignedUrlService signedUrlService;

    @Autowired
    private com.gdgoc.dashboard.letter.MemberLetterRepository memberLetterRepository;

    @Value("${app.jwt.secret:gdgoc-campus-dashboard-2026-2027-super-secret-signing-key-at-least-64-bytes-long}")
    private String jwtSecret;

    private record RegisteredUser(Long id, String token, String email) {}

    private RegisteredUser registerUser(String email, String fullName, Set<RoleName> roles) throws Exception {
        RegisterRequestDTO req = new RegisterRequestDTO(
                email, "Password123!", fullName, "22CS099", "CSE", 3, "+91-9876543210", roles
        );
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        return new RegisteredUser(
                node.get("member").get("id").asLong(),
                node.get("accessToken").asText(),
                email
        );
    }

    private SecretKey getSystemJwtKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[64];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    // =========================================================================
    // FOCUS 1: Malformed / Expired / Tampered JWT Tokens on Protected Endpoints
    // =========================================================================

    @Test
    @DisplayName("Focus 1: Tampered, malformed, expired, wrong-key, and inactive-member JWTs rejected with 401")
    void testFocus1_JwtTamperingAndMalformedTokens() throws Exception {
        RegisteredUser user = registerUser("jwt.probe@gdgoc.edu", "JWT Probe User", Set.of(RoleName.TEAM_MEMBER));
        String validToken = user.token();

        // 1.1 Tampered signature: modify the trailing signature characters
        String tamperedToken = validToken.substring(0, validToken.length() - 6) + "XXXXXX";
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized());

        // 1.2 Expired JWT token: create a token with expiration in the past
        Date past = new Date(System.currentTimeMillis() - 10000);
        Date pastIssued = new Date(System.currentTimeMillis() - 20000);
        String expiredToken = Jwts.builder()
                .subject(String.valueOf(user.id()))
                .claim("email", user.email())
                .issuedAt(pastIssued)
                .expiration(past)
                .signWith(getSystemJwtKey())
                .compact();
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());

        // 1.3 JWT signed with an untrusted / forged secret key
        SecretKey forgedKey = Keys.hmacShaKeyFor("attacker-forged-secret-key-at-least-64-bytes-long-padding-for-hmac-sha-256".getBytes(StandardCharsets.UTF_8));
        String forgedToken = Jwts.builder()
                .subject(String.valueOf(user.id()))
                .claim("email", user.email())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(forgedKey)
                .compact();
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + forgedToken))
                .andExpect(status().isUnauthorized());

        // 1.4 Non-existent member ID in validly signed JWT
        String phantomToken = Jwts.builder()
                .subject("99999999")
                .claim("email", "phantom@gdgoc.edu")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(getSystemJwtKey())
                .compact();
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + phantomToken))
                .andExpect(status().isUnauthorized());

        // 1.5 Malformed token headers & formats
        String[] malformedTokens = {
                "",
                "null",
                "Bearer",
                "Bearer ",
                "Bearer abc.def",
                "Bearer ..",
                "Bearer header.payload", // missing signature
                "Bearer " + validToken + ".extra",
                "Basic dXNlcjpwYXNz", // wrong auth scheme
                "Token " + validToken
        };
        for (String badAuth : malformedTokens) {
            mockMvc.perform(get("/api/v1/auth/me")
                            .header("Authorization", badAuth))
                    .andExpect(status().isUnauthorized());
        }

        // 1.6 Inactive member token rejected (401 Unauthorized)
        Member member = memberRepository.findById(user.id()).orElseThrow();
        member.setStatus(MemberStatus.INACTIVE);
        memberRepository.save(member);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/profile/me")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // FOCUS 2: Unauthorized Attempts to Grant Permissions by Non-Leads
    // =========================================================================

    @Test
    @DisplayName("Focus 2: Unauthorized permission granting attempts by TEAM_MEMBER, HR_MEMBER, EVENT_TEAM_MEMBER rejected with 403")
    void testFocus2_UnauthorizedPermissionGrantingByNonLeads() throws Exception {
        RegisteredUser gdgLead = registerUser("gdg.lead.f2@gdgoc.edu", "GDG Lead", Set.of(RoleName.GDG_LEAD));
        RegisteredUser teamMember = registerUser("team.member.f2@gdgoc.edu", "Team Member", Set.of(RoleName.TEAM_MEMBER));
        RegisteredUser hrMember = registerUser("hr.member.f2@gdgoc.edu", "HR Member", Set.of(RoleName.HR_MEMBER));
        RegisteredUser eventTeamMember = registerUser("event.member.f2@gdgoc.edu", "Event Team Member", Set.of(RoleName.EVENT_TEAM_MEMBER));
        RegisteredUser targetMember = registerUser("target.f2@gdgoc.edu", "Target Member", Set.of(RoleName.TEAM_MEMBER));

        CreatePermissionGrantRequestDTO req = new CreatePermissionGrantRequestDTO(
                targetMember.id(), null, PermissionCode.VIEW_TEAM_STATS, null, null, null,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Malicious Grant Attempt"
        );

        // 2.1 Standard TEAM_MEMBER cannot grant permissions -> 403 Forbidden
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + teamMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        // 2.2 HR_MEMBER (non-lead) cannot grant permissions -> 403 Forbidden
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + hrMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        // 2.3 EVENT_TEAM_MEMBER (non-lead) cannot grant permissions -> 403 Forbidden
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + eventTeamMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        // 2.4 Anonymous request cannot grant permissions -> 401 Unauthorized
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());

        // 2.5 Non-lead cannot list all grants -> 403 Forbidden
        mockMvc.perform(get("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + teamMember.token()))
                .andExpect(status().isForbidden());

        // 2.6 Active member CAN view their own grants via /my
        mockMvc.perform(get("/api/v1/permissions/grants/my")
                        .header("Authorization", "Bearer " + teamMember.token()))
                .andExpect(status().isOk());

        // Create an active grant by GDG_LEAD
        MvcResult grantRes = mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        long grantId = objectMapper.readTree(grantRes.getResponse().getContentAsString()).get("id").asLong();

        // 2.7 Non-lead cannot approve grants -> 403 Forbidden
        mockMvc.perform(post("/api/v1/permissions/grants/" + grantId + "/approve")
                        .header("Authorization", "Bearer " + teamMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveGrantRequestDTO(true, "Malicious approve"))))
                .andExpect(status().isForbidden());

        // 2.8 Non-lead cannot revoke grants -> 403 Forbidden
        mockMvc.perform(delete("/api/v1/permissions/grants/" + grantId)
                        .header("Authorization", "Bearer " + teamMember.token()))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // FOCUS 3: Delegation Attempts for Non-Shareable Permissions
    // =========================================================================

    @Test
    @DisplayName("Focus 3: All 5 Non-Shareable permissions rejected with 400 Bad Request, plus role restriction enforcement")
    void testFocus3_NonShareablePermissionsAndRoleRestrictions() throws Exception {
        RegisteredUser gdgLead = registerUser("gdg.lead.f3@gdgoc.edu", "GDG Lead", Set.of(RoleName.GDG_LEAD));
        RegisteredUser regularMember = registerUser("reg.member.f3@gdgoc.edu", "Regular Member", Set.of(RoleName.TEAM_MEMBER));
        RegisteredUser hrMember = registerUser("hr.member.f3@gdgoc.edu", "HR Member", Set.of(RoleName.HR_MEMBER));
        RegisteredUser eventMember = registerUser("event.member.f3@gdgoc.edu", "Event Member", Set.of(RoleName.EVENT_TEAM_MEMBER));

        // 3.1 All 5 Non-shareable permissions must return 400 Bad Request
        PermissionCode[] nonShareablePermissions = {
                PermissionCode.SUPER_ADMIN,
                PermissionCode.GRANT_PERMISSIONS,
                PermissionCode.TEAM_LIFECYCLE_CONSENSUS,
                PermissionCode.PROJECT_CONTRIBUTOR_AGREEMENT,
                PermissionCode.ASSIGN_TASK_TO_TEAM_LEAD
        };

        for (PermissionCode code : nonShareablePermissions) {
            CreatePermissionGrantRequestDTO req = new CreatePermissionGrantRequestDTO(
                    regularMember.id(), null, code, null, null, null,
                    Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Try delegating non-shareable " + code
            );
            mockMvc.perform(post("/api/v1/permissions/grants")
                            .header("Authorization", "Bearer " + gdgLead.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cannot be shared or delegated")));
        }

        // 3.2 Target Role Restriction: VIEW_MEMBER_DETAILS can ONLY be delegated to HR_MEMBER
        CreatePermissionGrantRequestDTO viewMemberWrongRole = new CreatePermissionGrantRequestDTO(
                regularMember.id(), null, PermissionCode.VIEW_MEMBER_DETAILS, null, null, null,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Delegating to non-HR member"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(viewMemberWrongRole)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("can only be shared with roles")));

        // Valid delegation to HR_MEMBER succeeds
        CreatePermissionGrantRequestDTO viewMemberValid = new CreatePermissionGrantRequestDTO(
                hrMember.id(), null, PermissionCode.VIEW_MEMBER_DETAILS, null, null, null,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Valid delegation to HR member"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(viewMemberValid)))
                .andExpect(status().isCreated());

        // 3.3 Target Role Restriction: EVENT_MANAGEMENT can ONLY be delegated to EVENT_TEAM_MEMBER
        CreatePermissionGrantRequestDTO eventMgmtWrongRole = new CreatePermissionGrantRequestDTO(
                regularMember.id(), null, PermissionCode.EVENT_MANAGEMENT, null, null, null,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Delegating to non-event member"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(eventMgmtWrongRole)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("can only be shared with roles")));

        // 3.4 Target Role Restriction: CREATE_CROSS_TEAM_COLLAB can ONLY be delegated to HR_MEMBER
        CreatePermissionGrantRequestDTO collabWrongRole = new CreatePermissionGrantRequestDTO(
                regularMember.id(), null, PermissionCode.CREATE_CROSS_TEAM_COLLAB, null, null, null,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Delegating to non-HR member"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(collabWrongRole)))
                .andExpect(status().isBadRequest());

        // 3.5 Missing both granteeMemberId and granteeRole -> 400 Bad Request
        CreatePermissionGrantRequestDTO noGrantee = new CreatePermissionGrantRequestDTO(
                null, null, PermissionCode.VIEW_TEAM_STATS, null, null, null,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "No grantee"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noGrantee)))
                .andExpect(status().isBadRequest());

        // 3.6 Non-existent scopeTeamId or crossTeamTargetTeamId -> 400 Bad Request
        CreatePermissionGrantRequestDTO nonExistentTeam = new CreatePermissionGrantRequestDTO(
                regularMember.id(), null, PermissionCode.ASSIGN_TASK_INTRA_TEAM, 999999L, null, null,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Bad scope team"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nonExistentTeam)))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // FOCUS 4: Inverted Time Windows & Expired / Future Scheduled Grants
    // =========================================================================

    @Test
    @DisplayName("Focus 4: Inverted time windows rejected (400), expired/future grants do not grant access (403), revoked grants immediately lose access")
    void testFocus4_InvertedWindowsAndScheduledAccessLifecycle() throws Exception {
        RegisteredUser gdgLead = registerUser("gdg.lead.f4@gdgoc.edu", "GDG Lead", Set.of(RoleName.GDG_LEAD));
        RegisteredUser teamMember = registerUser("probe.member.f4@gdgoc.edu", "Probe Member", Set.of(RoleName.TEAM_MEMBER));

        // 4.1 Inverted window: validUntil is strictly before validFrom -> 400 Bad Request
        CreatePermissionGrantRequestDTO invertedReq = new CreatePermissionGrantRequestDTO(
                teamMember.id(), null, PermissionCode.VIEW_TEAM_STATS, null, null, null,
                Instant.now().plus(2, ChronoUnit.HOURS),
                Instant.now().plus(1, ChronoUnit.HOURS),
                "Inverted window: validUntil before validFrom"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invertedReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("validUntil must be strictly after validFrom")));

        // 4.2 Equal window: validUntil == validFrom -> 400 Bad Request
        Instant sameInstant = Instant.now().plus(1, ChronoUnit.HOURS);
        CreatePermissionGrantRequestDTO equalWindowReq = new CreatePermissionGrantRequestDTO(
                teamMember.id(), null, PermissionCode.VIEW_TEAM_STATS, null, null, null,
                sameInstant, sameInstant, "Equal window"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(equalWindowReq)))
                .andExpect(status().isBadRequest());

        // 4.3 Expired grant: validFrom = NOW - 2h, validUntil = NOW - 1h (already expired)
        CreatePermissionGrantRequestDTO expiredGrantReq = new CreatePermissionGrantRequestDTO(
                teamMember.id(), null, PermissionCode.VIEW_TEAM_STATS, null, null, null,
                Instant.now().minus(2, ChronoUnit.HOURS),
                Instant.now().minus(1, ChronoUnit.HOURS),
                "Already expired scheduled grant"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(expiredGrantReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentlyActive").value(false));

        // Member still gets 403 Forbidden because grant is expired!
        mockMvc.perform(get("/api/v1/teams/stats")
                        .header("Authorization", "Bearer " + teamMember.token()))
                .andExpect(status().isForbidden());

        // 4.4 Future grant: validFrom = NOW + 1h, validUntil = NOW + 3h
        CreatePermissionGrantRequestDTO futureGrantReq = new CreatePermissionGrantRequestDTO(
                teamMember.id(), null, PermissionCode.VIEW_TEAM_STATS, null, null, null,
                Instant.now().plus(1, ChronoUnit.HOURS),
                Instant.now().plus(3, ChronoUnit.HOURS),
                "Future scheduled grant"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(futureGrantReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentlyActive").value(false));

        // Member still gets 403 Forbidden because grant has not started yet!
        mockMvc.perform(get("/api/v1/teams/stats")
                        .header("Authorization", "Bearer " + teamMember.token()))
                .andExpect(status().isForbidden());

        // 4.5 Active grant allows access, then immediate revocation terminates access
        CreatePermissionGrantRequestDTO activeGrantReq = new CreatePermissionGrantRequestDTO(
                teamMember.id(), null, PermissionCode.VIEW_TEAM_STATS, null, null, null,
                Instant.now().minusSeconds(100),
                Instant.now().plus(1, ChronoUnit.HOURS),
                "Currently active grant"
        );
        MvcResult activeRes = mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(activeGrantReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentlyActive").value(true))
                .andReturn();
        long activeGrantId = objectMapper.readTree(activeRes.getResponse().getContentAsString()).get("id").asLong();

        // Access succeeds (200 OK)
        mockMvc.perform(get("/api/v1/teams/stats")
                        .header("Authorization", "Bearer " + teamMember.token()))
                .andExpect(status().isOk());

        // Revoke grant
        mockMvc.perform(delete("/api/v1/permissions/grants/" + activeGrantId)
                        .header("Authorization", "Bearer " + gdgLead.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.currentlyActive").value(false));

        // Immediately after revocation, access is rejected (403 Forbidden)
        mockMvc.perform(get("/api/v1/teams/stats")
                        .header("Authorization", "Bearer " + teamMember.token()))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // FOCUS 5: Terminated Member Accessing Operational Endpoints
    // =========================================================================

    @Test
    @DisplayName("Focus 5: Terminated member in 10-day window is blocked (403) from all operational endpoints, but retains profile and letter download")
    void testFocus5_TerminatedMemberOperationalEndpointsBlocked() throws Exception {
        RegisteredUser hrLead = registerUser("hr.lead.f5@gdgoc.edu", "HR Lead", Set.of(RoleName.HR_LEAD));
        RegisteredUser gdgLead = registerUser("gdg.lead.f5@gdgoc.edu", "GDG Lead", Set.of(RoleName.GDG_LEAD));
        RegisteredUser victim = registerUser("victim.term.f5@gdgoc.edu", "Terminated Victim", Set.of(RoleName.HR_MEMBER));

        // Create an Alpha team and Beta team
        Team team1 = teamRepository.save(Team.builder().name("Alpha F5").slug("alpha-f5").status(TeamStatus.ACTIVE).build());
        teamMembershipRepository.save(TeamMembership.builder()
                .teamId(team1.getId())
                .memberId(victim.id())
                .teamRole(TeamRole.MEMBER)
                .active(true)
                .joinedAt(Instant.now())
                .build());

        // Grant victim active permission prior to termination
        CreatePermissionGrantRequestDTO preGrant = new CreatePermissionGrantRequestDTO(
                victim.id(), null, PermissionCode.VIEW_TEAM_STATS, null, null, null,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Pre-termination grant"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(preGrant)))
                .andExpect(status().isCreated());

        // Verify victim had access before termination
        mockMvc.perform(get("/api/v1/teams/stats")
                        .header("Authorization", "Bearer " + victim.token()))
                .andExpect(status().isOk());

        // Terminate victim with terminatedAt = NOW - 2 days (within 10-day window)
        Instant twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS);
        mockMvc.perform(post("/api/v1/members/" + victim.id() + "/terminate")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TerminateMemberRequestDTO("Terminated for cause", twoDaysAgo))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"));

        // Operational Endpoint 1: GET /api/v1/teams -> 403 Forbidden
        mockMvc.perform(get("/api/v1/teams")
                        .header("Authorization", "Bearer " + victim.token()))
                .andExpect(status().isForbidden());

        // Operational Endpoint 2: GET /api/v1/teams/stats -> 403 Forbidden
        mockMvc.perform(get("/api/v1/teams/stats")
                        .header("Authorization", "Bearer " + victim.token()))
                .andExpect(status().isForbidden());

        // Operational Endpoint 3: GET /api/v1/teams/past-members -> 403 Forbidden
        mockMvc.perform(get("/api/v1/teams/past-members")
                        .header("Authorization", "Bearer " + victim.token()))
                .andExpect(status().isForbidden());

        // Operational Endpoint 4: POST /api/v1/teams/consensus-requests -> 403 Forbidden
        mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + victim.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"CREATE_TEAM\",\"proposedName\":\"Malicious Team\"}"))
                .andExpect(status().isForbidden());

        // Operational Endpoint 5: GET /api/v1/members -> 403 Forbidden
        mockMvc.perform(get("/api/v1/members")
                        .header("Authorization", "Bearer " + victim.token()))
                .andExpect(status().isForbidden());

        // Operational Endpoint 6: POST /api/v1/permissions/grants -> 403 Forbidden
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + victim.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(preGrant)))
                .andExpect(status().isForbidden());

        // Operational Endpoint 7: GET /api/v1/permissions/grants/my -> 403 Forbidden
        mockMvc.perform(get("/api/v1/permissions/grants/my")
                        .header("Authorization", "Bearer " + victim.token()))
                .andExpect(status().isForbidden());

        // Operational Endpoint 8: GET /api/v1/collaborations -> 403 Forbidden
        mockMvc.perform(get("/api/v1/collaborations")
                        .header("Authorization", "Bearer " + victim.token()))
                .andExpect(status().isForbidden());

        // Operational Endpoint 9: POST /api/v1/profile/me/bio-data-requests -> 403 Forbidden
        mockMvc.perform(post("/api/v1/profile/me/bio-data-requests")
                        .header("Authorization", "Bearer " + victim.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Attempted Name Change\"}"))
                .andExpect(status().isForbidden());

        // Operational Endpoint 10: POST /api/v1/profile/me/team-change-requests -> 403 Forbidden
        mockMvc.perform(post("/api/v1/profile/me/team-change-requests")
                        .header("Authorization", "Bearer " + victim.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentTeamId\":1,\"targetTeamId\":2,\"reason\":\"Transfer me\"}"))
                .andExpect(status().isForbidden());

        // Operational Endpoint 11: POST /api/v1/letters/issue -> 403 Forbidden
        mockMvc.perform(post("/api/v1/letters/issue")
                        .header("Authorization", "Bearer " + victim.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new IssueLetterRequestDTO(
                                victim.id(), LetterType.APPRECIATION, "Self appreciation", "<p>hi</p>"))))
                .andExpect(status().isForbidden());

        // Allowed Endpoints during 10-day window:
        // - GET /api/v1/profile/me (200 OK)
        mockMvc.perform(get("/api/v1/profile/me")
                        .header("Authorization", "Bearer " + victim.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"))
                .andExpect(jsonPath("$.letterDownloadWindowActive").value(true));

        // - GET /api/v1/profile/me/letters (200 OK, returns generated termination letter)
        MvcResult lettersRes = mockMvc.perform(get("/api/v1/profile/me/letters")
                        .header("Authorization", "Bearer " + victim.token()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode lettersArr = objectMapper.readTree(lettersRes.getResponse().getContentAsString());
        assertThat(lettersArr.size()).isGreaterThanOrEqualTo(1);
        long termLetterId = lettersArr.get(0).get("id").asLong();

        // - GET /api/v1/profile/me/letters/{id}/download (200 OK, application/pdf)
        MvcResult pdfRes = mockMvc.perform(get("/api/v1/profile/me/letters/" + termLetterId + "/download")
                        .header("Authorization", "Bearer " + victim.token()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();
        assertThat(new String(pdfRes.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");

        // - GET /api/v1/profile/me/letters/{id}/signed-url (200 OK)
        MvcResult signedRes = mockMvc.perform(get("/api/v1/profile/me/letters/" + termLetterId + "/signed-url")
                        .header("Authorization", "Bearer " + victim.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
        String signedToken = objectMapper.readTree(signedRes.getResponse().getContentAsString()).get("token").asText();

        // - GET /api/v1/letters/signed-download/{token} (200 OK, application/pdf)
        MvcResult signedPdf = mockMvc.perform(get("/api/v1/letters/signed-download/" + signedToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();
        assertThat(new String(signedPdf.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");
    }

    // =========================================================================
    // FOCUS 6: Terminated Member Accessing Letters After 10-Day Window Expiration
    // =========================================================================

    @Test
    @DisplayName("Focus 6: Letters view/download and signed URL downloads rejected with 403 after 10-day expiration, plus signed token tamper tests")
    void testFocus6_ExpiredTerminationLetterWindowAndSignedTokenTampering() throws Exception {
        RegisteredUser hrLead = registerUser("hr.lead.f6@gdgoc.edu", "HR Lead", Set.of(RoleName.HR_LEAD));
        RegisteredUser expiredVictim = registerUser("expired.term.f6@gdgoc.edu", "Expired Victim", Set.of(RoleName.TEAM_MEMBER));
        RegisteredUser otherActiveUser = registerUser("other.active.f6@gdgoc.edu", "Other Active User", Set.of(RoleName.TEAM_MEMBER));

        // Terminate expiredVictim with terminatedAt = NOW - 12 days (10-day window expired 2 days ago)
        Instant twelveDaysAgo = Instant.now().minus(12, ChronoUnit.DAYS);
        mockMvc.perform(post("/api/v1/members/" + expiredVictim.id() + "/terminate")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TerminateMemberRequestDTO("Expired tenure", twelveDaysAgo))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"));

        // 6.1 Profile shows letterDownloadWindowActive == false
        mockMvc.perform(get("/api/v1/profile/me")
                        .header("Authorization", "Bearer " + expiredVictim.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"))
                .andExpect(jsonPath("$.letterDownloadWindowActive").value(false));

        // 6.2 GET /api/v1/profile/me/letters is rejected (403 Forbidden)
        mockMvc.perform(get("/api/v1/profile/me/letters")
                        .header("Authorization", "Bearer " + expiredVictim.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("10-day post-termination window")));

        // Find the generated termination letter ID from DB
        var letters = memberLetterRepository.findByMemberIdOrderByIssuedAtDesc(expiredVictim.id());
        assertThat(letters).isNotEmpty();
        Long termLetterId = letters.get(0).getId();

        // 6.3 Direct letter download is rejected (403 Forbidden)
        mockMvc.perform(get("/api/v1/profile/me/letters/" + termLetterId + "/download")
                        .header("Authorization", "Bearer " + expiredVictim.token()))
                .andExpect(status().isForbidden());

        // 6.4 Generating signed URL is rejected (403 Forbidden)
        mockMvc.perform(get("/api/v1/profile/me/letters/" + termLetterId + "/signed-url")
                        .header("Authorization", "Bearer " + expiredVictim.token()))
                .andExpect(status().isForbidden());

        // 6.5 Signed Token probe: If an attacker crafts or reuses a signed token whose expiresAt is expired
        String expiredToken = signedUrlService.generateSignedToken(termLetterId, expiredVictim.id(), Instant.now().minusSeconds(60));
        mockMvc.perform(get("/api/v1/letters/signed-download/" + expiredToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("expired")));

        // 6.6 Tampered signed token probe: Modify signature part
        String validSignedToken = signedUrlService.generateSignedToken(termLetterId, otherActiveUser.id(), Instant.now().plusSeconds(3600));
        String tamperedSignedToken = validSignedToken.substring(0, validSignedToken.length() - 5) + "XXXXX";
        mockMvc.perform(get("/api/v1/letters/signed-download/" + tamperedSignedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid HMAC signature")));

        // 6.7 Malformed signed token probe: No dot separator
        mockMvc.perform(get("/api/v1/letters/signed-download/MALFORMED_TOKEN_WITHOUT_DOT"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid signed download token")));

        // 6.8 Cross-member letter snooping: otherActiveUser cannot download someone else's letter (403 Forbidden)
        mockMvc.perform(get("/api/v1/profile/me/letters/" + termLetterId + "/download")
                        .header("Authorization", "Bearer " + otherActiveUser.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Not authorized to download this letter")));

        // 6.9 Cross-member signed URL generation: otherActiveUser cannot generate signed URL for someone else's letter (403 Forbidden)
        mockMvc.perform(get("/api/v1/profile/me/letters/" + termLetterId + "/signed-url")
                        .header("Authorization", "Bearer " + otherActiveUser.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Not authorized to generate a signed download URL")));
    }
}
