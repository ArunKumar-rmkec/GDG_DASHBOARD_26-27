package com.gdgoc.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdgoc.dashboard.auth.AuthDtos.RegisterRequestDTO;
import com.gdgoc.dashboard.collaboration.CollaborationDtos.ApproveCollaborationTeamRequestDTO;
import com.gdgoc.dashboard.collaboration.CollaborationDtos.CreateCollaborationRequestDTO;
import com.gdgoc.dashboard.collaboration.CollaborationType;
import com.gdgoc.dashboard.member.RoleName;
import com.gdgoc.dashboard.team.ConsensusActionType;
import com.gdgoc.dashboard.team.TeamDtos.*;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TeamConsensusStatsAndCollaborationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record RegisteredUser(Long id, String token, String email) {}

    private RegisteredUser registerUser(String email, String fullName, Set<RoleName> roles) throws Exception {
        RegisterRequestDTO req = new RegisterRequestDTO(
                email, "Password123!", fullName, "22CS100", "CSE", 3, "+91-9111111111", roles
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

    private Long createTeamVia3LeadConsensus(
            String teamName,
            Long parentTeamId,
            RegisteredUser lead1,
            RegisteredUser lead2,
            RegisteredUser lead3
    ) throws Exception {
        CreateConsensusRequestDTO createReq = new CreateConsensusRequestDTO(
                ConsensusActionType.CREATE_TEAM, null, teamName, teamName + " Description", parentTeamId
        );
        MvcResult initRes = mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.approvalCount").value(1))
                .andReturn();
        long reqId = objectMapper.readTree(initRes.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + lead2.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Lead 2 approves"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.approvalCount").value(2));

        MvcResult execRes = mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + lead3.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Lead 3 approves"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.approvalCount").value(3))
                .andReturn();

        return objectMapper.readTree(execRes.getResponse().getContentAsString()).get("executedTeamId").asLong();
    }

    @Test
    void testF6_ThreeGdgLeadConsensusCreateRenameDeleteAndEdgeCases() throws Exception {
        RegisteredUser lead1 = registerUser("lead1.f6@gdgoc.edu", "GDG Lead 1", Set.of(RoleName.GDG_LEAD));
        RegisteredUser lead2 = registerUser("lead2.f6@gdgoc.edu", "GDG Lead 2", Set.of(RoleName.GDG_LEAD));
        RegisteredUser lead3 = registerUser("lead3.f6@gdgoc.edu", "GDG Lead 3", Set.of(RoleName.GDG_LEAD));
        RegisteredUser teamLead = registerUser("tl.f6@gdgoc.edu", "Team Lead", Set.of(RoleName.TEAM_LEAD));
        RegisteredUser member = registerUser("mem.f6@gdgoc.edu", "Member", Set.of(RoleName.TEAM_MEMBER));

        // 1. Non-GDG_LEAD cannot initiate consensus request (403 Forbidden)
        CreateConsensusRequestDTO createReq = new CreateConsensusRequestDTO(
                ConsensusActionType.CREATE_TEAM, null, "AI/ML Team", "AI and ML division", null
        );
        mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + teamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isForbidden());

        // 2. Lead 1 initiates CREATE_TEAM (approvalCount = 1, status = PENDING)
        MvcResult initRes = mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.approvalCount").value(1))
                .andExpect(jsonPath("$.executedTeamId").isEmpty())
                .andReturn();
        long reqId = objectMapper.readTree(initRes.getResponse().getContentAsString()).get("id").asLong();

        // 3. Lead 1 attempting a duplicate vote returns 409 Conflict
        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Duplicate vote"))))
                .andExpect(status().isConflict());

        // 4. Lead 2 votes approved=true (approvalCount = 2, still PENDING, team not created yet)
        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + lead2.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "2nd approval"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.approvalCount").value(2));

        // 5. Lead 3 votes approved=true -> reaches 3 distinct GDG_LEAD approvals -> EXECUTED!
        MvcResult execRes = mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + lead3.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "3rd approval"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.approvalCount").value(3))
                .andExpect(jsonPath("$.executedTeamId").isNumber())
                .andReturn();
        long createdTeamId = objectMapper.readTree(execRes.getResponse().getContentAsString()).get("executedTeamId").asLong();

        // 6. Assign a member to the newly created team
        mockMvc.perform(post("/api/v1/teams/" + createdTeamId + "/members")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignTeamMemberRequestDTO(member.id(), TeamRole.MEMBER))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true));

        // 7. Test RENAME_TEAM via 3-GDG-Lead consensus
        CreateConsensusRequestDTO renameReq = new CreateConsensusRequestDTO(
                ConsensusActionType.RENAME_TEAM, createdTeamId, "GenAI & ML Team", "Updated description", null
        );
        MvcResult renInit = mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(renameReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long renameReqId = objectMapper.readTree(renInit.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + renameReqId + "/vote")
                        .header("Authorization", "Bearer " + lead2.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Approve rename 2"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + renameReqId + "/vote")
                        .header("Authorization", "Bearer " + lead3.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Approve rename 3"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        mockMvc.perform(get("/api/v1/teams/" + createdTeamId)
                        .header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("GenAI & ML Team"))
                .andExpect(jsonPath("$.slug").value("genai-ml-team"));

        // 8. Test DELETE_TEAM via 3-GDG-Lead consensus -> soft-deletes team and moves active members to past members
        CreateConsensusRequestDTO deleteReq = new CreateConsensusRequestDTO(
                ConsensusActionType.DELETE_TEAM, createdTeamId, null, null, null
        );
        MvcResult delInit = mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long delReqId = objectMapper.readTree(delInit.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + delReqId + "/vote")
                        .header("Authorization", "Bearer " + lead2.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Approve delete 2"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + delReqId + "/vote")
                        .header("Authorization", "Bearer " + lead3.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Approve delete 3"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        // Verify team is DELETED and member is now in past-members with exitReason == TEAM_DELETED
        mockMvc.perform(get("/api/v1/teams/" + createdTeamId + "/past-members")
                        .header("Authorization", "Bearer " + lead1.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].memberId").value(member.id()))
                .andExpect(jsonPath("$[0].exitReason").value("TEAM_DELETED"))
                .andExpect(jsonPath("$[0].active").value(false));
    }

    @Test
    void testF8_F9_F10_F12_TeamStatsPastMembersAndCollaborations() throws Exception {
        RegisteredUser lead1 = registerUser("lead1.f8@gdgoc.edu", "Lead 1", Set.of(RoleName.GDG_LEAD));
        RegisteredUser lead2 = registerUser("lead2.f8@gdgoc.edu", "Lead 2", Set.of(RoleName.GDG_LEAD));
        RegisteredUser lead3 = registerUser("lead3.f8@gdgoc.edu", "Lead 3", Set.of(RoleName.GDG_LEAD));
        RegisteredUser hrMember = registerUser("hr.f8@gdgoc.edu", "HR Member", Set.of(RoleName.HR_MEMBER));
        RegisteredUser webTeamLead = registerUser("webtl.f8@gdgoc.edu", "Web Team Lead", Set.of(RoleName.TEAM_LEAD));
        RegisteredUser cloudTeamLead = registerUser("cloudtl.f8@gdgoc.edu", "Cloud Team Lead", Set.of(RoleName.TEAM_LEAD));
        RegisteredUser regularMember = registerUser("reg.f8@gdgoc.edu", "Regular Member", Set.of(RoleName.TEAM_MEMBER));

        Long webTeamId = createTeamVia3LeadConsensus("Web Team", null, lead1, lead2, lead3);
        Long cloudTeamId = createTeamVia3LeadConsensus("Cloud Team", null, lead1, lead2, lead3);
        Long frontendSubTeamId = createTeamVia3LeadConsensus("Frontend Sub-Team", webTeamId, lead1, lead2, lead3);
        assertThat(frontendSubTeamId).isNotNull();

        // Assign leads and member
        mockMvc.perform(post("/api/v1/teams/" + webTeamId + "/members")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignTeamMemberRequestDTO(webTeamLead.id(), TeamRole.TEAM_LEAD))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/teams/" + cloudTeamId + "/members")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignTeamMemberRequestDTO(cloudTeamLead.id(), TeamRole.TEAM_LEAD))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/teams/" + webTeamId + "/members")
                        .header("Authorization", "Bearer " + webTeamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignTeamMemberRequestDTO(regularMember.id(), TeamRole.MEMBER))))
                .andExpect(status().isCreated());

        // Remove regularMember from Web Team -> becomes past member with REMOVED_FROM_TEAM
        mockMvc.perform(delete("/api/v1/teams/" + webTeamId + "/members/" + regularMember.id())
                        .header("Authorization", "Bearer " + webTeamLead.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.exitReason").value("REMOVED_FROM_TEAM"));

        // F9: HR_MEMBER can view past members across all teams; regularMember without grant gets 403
        mockMvc.perform(get("/api/v1/teams/past-members")
                        .header("Authorization", "Bearer " + regularMember.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/teams/past-members")
                        .header("Authorization", "Bearer " + hrMember.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].memberId").value(regularMember.id()))
                .andExpect(jsonPath("$[0].exitReason").value("REMOVED_FROM_TEAM"));

        // F12: Create Cross-Team Collaboration requires >= 2 distinct teams
        CreateCollaborationRequestDTO invalidCollab = new CreateCollaborationRequestDTO(
                "Single Team Collab", "Invalid cross-team", CollaborationType.CROSS_TEAM,
                webTeamId, List.of(webTeamId), Instant.now().plus(7, ChronoUnit.DAYS)
        );
        mockMvc.perform(post("/api/v1/collaborations")
                        .header("Authorization", "Bearer " + hrMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidCollab)))
                .andExpect(status().isBadRequest());

        // Valid Cross-Team Collaboration between Web Team and Cloud Team
        CreateCollaborationRequestDTO validCollab = new CreateCollaborationRequestDTO(
                "Web + Cloud Portal", "Joint deployment", CollaborationType.CROSS_TEAM,
                webTeamId, List.of(webTeamId, cloudTeamId), Instant.now().plus(14, ChronoUnit.DAYS)
        );
        MvcResult collabRes = mockMvc.perform(post("/api/v1/collaborations")
                        .header("Authorization", "Bearer " + hrMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCollab)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andReturn();
        long collabId = objectMapper.readTree(collabRes.getResponse().getContentAsString()).get("id").asLong();

        // Web Team Lead CANNOT approve on behalf of Cloud Team (403 Forbidden)
        mockMvc.perform(post("/api/v1/collaborations/" + collabId + "/approve-team")
                        .header("Authorization", "Bearer " + webTeamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ApproveCollaborationTeamRequestDTO(cloudTeamId, true, "Trying to approve other team")
                        )))
                .andExpect(status().isForbidden());

        // Cloud Team Lead approves participation -> collaboration status becomes ACTIVE
        mockMvc.perform(post("/api/v1/collaborations/" + collabId + "/approve-team")
                        .header("Authorization", "Bearer " + cloudTeamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ApproveCollaborationTeamRequestDTO(cloudTeamId, true, "Cloud team approves")
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // F8: Verify Team Stats for Web Team (1 active member, 1 past member, 1 sub-team, 1 active collaboration)
        mockMvc.perform(get("/api/v1/teams/" + webTeamId + "/stats")
                        .header("Authorization", "Bearer " + hrMember.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeMemberCount").value(1))
                .andExpect(jsonPath("$.pastMemberCount").value(1))
                .andExpect(jsonPath("$.subTeamCount").value(1))
                .andExpect(jsonPath("$.activeCollaborations").value(1));
    }
}
