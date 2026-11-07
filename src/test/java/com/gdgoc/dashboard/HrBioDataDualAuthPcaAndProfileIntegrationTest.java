package com.gdgoc.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdgoc.dashboard.agreement.AgreementDtos.*;
import com.gdgoc.dashboard.auth.AuthDtos.RegisterRequestDTO;
import com.gdgoc.dashboard.letter.LetterDtos.IssueLetterRequestDTO;
import com.gdgoc.dashboard.letter.LetterType;
import com.gdgoc.dashboard.member.MemberDtos.*;
import com.gdgoc.dashboard.member.RoleName;
import com.gdgoc.dashboard.request.RequestDtos.*;
import com.gdgoc.dashboard.team.ConsensusActionType;
import com.gdgoc.dashboard.team.TeamDtos.AssignTeamMemberRequestDTO;
import com.gdgoc.dashboard.team.TeamDtos.ConsensusVoteRequestDTO;
import com.gdgoc.dashboard.team.TeamDtos.CreateConsensusRequestDTO;
import com.gdgoc.dashboard.team.TeamRole;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class HrBioDataDualAuthPcaAndProfileIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record RegisteredUser(Long id, String token, String email) {}

    private RegisteredUser registerUser(String email, String fullName, Set<RoleName> roles) throws Exception {
        RegisterRequestDTO req = new RegisterRequestDTO(
                email, "Password123!", fullName, "22CS200", "CSE", 3, "+91-9222222222", roles
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

    private Long createTeam(
            String name,
            RegisteredUser lead1,
            RegisteredUser lead2,
            RegisteredUser lead3
    ) throws Exception {
        CreateConsensusRequestDTO req = new CreateConsensusRequestDTO(
                ConsensusActionType.CREATE_TEAM, null, name, "Desc", null
        );
        MvcResult r1 = mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        long cId = objectMapper.readTree(r1.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + cId + "/vote")
                        .header("Authorization", "Bearer " + lead2.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "v2"))))
                .andExpect(status().isOk());

        MvcResult r3 = mockMvc.perform(post("/api/v1/teams/consensus-requests/" + cId + "/vote")
                        .header("Authorization", "Bearer " + lead3.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "v3"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(r3.getResponse().getContentAsString()).get("executedTeamId").asLong();
    }

    @Test
    void testF7_F11_HrBioDataCrudAndDualAuthorizationStateMachine() throws Exception {
        RegisteredUser gdgLead = registerUser("gdg1.f7@gdgoc.edu", "GDG Lead 1", Set.of(RoleName.GDG_LEAD));
        RegisteredUser hrLead = registerUser("hrlead.f7@gdgoc.edu", "HR Lead", Set.of(RoleName.HR_LEAD));
        RegisteredUser teamLead = registerUser("teamlead.f7@gdgoc.edu", "Team Lead", Set.of(RoleName.TEAM_LEAD));
        RegisteredUser hrMember = registerUser("hrmember.f7@gdgoc.edu", "HR Member", Set.of(RoleName.HR_MEMBER));
        RegisteredUser regularMember = registerUser("regular.f7@gdgoc.edu", "Regular Member", Set.of(RoleName.TEAM_MEMBER));

        // 1. Direct execution by HR_LEAD on POST /api/v1/members (201 Created, EXECUTED)
        HrAddMemberRequestDTO directAdd = new HrAddMemberRequestDTO(
                "direct.added@gdgoc.edu", "Password123!", "Direct Added Member",
                "22CS301", "IT", 3, "+91-9000012345", "Cloud dev", "Java, GCP",
                "https://github.com/direct", "https://linkedin.com/in/direct",
                Set.of(RoleName.TEAM_MEMBER), null, null, "Direct hire"
        );
        MvcResult directRes = mockMvc.perform(post("/api/v1/members")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(directAdd)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.executionStatus").value("EXECUTED"))
                .andExpect(jsonPath("$.member.email").value("direct.added@gdgoc.edu"))
                .andReturn();
        long directMemberId = objectMapper.readTree(directRes.getResponse().getContentAsString()).get("member").get("id").asLong();

        // 2. Staged execution by HR_MEMBER on PUT /api/v1/members/{id}/bio-data (202 Accepted, PENDING_AUTHORIZATION)
        BioDataUpdateRequestDTO stagedBioUpdate = new BioDataUpdateRequestDTO(
                "Direct Added Updated Name", "22CS301", "CSE-AI", 4, "+91-9999999999",
                "Updated Bio via HR Member", "Java, Spring, Kubernetes", null, null, "Promotion update"
        );
        MvcResult stagedRes = mockMvc.perform(put("/api/v1/members/" + directMemberId + "/bio-data")
                        .header("Authorization", "Bearer " + hrMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(stagedBioUpdate)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionStatus").value("PENDING_AUTHORIZATION"))
                .andExpect(jsonPath("$.changeRequestId").isNumber())
                .andReturn();
        long changeReqId = objectMapper.readTree(stagedRes.getResponse().getContentAsString()).get("changeRequestId").asLong();

        // Verify member name is NOT updated yet before dual authorization
        mockMvc.perform(get("/api/v1/members/" + directMemberId)
                        .header("Authorization", "Bearer " + hrMember.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Direct Added Member"));

        // 3. Only HR_LEAD approves -> status is PARTIALLY_APPROVED, member still NOT updated
        mockMvc.perform(post("/api/v1/change-requests/" + changeReqId + "/approve")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveChangeRequestDTO(true, "HR Lead approves"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_APPROVED"))
                .andExpect(jsonPath("$.hrLeadApproved").value(true))
                .andExpect(jsonPath("$.teamLeadApproved").value(false));

        mockMvc.perform(get("/api/v1/members/" + directMemberId)
                        .header("Authorization", "Bearer " + hrMember.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Direct Added Member"));

        // 4. Now TEAM_LEAD also approves -> both hrLeadApproved & teamLeadApproved are true -> EXECUTED!
        mockMvc.perform(post("/api/v1/change-requests/" + changeReqId + "/approve")
                        .header("Authorization", "Bearer " + teamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveChangeRequestDTO(true, "Team Lead approves"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.hrLeadApproved").value(true))
                .andExpect(jsonPath("$.teamLeadApproved").value(true));

        // Verify member bio-data is now mutated in the database!
        mockMvc.perform(get("/api/v1/members/" + directMemberId)
                        .header("Authorization", "Bearer " + hrMember.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Direct Added Updated Name"))
                .andExpect(jsonPath("$.department").value("CSE-AI"))
                .andExpect(jsonPath("$.academicYear").value(4));

        // 5. F11: Regular TEAM_MEMBER without grant is forbidden from viewing member details (403 Forbidden)
        mockMvc.perform(get("/api/v1/members/" + directMemberId)
                        .header("Authorization", "Bearer " + regularMember.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void testF13_ProjectContributorAgreementsAndPdfSigning() throws Exception {
        RegisteredUser hrLead = registerUser("hrlead.f13@gdgoc.edu", "HR Lead", Set.of(RoleName.HR_LEAD));
        RegisteredUser teamLead = registerUser("tl.f13@gdgoc.edu", "Team Lead", Set.of(RoleName.TEAM_LEAD));
        RegisteredUser memberA = registerUser("memA.f13@gdgoc.edu", "Member A", Set.of(RoleName.TEAM_MEMBER));
        RegisteredUser memberB = registerUser("memB.f13@gdgoc.edu", "Member B", Set.of(RoleName.TEAM_MEMBER));

        CreateAgreementRequestDTO createPca = new CreateAgreementRequestDTO(
                "2026.1",
                "GDGoC 2026-27 Project Contributor Agreement",
                "<p>I agree to contribute under the Apache 2.0 license and uphold the GDGoC Code of Conduct.</p>"
        );

        // 1. TEAM_LEAD cannot create PCA (403 Forbidden — Permitted Access Sharing: None)
        mockMvc.perform(post("/api/v1/agreements")
                        .header("Authorization", "Bearer " + teamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createPca)))
                .andExpect(status().isForbidden());

        // 2. HR_LEAD creates PCA template (201 Created)
        MvcResult pcaRes = mockMvc.perform(post("/api/v1/agreements")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createPca)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("2026.1"))
                .andReturn();
        long agreementId = objectMapper.readTree(pcaRes.getResponse().getContentAsString()).get("id").asLong();

        // 3. HR_LEAD issues PCA to Member A
        MvcResult issueRes = mockMvc.perform(post("/api/v1/agreements/" + agreementId + "/issue")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new IssueAgreementRequestDTO(List.of(memberA.id())))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andReturn();
        long signatureId = objectMapper.readTree(issueRes.getResponse().getContentAsString()).get(0).get("id").asLong();

        // 4. Member B cannot sign Member A's agreement (403 Forbidden)
        mockMvc.perform(post("/api/v1/agreements/signatures/" + signatureId + "/sign")
                        .header("Authorization", "Bearer " + memberB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SignAgreementRequestDTO("Signed by Member B"))))
                .andExpect(status().isForbidden());

        // 5. Member A signs their PCA -> renders PDF and creates CONTRIBUTOR_AGREEMENT letter
        mockMvc.perform(post("/api/v1/agreements/signatures/" + signatureId + "/sign")
                        .header("Authorization", "Bearer " + memberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SignAgreementRequestDTO("Digitally Signed by Member A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"))
                .andExpect(jsonPath("$.pdfStorageKey").isNotEmpty());

        // 6. Download signed PCA PDF and verify %PDF-1.x header
        MvcResult pdfRes = mockMvc.perform(get("/api/v1/agreements/signatures/" + signatureId + "/download")
                        .header("Authorization", "Bearer " + memberA.token()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();
        String pdfHeader = new String(pdfRes.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII);
        assertThat(pdfHeader).isEqualTo("%PDF-");
    }

    @Test
    void testF20_F21_F22_ProfileSignedUrlAndSelfServiceBioDataAndTeamChangeRequests() throws Exception {
        RegisteredUser lead1 = registerUser("lead1.f20@gdgoc.edu", "Lead 1", Set.of(RoleName.GDG_LEAD));
        RegisteredUser lead2 = registerUser("lead2.f20@gdgoc.edu", "Lead 2", Set.of(RoleName.GDG_LEAD));
        RegisteredUser lead3 = registerUser("lead3.f20@gdgoc.edu", "Lead 3", Set.of(RoleName.GDG_LEAD));
        RegisteredUser hrLead = registerUser("hrlead.f20@gdgoc.edu", "HR Lead", Set.of(RoleName.HR_LEAD));
        RegisteredUser teamLead = registerUser("tl.f20@gdgoc.edu", "Team Lead", Set.of(RoleName.TEAM_LEAD));
        RegisteredUser member = registerUser("member.f20@gdgoc.edu", "Active Member", Set.of(RoleName.TEAM_MEMBER));

        Long team1Id = createTeam("Backend Team", lead1, lead2, lead3);
        Long team2Id = createTeam("DevOps Team", lead1, lead2, lead3);

        mockMvc.perform(post("/api/v1/teams/" + team1Id + "/members")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignTeamMemberRequestDTO(member.id(), TeamRole.MEMBER))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/teams/" + team2Id + "/members")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignTeamMemberRequestDTO(teamLead.id(), TeamRole.TEAM_LEAD))))
                .andExpect(status().isCreated());

        // 1. F20: Issue official Appointment letter, generate HMAC pre-signed URL, and download via token
        MvcResult letterRes = mockMvc.perform(post("/api/v1/letters/issue")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new IssueLetterRequestDTO(
                                member.id(), LetterType.APPOINTMENT, "Appointment Letter", "<p>Welcome to Backend Team!</p>"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        long letterId = objectMapper.readTree(letterRes.getResponse().getContentAsString()).get("id").asLong();

        MvcResult signedUrlRes = mockMvc.perform(get("/api/v1/profile/me/letters/" + letterId + "/signed-url")
                        .header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signedUrl").isNotEmpty())
                .andReturn();
        String signedUrl = objectMapper.readTree(signedUrlRes.getResponse().getContentAsString()).get("signedUrl").asText();

        // Public signed URL download succeeds and returns %PDF-1.x
        MvcResult signedDl = mockMvc.perform(get(signedUrl))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();
        assertThat(new String(signedDl.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        // Tampered signed URL returns 403 Forbidden
        mockMvc.perform(get(signedUrl + "tampered"))
                .andExpect(status().isForbidden());

        // 2. F22: Team change request with blank reason or same team is rejected with 400 Bad Request
        mockMvc.perform(post("/api/v1/profile/me/team-change-requests")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentTeamId\":" + team1Id + ",\"targetTeamId\":" + team2Id + ",\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/profile/me/team-change-requests")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TeamChangeRequestDTO(team1Id, team1Id, "Same team invalid")
                        )))
                .andExpect(status().isBadRequest());

        // 3. F22: Valid team change request + HR_LEAD and TEAM_LEAD dual approval transfers member and records past team
        MvcResult tcRes = mockMvc.perform(post("/api/v1/profile/me/team-change-requests")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TeamChangeRequestDTO(team1Id, team2Id, "Interested in CI/CD and Kubernetes")
                        )))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_AUTHORIZATION"))
                .andReturn();
        long tcReqId = objectMapper.readTree(tcRes.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/v1/change-requests/" + tcReqId + "/approve")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveChangeRequestDTO(true, "HR approves transfer"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_APPROVED"));

        mockMvc.perform(post("/api/v1/change-requests/" + tcReqId + "/approve")
                        .header("Authorization", "Bearer " + teamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveChangeRequestDTO(true, "DevOps Team Lead approves"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        // Verify member profile now shows Team 2 as activeTeam and Team 1 as pastTeam with exitReason == TEAM_TRANSFER
        mockMvc.perform(get("/api/v1/profile/me")
                        .header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeTeams[0].teamId").value(team2Id))
                .andExpect(jsonPath("$.pastTeams[0].teamId").value(team1Id))
                .andExpect(jsonPath("$.pastTeams[0].exitReason").value("TEAM_TRANSFER"));
    }
}
