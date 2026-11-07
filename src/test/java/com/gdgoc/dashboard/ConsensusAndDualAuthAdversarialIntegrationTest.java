package com.gdgoc.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdgoc.dashboard.auth.AuthDtos.LoginRequestDTO;
import com.gdgoc.dashboard.auth.AuthDtos.RegisterRequestDTO;
import com.gdgoc.dashboard.collaboration.CollaborationDtos.ApproveCollaborationTeamRequestDTO;
import com.gdgoc.dashboard.collaboration.CollaborationDtos.CreateCollaborationRequestDTO;
import com.gdgoc.dashboard.collaboration.CollaborationType;
import com.gdgoc.dashboard.member.Member;
import com.gdgoc.dashboard.member.MemberDtos.BioDataUpdateRequestDTO;
import com.gdgoc.dashboard.member.MemberDtos.HrAddMemberRequestDTO;
import com.gdgoc.dashboard.member.MemberDtos.TerminateMemberRequestDTO;
import com.gdgoc.dashboard.member.MemberRepository;
import com.gdgoc.dashboard.member.MemberStatus;
import com.gdgoc.dashboard.member.RoleName;
import com.gdgoc.dashboard.request.ChangeRequestStatus;
import com.gdgoc.dashboard.request.MemberChangeRequest;
import com.gdgoc.dashboard.request.MemberChangeRequestRepository;
import com.gdgoc.dashboard.request.RequestDtos.ApproveChangeRequestDTO;
import com.gdgoc.dashboard.request.RequestDtos.TeamChangeRequestDTO;
import com.gdgoc.dashboard.team.ConsensusActionType;
import com.gdgoc.dashboard.team.ConsensusStatus;
import com.gdgoc.dashboard.team.TeamDtos.AssignTeamMemberRequestDTO;
import com.gdgoc.dashboard.team.TeamDtos.ConsensusVoteRequestDTO;
import com.gdgoc.dashboard.team.TeamDtos.CreateConsensusRequestDTO;
import com.gdgoc.dashboard.team.TeamMembershipRepository;
import com.gdgoc.dashboard.team.TeamRepository;
import com.gdgoc.dashboard.team.TeamRole;
import org.junit.jupiter.api.DisplayName;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ConsensusAndDualAuthAdversarialIntegrationTest {

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
    private MemberChangeRequestRepository changeRequestRepository;

    private record RegisteredUser(Long id, String token, String email) {}

    private RegisteredUser registerUser(String email, String fullName, Set<RoleName> roles) throws Exception {
        RegisterRequestDTO req = new RegisterRequestDTO(
                email, "Password123!", fullName, "22CS999", "CSE", 3, "+91-9999900000", roles
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
                .andReturn();
        long reqId = objectMapper.readTree(initRes.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + lead2.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Lead 2 approves"))))
                .andExpect(status().isOk());

        MvcResult execRes = mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + lead3.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Lead 3 approves"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andReturn();

        return objectMapper.readTree(execRes.getResponse().getContentAsString()).get("executedTeamId").asLong();
    }

    // =========================================================================
    // Focus Item 1: Duplicate vote by the same GDG Lead on the same consensus request - ensure 409 Conflict
    // =========================================================================
    @Test
    @DisplayName("1. Duplicate vote by the same GDG Lead on the same consensus request - ensure 409 Conflict")
    void testItem1_DuplicateVoteByGdgLeadReturns409Conflict() throws Exception {
        RegisteredUser lead1 = registerUser("lead1.dup@gdgoc.edu", "GDG Lead 1", Set.of(RoleName.GDG_LEAD));
        RegisteredUser lead2 = registerUser("lead2.dup@gdgoc.edu", "GDG Lead 2", Set.of(RoleName.GDG_LEAD));

        CreateConsensusRequestDTO createReq = new CreateConsensusRequestDTO(
                ConsensusActionType.CREATE_TEAM, null, "Duplicate Vote Test Team", "Test description", null
        );

        // Lead 1 initiates (initial vote is automatically recorded)
        MvcResult initRes = mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.approvalCount").value(1))
                .andReturn();
        long reqId = objectMapper.readTree(initRes.getResponse().getContentAsString()).get("id").asLong();

        // Lead 1 attempts to vote again -> 409 Conflict
        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Trying to vote again"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Duplicate vote")));

        // Lead 2 votes once -> 200 OK (approvalCount = 2)
        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + lead2.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "First vote from Lead 2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalCount").value(2));

        // Lead 2 attempts a duplicate vote (even with different comment or negative vote) -> 409 Conflict
        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + lead2.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(false, "Lead 2 changing mind"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Duplicate vote")));
    }

    // =========================================================================
    // Focus Item 2: Non-GDG Lead attempting to initiate or vote on consensus requests - ensure 403 Forbidden
    // =========================================================================
    @Test
    @DisplayName("2. Non-GDG Lead attempting to initiate or vote on consensus requests - ensure 403 Forbidden")
    void testItem2_NonGdgLeadForbiddenFromInitiatingOrVoting() throws Exception {
        RegisteredUser gdgLead = registerUser("gdglead.auth@gdgoc.edu", "GDG Lead", Set.of(RoleName.GDG_LEAD));
        RegisteredUser hrLead = registerUser("hrlead.auth@gdgoc.edu", "HR Lead", Set.of(RoleName.HR_LEAD));
        RegisteredUser teamLead = registerUser("teamlead.auth@gdgoc.edu", "Team Lead", Set.of(RoleName.TEAM_LEAD));
        RegisteredUser hrMember = registerUser("hrmember.auth@gdgoc.edu", "HR Member", Set.of(RoleName.HR_MEMBER));
        RegisteredUser teamMember = registerUser("member.auth@gdgoc.edu", "Regular Member", Set.of(RoleName.TEAM_MEMBER));

        CreateConsensusRequestDTO createReq = new CreateConsensusRequestDTO(
                ConsensusActionType.CREATE_TEAM, null, "Auth Test Team", "Test description", null
        );

        // A. Non-GDG Leads attempting to initiate consensus request -> 403 Forbidden
        mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + teamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + hrMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + teamMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isForbidden());

        // B. Anonymous user initiating consensus request -> 401 Unauthorized
        mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isUnauthorized());

        // C. GDG Lead initiates a valid consensus request
        MvcResult initRes = mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long reqId = objectMapper.readTree(initRes.getResponse().getContentAsString()).get("id").asLong();

        // D. Non-GDG Leads attempting to vote on consensus request -> 403 Forbidden
        ConsensusVoteRequestDTO voteDto = new ConsensusVoteRequestDTO(true, "Unauthorized vote");

        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(voteDto)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + teamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(voteDto)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + hrMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(voteDto)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + teamMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(voteDto)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Focus Item 3: Vote on already executed consensus request - ensure 400 or 409 rejection
    // =========================================================================
    @Test
    @DisplayName("3. Vote on already executed or rejected consensus request - ensure 409 rejection")
    void testItem3_VoteOnAlreadyExecutedOrRejectedConsensusRequestReturns409() throws Exception {
        RegisteredUser lead1 = registerUser("lead1.exec@gdgoc.edu", "Lead 1", Set.of(RoleName.GDG_LEAD));
        RegisteredUser lead2 = registerUser("lead2.exec@gdgoc.edu", "Lead 2", Set.of(RoleName.GDG_LEAD));
        RegisteredUser lead3 = registerUser("lead3.exec@gdgoc.edu", "Lead 3", Set.of(RoleName.GDG_LEAD));
        RegisteredUser lead4 = registerUser("lead4.exec@gdgoc.edu", "Lead 4", Set.of(RoleName.GDG_LEAD));

        // Part A: Execute consensus request with 3 approvals
        CreateConsensusRequestDTO createReq = new CreateConsensusRequestDTO(
                ConsensusActionType.CREATE_TEAM, null, "Executed Team", "Executed description", null
        );
        MvcResult initRes = mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long reqId = objectMapper.readTree(initRes.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + lead2.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Approve 2"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + lead3.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Approve 3"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        // Lead 4 attempts to vote on already EXECUTED request -> 409 Conflict
        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + lead4.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Lead 4 late vote"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already EXECUTED")));

        // Part B: Reject consensus request
        CreateConsensusRequestDTO rejectReq = new CreateConsensusRequestDTO(
                ConsensusActionType.CREATE_TEAM, null, "Rejected Team", "Will be rejected", null
        );
        MvcResult rejectInit = mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rejectReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long rejectReqId = objectMapper.readTree(rejectInit.getResponse().getContentAsString()).get("id").asLong();

        // Lead 2 votes NO -> status becomes REJECTED
        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + rejectReqId + "/vote")
                        .header("Authorization", "Bearer " + lead2.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(false, "Veto/Reject"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // Lead 3 attempts to vote on already REJECTED request -> 409 Conflict
        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + rejectReqId + "/vote")
                        .header("Authorization", "Bearer " + lead3.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Late approve"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already REJECTED")));
    }

    // =========================================================================
    // Focus Item 4: Single-approval bypass attempt on HR Member change request
    //               (only HR_LEAD approved, verify data is NOT mutated in database)
    // =========================================================================
    @Test
    @DisplayName("4. Single-approval bypass attempt on HR Member change request - verify DB is NOT mutated until dual authorization")
    void testItem4_SingleApprovalBypassAttemptLeavesDatabaseUnmutated() throws Exception {
        RegisteredUser gdgLead1 = registerUser("gdg1.hr@gdgoc.edu", "GDG Lead 1", Set.of(RoleName.GDG_LEAD));
        RegisteredUser gdgLead2 = registerUser("gdg2.hr@gdgoc.edu", "GDG Lead 2", Set.of(RoleName.GDG_LEAD));
        RegisteredUser gdgLead3 = registerUser("gdg3.hr@gdgoc.edu", "GDG Lead 3", Set.of(RoleName.GDG_LEAD));
        RegisteredUser hrLead = registerUser("hrlead.dual@gdgoc.edu", "HR Lead", Set.of(RoleName.HR_LEAD));
        RegisteredUser teamLead = registerUser("tl.dual@gdgoc.edu", "Mobile Team Lead", Set.of(RoleName.TEAM_LEAD));
        RegisteredUser hrMember = registerUser("hrmember.dual@gdgoc.edu", "HR Member", Set.of(RoleName.HR_MEMBER));

        // Create target team and assign teamLead
        Long mobileTeamId = createTeamVia3LeadConsensus("Mobile App Team", null, gdgLead1, gdgLead2, gdgLead3);
        mockMvc.perform(post("/api/v1/teams/" + mobileTeamId + "/members")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignTeamMemberRequestDTO(teamLead.id(), TeamRole.TEAM_LEAD))))
                .andExpect(status().isCreated());

        // --- SUBTEST A: ADD_MEMBER Change Request ---
        String prospectiveEmail = "candidate.flutter@gdgoc.edu";
        HrAddMemberRequestDTO addMemberDto = new HrAddMemberRequestDTO(
                prospectiveEmail, "SecretPass123!", "Candidate Flutter",
                "22CS555", "CSE", 2, "+91-9876543210", "Flutter enthusiast", "Flutter, Dart",
                "https://github.com/flutter", "https://linkedin.com/in/flutter",
                Set.of(RoleName.TEAM_MEMBER), mobileTeamId, null, "Recruiting Flutter developer"
        );

        // HR Member initiates add member -> Staged in change request (202 Accepted)
        MvcResult stageRes = mockMvc.perform(post("/api/v1/members")
                        .header("Authorization", "Bearer " + hrMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addMemberDto)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionStatus").value("PENDING_AUTHORIZATION"))
                .andReturn();
        long addChangeReqId = objectMapper.readTree(stageRes.getResponse().getContentAsString()).get("changeRequestId").asLong();

        // Adversarial verification 1: Member must NOT exist in database right now
        assertThat(memberRepository.existsByEmailIgnoreCase(prospectiveEmail)).isFalse();

        // HR_LEAD alone approves the change request (Single Approval)
        mockMvc.perform(post("/api/v1/change-requests/" + addChangeReqId + "/approve")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveChangeRequestDTO(true, "HR Lead approves candidate"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_APPROVED"))
                .andExpect(jsonPath("$.hrLeadApproved").value(true))
                .andExpect(jsonPath("$.teamLeadApproved").value(false));

        // Adversarial verification 2: Member STILL must NOT exist in database after single HR_LEAD approval!
        assertThat(memberRepository.existsByEmailIgnoreCase(prospectiveEmail)).isFalse();
        Optional<Member> memberSearch = memberRepository.findByEmailIgnoreCase(prospectiveEmail);
        assertThat(memberSearch).isEmpty();

        // Prospective member cannot log in
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDTO(prospectiveEmail, "SecretPass123!"))))
                .andExpect(status().isUnauthorized());

        // Duplicate approval by HR_LEAD does not execute
        mockMvc.perform(post("/api/v1/change-requests/" + addChangeReqId + "/approve")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveChangeRequestDTO(true, "HR Lead duplicate approval"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_APPROVED"))
                .andExpect(jsonPath("$.teamLeadApproved").value(false));
        assertThat(memberRepository.existsByEmailIgnoreCase(prospectiveEmail)).isFalse();

        // Now Team Lead approves -> Dual authorization completed -> status EXECUTED!
        mockMvc.perform(post("/api/v1/change-requests/" + addChangeReqId + "/approve")
                        .header("Authorization", "Bearer " + teamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveChangeRequestDTO(true, "Team Lead approves candidate"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.hrLeadApproved").value(true))
                .andExpect(jsonPath("$.teamLeadApproved").value(true));

        // Adversarial verification 3: Member is NOW created in the database and assigned to Mobile Team!
        assertThat(memberRepository.existsByEmailIgnoreCase(prospectiveEmail)).isTrue();
        Member createdMember = memberRepository.findByEmailIgnoreCase(prospectiveEmail).orElseThrow();
        assertThat(createdMember.getFullName()).isEqualTo("Candidate Flutter");
        assertThat(teamMembershipRepository.existsByTeamIdAndMemberIdAndActiveTrue(mobileTeamId, createdMember.getId())).isTrue();

        // --- SUBTEST B: BIO_DATA_UPDATE Change Request ---
        BioDataUpdateRequestDTO bioUpdate = new BioDataUpdateRequestDTO(
                "Candidate Flutter Senior", "22CS555", "CSE", 3, "+91-9876543210",
                "Senior Flutter Lead", "Flutter, Dart, Kotlin", "https://github.com/flutter", "https://linkedin.com/in/flutter",
                "Promoting candidate"
        );
        MvcResult bioStageRes = mockMvc.perform(put("/api/v1/members/" + createdMember.getId() + "/bio-data")
                        .header("Authorization", "Bearer " + hrMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bioUpdate)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionStatus").value("PENDING_AUTHORIZATION"))
                .andReturn();
        long bioChangeReqId = objectMapper.readTree(bioStageRes.getResponse().getContentAsString()).get("changeRequestId").asLong();

        // HR Lead alone approves bio update
        mockMvc.perform(post("/api/v1/change-requests/" + bioChangeReqId + "/approve")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveChangeRequestDTO(true, "HR Lead approves promo"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_APPROVED"));

        // Adversarial verification 4: Bio-data in database must NOT be mutated yet!
        Member unmutated = memberRepository.findById(createdMember.getId()).orElseThrow();
        assertThat(unmutated.getFullName()).isEqualTo("Candidate Flutter");
        assertThat(unmutated.getBio()).isEqualTo("Flutter enthusiast");
    }

    // =========================================================================
    // Focus Item 5: Unauthorized member approving HR change request - ensure 403 Forbidden
    // =========================================================================
    @Test
    @DisplayName("5. Unauthorized member approving HR change request - ensure 403 Forbidden")
    void testItem5_UnauthorizedMemberApprovingChangeRequestReturns403() throws Exception {
        RegisteredUser gdgLead1 = registerUser("gdg1.scope@gdgoc.edu", "GDG Lead 1", Set.of(RoleName.GDG_LEAD));
        RegisteredUser gdgLead2 = registerUser("gdg2.scope@gdgoc.edu", "GDG Lead 2", Set.of(RoleName.GDG_LEAD));
        RegisteredUser gdgLead3 = registerUser("gdg3.scope@gdgoc.edu", "GDG Lead 3", Set.of(RoleName.GDG_LEAD));
        RegisteredUser hrLead = registerUser("hrlead.scope@gdgoc.edu", "HR Lead", Set.of(RoleName.HR_LEAD));
        RegisteredUser hrMember = registerUser("hrmem.scope@gdgoc.edu", "HR Member", Set.of(RoleName.HR_MEMBER));
        RegisteredUser teamMember = registerUser("mem.scope@gdgoc.edu", "Regular Member", Set.of(RoleName.TEAM_MEMBER));

        Long teamA = createTeamVia3LeadConsensus("Team Alpha", null, gdgLead1, gdgLead2, gdgLead3);
        Long teamB = createTeamVia3LeadConsensus("Team Beta", null, gdgLead1, gdgLead2, gdgLead3);

        RegisteredUser leadTeamA = registerUser("leadA.scope@gdgoc.edu", "Lead Team A", Set.of(RoleName.TEAM_LEAD));
        RegisteredUser leadTeamB = registerUser("leadB.scope@gdgoc.edu", "Lead Team B", Set.of(RoleName.TEAM_LEAD));

        // Assign leadTeamA to Team Alpha and leadTeamB to Team Beta
        mockMvc.perform(post("/api/v1/teams/" + teamA + "/members")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignTeamMemberRequestDTO(leadTeamA.id(), TeamRole.TEAM_LEAD))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/teams/" + teamB + "/members")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignTeamMemberRequestDTO(leadTeamB.id(), TeamRole.TEAM_LEAD))))
                .andExpect(status().isCreated());

        // HR Member stages a member addition for Team Alpha
        HrAddMemberRequestDTO addReq = new HrAddMemberRequestDTO(
                "alpha.dev@gdgoc.edu", "Pass12345!", "Alpha Dev",
                "22CS888", "CSE", 3, "+91-9870001111", null, null,
                null, null, Set.of(RoleName.TEAM_MEMBER), teamA, null, "Hiring for Team Alpha"
        );
        MvcResult stageRes = mockMvc.perform(post("/api/v1/members")
                        .header("Authorization", "Bearer " + hrMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addReq)))
                .andExpect(status().isAccepted())
                .andReturn();
        long changeReqId = objectMapper.readTree(stageRes.getResponse().getContentAsString()).get("changeRequestId").asLong();

        ApproveChangeRequestDTO approveDto = new ApproveChangeRequestDTO(true, "Attempted approval");

        // A. Regular TEAM_MEMBER cannot approve change request -> 403 Forbidden
        mockMvc.perform(post("/api/v1/change-requests/" + changeReqId + "/approve")
                        .header("Authorization", "Bearer " + teamMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveDto)))
                .andExpect(status().isForbidden());

        // B. HR_MEMBER (non-lead) cannot approve change request -> 403 Forbidden
        mockMvc.perform(post("/api/v1/change-requests/" + changeReqId + "/approve")
                        .header("Authorization", "Bearer " + hrMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveDto)))
                .andExpect(status().isForbidden());

        // C. TEAM_LEAD of unrelated Team Beta cannot approve change request for Team Alpha -> 403 Forbidden
        mockMvc.perform(post("/api/v1/change-requests/" + changeReqId + "/approve")
                        .header("Authorization", "Bearer " + leadTeamB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveDto)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not authorized for this member's team scope")));

        // D. Terminated lead cannot approve change request -> 403 Forbidden
        RegisteredUser terminatedLead = registerUser("termlead@gdgoc.edu", "Term Lead", Set.of(RoleName.HR_LEAD));
        mockMvc.perform(post("/api/v1/members/" + terminatedLead.id() + "/terminate")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TerminateMemberRequestDTO("Terminated lead", Instant.now()))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/change-requests/" + changeReqId + "/approve")
                        .header("Authorization", "Bearer " + terminatedLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveDto)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Focus Item 6: Team change request with null or blank reason - ensure 400 Bad Request
    // =========================================================================
    @Test
    @DisplayName("6. Team change request with null or blank reason - ensure 400 Bad Request")
    void testItem6_TeamChangeRequestWithNullOrBlankReasonReturns400() throws Exception {
        RegisteredUser gdgLead1 = registerUser("gdg1.tc@gdgoc.edu", "GDG Lead 1", Set.of(RoleName.GDG_LEAD));
        RegisteredUser gdgLead2 = registerUser("gdg2.tc@gdgoc.edu", "GDG Lead 2", Set.of(RoleName.GDG_LEAD));
        RegisteredUser gdgLead3 = registerUser("gdg3.tc@gdgoc.edu", "GDG Lead 3", Set.of(RoleName.GDG_LEAD));
        RegisteredUser member = registerUser("member.tc@gdgoc.edu", "Transfer Member", Set.of(RoleName.TEAM_MEMBER));

        Long teamSource = createTeamVia3LeadConsensus("Source Team", null, gdgLead1, gdgLead2, gdgLead3);
        Long teamTarget = createTeamVia3LeadConsensus("Target Team", null, gdgLead1, gdgLead2, gdgLead3);

        // Assign member to source team
        mockMvc.perform(post("/api/v1/teams/" + teamSource + "/members")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignTeamMemberRequestDTO(member.id(), TeamRole.MEMBER))))
                .andExpect(status().isCreated());

        // A. Reason is null -> 400 Bad Request
        String nullReasonPayload = "{\"currentTeamId\":" + teamSource + ",\"targetTeamId\":" + teamTarget + ",\"reason\":null}";
        mockMvc.perform(post("/api/v1/profile/me/team-change-requests")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nullReasonPayload))
                .andExpect(status().isBadRequest());

        // B. Reason is empty string "" -> 400 Bad Request
        String emptyReasonPayload = "{\"currentTeamId\":" + teamSource + ",\"targetTeamId\":" + teamTarget + ",\"reason\":\"\"}";
        mockMvc.perform(post("/api/v1/profile/me/team-change-requests")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyReasonPayload))
                .andExpect(status().isBadRequest());

        // C. Reason is whitespace only "    " -> 400 Bad Request
        String blankReasonPayload = "{\"currentTeamId\":" + teamSource + ",\"targetTeamId\":" + teamTarget + ",\"reason\":\"     \"}";
        mockMvc.perform(post("/api/v1/profile/me/team-change-requests")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blankReasonPayload))
                .andExpect(status().isBadRequest());

        // D. targetTeamId is missing/null -> 400 Bad Request
        String nullTargetPayload = "{\"currentTeamId\":" + teamSource + ",\"targetTeamId\":null,\"reason\":\"Valid reason\"}";
        mockMvc.perform(post("/api/v1/profile/me/team-change-requests")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nullTargetPayload))
                .andExpect(status().isBadRequest());

        // E. targetTeamId is same as currentTeamId -> 400 Bad Request
        String sameTeamPayload = "{\"currentTeamId\":" + teamSource + ",\"targetTeamId\":" + teamSource + ",\"reason\":\"I want to stay in same team\"}";
        mockMvc.perform(post("/api/v1/profile/me/team-change-requests")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sameTeamPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("must be different")));

        // F. Valid team change request -> 201 Created
        String validPayload = "{\"currentTeamId\":" + teamSource + ",\"targetTeamId\":" + teamTarget + ",\"reason\":\"Interested in backend architecture\"}";
        mockMvc.perform(post("/api/v1/profile/me/team-change-requests")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_AUTHORIZATION"))
                .andExpect(jsonPath("$.targetTeamId").value(teamTarget))
                .andExpect(jsonPath("$.reason").value("Interested in backend architecture"));
    }

    // =========================================================================
    // Focus Item 7: Cross-team collaboration creation with fewer than 2 distinct teams - ensure 400 Bad Request
    // =========================================================================
    @Test
    @DisplayName("7. Cross-team collaboration creation with fewer than 2 distinct teams - ensure 400 Bad Request")
    void testItem7_CrossTeamCollaborationWithFewerThanTwoTeamsReturns400() throws Exception {
        RegisteredUser gdgLead1 = registerUser("gdg1.collab@gdgoc.edu", "GDG Lead 1", Set.of(RoleName.GDG_LEAD));
        RegisteredUser gdgLead2 = registerUser("gdg2.collab@gdgoc.edu", "GDG Lead 2", Set.of(RoleName.GDG_LEAD));
        RegisteredUser gdgLead3 = registerUser("gdg3.collab@gdgoc.edu", "GDG Lead 3", Set.of(RoleName.GDG_LEAD));
        RegisteredUser teamLead = registerUser("tl.collab@gdgoc.edu", "Team Lead", Set.of(RoleName.TEAM_LEAD));

        Long teamOne = createTeamVia3LeadConsensus("Collab Team 1", null, gdgLead1, gdgLead2, gdgLead3);
        Long teamTwo = createTeamVia3LeadConsensus("Collab Team 2", null, gdgLead1, gdgLead2, gdgLead3);

        // A. participatingTeamIds has only 1 team -> 400 Bad Request
        CreateCollaborationRequestDTO singleTeam = new CreateCollaborationRequestDTO(
                "Single Team Collab", "Invalid single team", CollaborationType.CROSS_TEAM,
                teamOne, List.of(teamOne), Instant.now().plus(7, ChronoUnit.DAYS)
        );
        mockMvc.perform(post("/api/v1/collaborations")
                        .header("Authorization", "Bearer " + teamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(singleTeam)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("at least 2 distinct participating teams")));

        // B. participatingTeamIds has duplicate of the same team -> distinct is 1 -> 400 Bad Request
        CreateCollaborationRequestDTO duplicateTeam = new CreateCollaborationRequestDTO(
                "Duplicate Team Collab", "Invalid duplicate team", CollaborationType.CROSS_TEAM,
                teamOne, List.of(teamOne, teamOne), Instant.now().plus(7, ChronoUnit.DAYS)
        );
        mockMvc.perform(post("/api/v1/collaborations")
                        .header("Authorization", "Bearer " + teamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicateTeam)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("at least 2 distinct participating teams")));

        // C. participatingTeamIds is empty -> 400 Bad Request
        CreateCollaborationRequestDTO emptyTeams = new CreateCollaborationRequestDTO(
                "Empty Teams Collab", "No teams", CollaborationType.CROSS_TEAM,
                teamOne, List.of(), Instant.now().plus(7, ChronoUnit.DAYS)
        );
        mockMvc.perform(post("/api/v1/collaborations")
                        .header("Authorization", "Bearer " + teamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyTeams)))
                .andExpect(status().isBadRequest());

        // D. participatingTeamIds is null -> 400 Bad Request
        CreateCollaborationRequestDTO nullTeams = new CreateCollaborationRequestDTO(
                "Null Teams Collab", "Null teams", CollaborationType.CROSS_TEAM,
                teamOne, null, Instant.now().plus(7, ChronoUnit.DAYS)
        );
        mockMvc.perform(post("/api/v1/collaborations")
                        .header("Authorization", "Bearer " + teamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nullTeams)))
                .andExpect(status().isBadRequest());

        // E. Valid cross-team collaboration with 2 distinct teams -> 201 Created
        CreateCollaborationRequestDTO validCollab = new CreateCollaborationRequestDTO(
                "Multi-team Hackathon", "Joint event", CollaborationType.CROSS_TEAM,
                teamOne, List.of(teamOne, teamTwo), Instant.now().plus(14, ChronoUnit.DAYS)
        );
        mockMvc.perform(post("/api/v1/collaborations")
                        .header("Authorization", "Bearer " + teamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCollab)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participatingTeamIds").isArray())
                .andExpect(jsonPath("$.participatingTeamIds.length()").value(2))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }
}
