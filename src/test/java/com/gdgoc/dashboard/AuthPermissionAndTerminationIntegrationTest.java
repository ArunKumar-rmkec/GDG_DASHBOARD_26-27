package com.gdgoc.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdgoc.dashboard.auth.AuthDtos.*;
import com.gdgoc.dashboard.letter.LetterDtos.IssueLetterRequestDTO;
import com.gdgoc.dashboard.letter.LetterType;
import com.gdgoc.dashboard.member.MemberDtos.TerminateMemberRequestDTO;
import com.gdgoc.dashboard.member.RoleName;
import com.gdgoc.dashboard.permission.PermissionCode;
import com.gdgoc.dashboard.permission.PermissionDtos.ApproveGrantRequestDTO;
import com.gdgoc.dashboard.permission.PermissionDtos.CreatePermissionGrantRequestDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthPermissionAndTerminationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.gdgoc.dashboard.team.TeamRepository teamRepository;

    @Autowired
    private com.gdgoc.dashboard.team.TeamMembershipRepository teamMembershipRepository;

    private record RegisteredUser(Long id, String token, String email) {}

    private RegisteredUser registerUser(String email, String fullName, Set<RoleName> roles) throws Exception {
        RegisterRequestDTO req = new RegisterRequestDTO(
                email, "Password123!", fullName, "22CS001", "CSE", 3, "+91-9000000001", roles
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

    @Test
    void testF1_JwtAuthAndGoogleOAuth2Lifecycle() throws Exception {
        // 1. Register new user
        RegisteredUser user = registerUser("f1.user@gdgoc.edu", "F1 User", Set.of(RoleName.TEAM_MEMBER));
        assertThat(user.token()).isNotBlank();

        // 2. Duplicate registration returns 409 Conflict
        RegisterRequestDTO dupReq = new RegisterRequestDTO(
                "f1.user@gdgoc.edu", "Password123!", "Dup User", null, null, null, null, null
        );
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dupReq)))
                .andExpect(status().isConflict());

        // 3. Login with valid credentials returns 200 OK
        LoginRequestDTO loginReq = new LoginRequestDTO("f1.user@gdgoc.edu", "Password123!");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.member.email").value("f1.user@gdgoc.edu"));

        // 4. Login with wrong password returns 401 Unauthorized
        LoginRequestDTO badLogin = new LoginRequestDTO("f1.user@gdgoc.edu", "WrongPass!");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badLogin)))
                .andExpect(status().isUnauthorized());

        // 5. Google OAuth2 login auto-registers and returns JWT
        GoogleAuthRequestDTO googleReq = new GoogleAuthRequestDTO(
                "eyJhbGciOiJSUzI1NiJ9.validGoogleTokenPayload.sig",
                "google.member@gdgoc.edu",
                "Google Member",
                "google-sub-12345"
        );
        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(googleReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.member.email").value("google.member@gdgoc.edu"));

        // 6. Invalid Google OAuth2 token returns 401 Unauthorized
        GoogleAuthRequestDTO badGoogle = new GoogleAuthRequestDTO(
                "INVALID_GOOGLE_TOKEN", "bad@gdgoc.edu", "Bad", "sub"
        );
        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badGoogle)))
                .andExpect(status().isUnauthorized());

        // 7. /api/v1/auth/me requires authentication
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.id()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void testF2_F3_F4_PermissionGrantsScheduledWindowsAndNonShareableRejection() throws Exception {
        RegisteredUser gdgLead = registerUser("gdglead.f2@gdgoc.edu", "GDG Lead", Set.of(RoleName.GDG_LEAD));
        RegisteredUser teamLead = registerUser("teamlead.f3@gdgoc.edu", "Team Lead", Set.of(RoleName.TEAM_LEAD));
        RegisteredUser regularMember = registerUser("member.f4@gdgoc.edu", "Regular Member", Set.of(RoleName.TEAM_MEMBER));
        RegisteredUser hrMember = registerUser("hrmember.f4@gdgoc.edu", "HR Member", Set.of(RoleName.HR_MEMBER));

        // 1. Non-lead cannot grant permissions (403 Forbidden)
        CreatePermissionGrantRequestDTO memberAttempt = new CreatePermissionGrantRequestDTO(
                hrMember.id(), null, PermissionCode.VIEW_TEAM_STATS, null, null, null,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Unauthorized attempt"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + regularMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberAttempt)))
                .andExpect(status().isForbidden());

        // 2. All 5 Non-Shareable permissions must be rejected with 400 Bad Request
        for (PermissionCode nonShareable : new PermissionCode[]{
                PermissionCode.SUPER_ADMIN,
                PermissionCode.GRANT_PERMISSIONS,
                PermissionCode.TEAM_LIFECYCLE_CONSENSUS,
                PermissionCode.PROJECT_CONTRIBUTOR_AGREEMENT,
                PermissionCode.ASSIGN_TASK_TO_TEAM_LEAD
        }) {
            CreatePermissionGrantRequestDTO nonShareReq = new CreatePermissionGrantRequestDTO(
                    regularMember.id(), null, nonShareable, null, null, null,
                    Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Try share non-shareable"
            );
            mockMvc.perform(post("/api/v1/permissions/grants")
                            .header("Authorization", "Bearer " + gdgLead.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(nonShareReq)))
                    .andExpect(status().isBadRequest());
        }

        // 3. Target role restriction: VIEW_MEMBER_DETAILS can only be shared with HR_MEMBER, not regular TEAM_MEMBER
        CreatePermissionGrantRequestDTO wrongRoleReq = new CreatePermissionGrantRequestDTO(
                regularMember.id(), null, PermissionCode.VIEW_MEMBER_DETAILS, null, null, null,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Wrong target role"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongRoleReq)))
                .andExpect(status().isBadRequest());

        // 4. Before grant, regularMember gets 403 on /api/v1/teams/stats
        mockMvc.perform(get("/api/v1/teams/stats")
                        .header("Authorization", "Bearer " + regularMember.token()))
                .andExpect(status().isForbidden());

        // 5. Future-scheduled grant (validFrom = NOW + 2 hours) does NOT activate yet
        CreatePermissionGrantRequestDTO futureGrant = new CreatePermissionGrantRequestDTO(
                regularMember.id(), null, PermissionCode.VIEW_TEAM_STATS, null, null, null,
                Instant.now().plus(2, ChronoUnit.HOURS), Instant.now().plus(4, ChronoUnit.HOURS), "Future grant"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + teamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(futureGrant)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentlyActive").value(false));

        mockMvc.perform(get("/api/v1/teams/stats")
                        .header("Authorization", "Bearer " + regularMember.token()))
                .andExpect(status().isForbidden());

        // 6. Active grant (validFrom = NOW - 5m, validUntil = NOW + 1h) immediately grants access
        CreatePermissionGrantRequestDTO activeGrantReq = new CreatePermissionGrantRequestDTO(
                regularMember.id(), null, PermissionCode.VIEW_TEAM_STATS, null, null, null,
                Instant.now().minusSeconds(300), Instant.now().plusSeconds(3600), "Active grant"
        );
        MvcResult grantRes = mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + teamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(activeGrantReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentlyActive").value(true))
                .andReturn();
        long activeGrantId = objectMapper.readTree(grantRes.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/v1/teams/stats")
                        .header("Authorization", "Bearer " + regularMember.token()))
                .andExpect(status().isOk());

        // 7. Revoke grant -> access is immediately revoked (403 Forbidden)
        mockMvc.perform(delete("/api/v1/permissions/grants/" + activeGrantId)
                        .header("Authorization", "Bearer " + teamLead.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.status").value("REVOKED"));

        mockMvc.perform(get("/api/v1/teams/stats")
                        .header("Authorization", "Bearer " + regularMember.token()))
                .andExpect(status().isForbidden());

        // 8. Cross-team permission grant by TEAM_LEAD requires target TEAM_LEAD approval
        com.gdgoc.dashboard.team.Team team1 = teamRepository.save(com.gdgoc.dashboard.team.Team.builder()
                .name("Alpha Team")
                .slug("alpha-team")
                .status(com.gdgoc.dashboard.team.TeamStatus.ACTIVE)
                .build());
        com.gdgoc.dashboard.team.Team team2 = teamRepository.save(com.gdgoc.dashboard.team.Team.builder()
                .name("Beta Team")
                .slug("beta-team")
                .status(com.gdgoc.dashboard.team.TeamStatus.ACTIVE)
                .build());

        teamMembershipRepository.save(com.gdgoc.dashboard.team.TeamMembership.builder()
                .teamId(team1.getId())
                .memberId(teamLead.id())
                .teamRole(com.gdgoc.dashboard.team.TeamRole.TEAM_LEAD)
                .active(true)
                .joinedAt(Instant.now())
                .build());

        CreatePermissionGrantRequestDTO crossTeamGrant = new CreatePermissionGrantRequestDTO(
                regularMember.id(), null, PermissionCode.ASSIGN_TASK_CROSS_TEAM, team1.getId(), null, team2.getId(),
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Cross team grant"
        );
        MvcResult ctRes = mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + teamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crossTeamGrant)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_TEAM_LEAD_APPROVAL"))
                .andReturn();
        long ctGrantId = objectMapper.readTree(ctRes.getResponse().getContentAsString()).get("id").asLong();

        // Approve cross-team grant by GDG_LEAD
        mockMvc.perform(post("/api/v1/permissions/grants/" + ctGrantId + "/approve")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveGrantRequestDTO(true, "Approved"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void testF5_TenDayPostTerminationWindowActiveAndExpired() throws Exception {
        RegisteredUser hrLead = registerUser("hrlead.f5@gdgoc.edu", "HR Lead", Set.of(RoleName.HR_LEAD));
        RegisteredUser memberRecent = registerUser("term.recent@gdgoc.edu", "Recent Terminated", Set.of(RoleName.HR_MEMBER));
        RegisteredUser memberExpired = registerUser("term.expired@gdgoc.edu", "Expired Terminated", Set.of(RoleName.HR_MEMBER));

        // Issue an Appreciation letter to both members before termination
        mockMvc.perform(post("/api/v1/letters/issue")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new IssueLetterRequestDTO(
                                memberRecent.id(), LetterType.APPRECIATION, "Appreciation Letter", "<p>Great work!</p>"
                        ))))
                .andExpect(status().isCreated());

        MvcResult expLetterRes = mockMvc.perform(post("/api/v1/letters/issue")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new IssueLetterRequestDTO(
                                memberExpired.id(), LetterType.APPRECIATION, "Old Appreciation Letter", "<p>Good job!</p>"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        long expiredMemberLetterId = objectMapper.readTree(expLetterRes.getResponse().getContentAsString()).get("id").asLong();

        // Obtain a signed URL for memberExpired BEFORE termination expires
        // Now terminate memberRecent with terminatedAt = NOW - 2 days (within 10-day window)
        Instant twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS);
        mockMvc.perform(post("/api/v1/members/" + memberRecent.id() + "/terminate")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TerminateMemberRequestDTO("End of tenure", twoDaysAgo))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"));

        // Verify memberRecent (within 10-day window):
        // - CAN access /api/v1/profile/me and /api/v1/profile/me/letters and download PDF
        mockMvc.perform(get("/api/v1/profile/me")
                        .header("Authorization", "Bearer " + memberRecent.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"))
                .andExpect(jsonPath("$.letterDownloadWindowActive").value(true));

        MvcResult lettersRes = mockMvc.perform(get("/api/v1/profile/me/letters")
                        .header("Authorization", "Bearer " + memberRecent.token()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode lettersArray = objectMapper.readTree(lettersRes.getResponse().getContentAsString());
        assertThat(lettersArray.size()).isGreaterThanOrEqualTo(2); // Appreciation + auto-generated Termination letter
        long recentLetterId = lettersArray.get(0).get("id").asLong();

        MvcResult pdfRes = mockMvc.perform(get("/api/v1/profile/me/letters/" + recentLetterId + "/download")
                        .header("Authorization", "Bearer " + memberRecent.token()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();
        String pdfHeader = new String(pdfRes.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII);
        assertThat(pdfHeader).isEqualTo("%PDF-");

        // - CANNOT access operational endpoints or submit change requests (403 Forbidden)
        mockMvc.perform(get("/api/v1/teams")
                        .header("Authorization", "Bearer " + memberRecent.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/members")
                        .header("Authorization", "Bearer " + memberRecent.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/profile/me/bio-data-requests")
                        .header("Authorization", "Bearer " + memberRecent.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bio\":\"Trying to edit bio after termination\"}"))
                .andExpect(status().isForbidden());

        // Now terminate memberExpired with terminatedAt = NOW - 11 days (10-day window EXPIRED!)
        Instant elevenDaysAgo = Instant.now().minus(11, ChronoUnit.DAYS);
        mockMvc.perform(post("/api/v1/members/" + memberExpired.id() + "/terminate")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TerminateMemberRequestDTO("Expired tenure", elevenDaysAgo))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"));

        // Verify memberExpired (after 10 days):
        // - CAN still view /api/v1/profile/me, which shows letterDownloadWindowActive = false
        mockMvc.perform(get("/api/v1/profile/me")
                        .header("Authorization", "Bearer " + memberExpired.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.letterDownloadWindowActive").value(false));

        // - CANNOT list or download letters or get signed URLs (403 Forbidden)
        mockMvc.perform(get("/api/v1/profile/me/letters")
                        .header("Authorization", "Bearer " + memberExpired.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/profile/me/letters/" + expiredMemberLetterId + "/download")
                        .header("Authorization", "Bearer " + memberExpired.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/profile/me/letters/" + expiredMemberLetterId + "/signed-url")
                        .header("Authorization", "Bearer " + memberExpired.token()))
                .andExpect(status().isForbidden());
    }
}
