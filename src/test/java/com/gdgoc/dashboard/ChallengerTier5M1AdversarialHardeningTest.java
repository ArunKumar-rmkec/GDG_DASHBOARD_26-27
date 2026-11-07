package com.gdgoc.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdgoc.dashboard.agreement.AgreementDtos.CreateAgreementRequestDTO;
import com.gdgoc.dashboard.agreement.AgreementDtos.IssueAgreementRequestDTO;
import com.gdgoc.dashboard.agreement.AgreementDtos.SignAgreementRequestDTO;
import com.gdgoc.dashboard.agreement.SignatureStatus;
import com.gdgoc.dashboard.auth.AuthDtos.LoginRequestDTO;
import com.gdgoc.dashboard.auth.AuthDtos.RegisterRequestDTO;
import com.gdgoc.dashboard.member.Member;
import com.gdgoc.dashboard.member.MemberDtos.BioDataUpdateRequestDTO;
import com.gdgoc.dashboard.member.MemberDtos.HrAddMemberRequestDTO;
import com.gdgoc.dashboard.member.MemberDtos.TerminateMemberRequestDTO;
import com.gdgoc.dashboard.member.MemberRepository;
import com.gdgoc.dashboard.member.MemberStatus;
import com.gdgoc.dashboard.member.RoleName;
import com.gdgoc.dashboard.permission.GrantStatus;
import com.gdgoc.dashboard.permission.PermissionCode;
import com.gdgoc.dashboard.permission.PermissionDtos.ApproveGrantRequestDTO;
import com.gdgoc.dashboard.permission.PermissionDtos.CreatePermissionGrantRequestDTO;
import com.gdgoc.dashboard.permission.PermissionGrantRepository;
import com.gdgoc.dashboard.request.ChangeRequestStatus;
import com.gdgoc.dashboard.request.ChangeRequestType;
import com.gdgoc.dashboard.request.MemberChangeRequest;
import com.gdgoc.dashboard.request.MemberChangeRequestRepository;
import com.gdgoc.dashboard.request.RequestDtos.ApproveChangeRequestDTO;
import com.gdgoc.dashboard.team.ConsensusActionType;
import com.gdgoc.dashboard.team.ConsensusStatus;
import com.gdgoc.dashboard.team.Team;
import com.gdgoc.dashboard.team.TeamDtos.AssignTeamMemberRequestDTO;
import com.gdgoc.dashboard.team.TeamDtos.ConsensusVoteRequestDTO;
import com.gdgoc.dashboard.team.TeamDtos.CreateConsensusRequestDTO;
import com.gdgoc.dashboard.team.TeamMembership;
import com.gdgoc.dashboard.team.TeamMembershipRepository;
import com.gdgoc.dashboard.team.TeamRepository;
import com.gdgoc.dashboard.team.TeamRole;
import com.gdgoc.dashboard.team.TeamStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tier 5 Adversarial Stress Testing for GDGoC Campus Dashboard Backend Core Milestone 1 (F1-F13, F20-F22).
 * Formulated by Challenger challenger_m3_1.
 *
 * Attack Vectors Tested:
 * 1. Concurrent duplicate voting on 3-GDG-Lead consensus requests (race conditions).
 * 2. Consensus DELETE_TEAM cascade to past members, status transition to DELETED, and prevention of operations on deleted teams.
 * 3. Reverse dual-approval workflow (TEAM_LEAD approves first, DB unmutated until HR_LEAD approves).
 * 4. GDG_LEAD single-action direct override on staged change requests.
 * 5. Project Contributor Agreement (PCA) tampering: cross-member signature attempt, re-signing conflict, premature download, and unauthorized download.
 * 6. Cross-team permission grant approval and rejection quarantine state machine.
 * 7. Stateless JWT stale token invalidation post-termination (token still cryptographically valid, but member terminated in DB).
 * 8. Invalid self-service change requests boundary attacks (invalid academic years, non-existent target teams, deleted target teams).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ChallengerTier5M1AdversarialHardeningTest {

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

    @Autowired
    private PermissionGrantRepository permissionGrantRepository;

    private record RegisteredUser(Long id, String token, String email) {}

    private RegisteredUser registerUser(String email, String fullName, Set<RoleName> roles) throws Exception {
        RegisterRequestDTO req = new RegisterRequestDTO(
                email, "StrongPass123!", fullName, "22CS" + (int)(Math.random() * 9000 + 1000), "CSE", 3, "+91-9876543210", roles
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
    // 1. Concurrent Duplicate Consensus Voting Race Condition Attack
    // =========================================================================
    @Test
    @DisplayName("Tier 5 Adversarial 1: Concurrent duplicate votes by same GDG Lead must yield exactly 1 success and N-1 409 Conflicts")
    void testAdv01_ConcurrentDuplicateConsensusVotingRaceCondition() throws Exception {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        RegisteredUser lead1 = registerUser("lead1.race." + uid + "@gdgoc.edu", "GDG Lead 1", Set.of(RoleName.GDG_LEAD));
        RegisteredUser lead2 = registerUser("lead2.race." + uid + "@gdgoc.edu", "GDG Lead 2", Set.of(RoleName.GDG_LEAD));

        CreateConsensusRequestDTO createReq = new CreateConsensusRequestDTO(
                ConsensusActionType.CREATE_TEAM, null, "Concurrent Race Team " + uid, "Race test description", null
        );

        MvcResult initRes = mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long reqId = objectMapper.readTree(initRes.getResponse().getContentAsString()).get("id").asLong();

        int concurrencyLevel = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrencyLevel);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrencyLevel);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger otherCount = new AtomicInteger(0);

        for (int i = 0; i < concurrencyLevel; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    MvcResult voteRes = mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                                    .header("Authorization", "Bearer " + lead2.token())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Concurrent vote #" + index))))
                            .andReturn();
                    int statusCode = voteRes.getResponse().getStatus();
                    if (statusCode == 200) {
                        successCount.incrementAndGet();
                    } else if (statusCode == 409) {
                        conflictCount.incrementAndGet();
                    } else {
                        otherCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    conflictCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Fire all threads simultaneously
        startLatch.countDown();
        boolean completedInTime = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completedInTime).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(concurrencyLevel - 1);
        assertThat(otherCount.get()).isEqualTo(0);

        // Verify request approval count in DB is exactly 2 (Lead 1 + Lead 2)
        mockMvc.perform(get("/api/v1/teams/consensus-requests/" + reqId)
                        .header("Authorization", "Bearer " + lead1.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalCount").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    // =========================================================================
    // 2. Consensus DELETE_TEAM Cascade to Past Members & Blocking Operations on Deleted Teams
    // =========================================================================
    @Test
    @DisplayName("Tier 5 Adversarial 2: Consensus DELETE_TEAM soft-deletes team, transitions active members to past members, and rejects subsequent mutations")
    void testAdv02_ConsensusDeleteTeamCascadesToPastMembersAndBlocksNewAssignments() throws Exception {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        RegisteredUser lead1 = registerUser("lead1.del." + uid + "@gdgoc.edu", "GDG Lead 1", Set.of(RoleName.GDG_LEAD));
        RegisteredUser lead2 = registerUser("lead2.del." + uid + "@gdgoc.edu", "GDG Lead 2", Set.of(RoleName.GDG_LEAD));
        RegisteredUser lead3 = registerUser("lead3.del." + uid + "@gdgoc.edu", "GDG Lead 3", Set.of(RoleName.GDG_LEAD));
        RegisteredUser member1 = registerUser("mem1.del." + uid + "@gdgoc.edu", "Member One", Set.of(RoleName.TEAM_MEMBER));
        RegisteredUser member2 = registerUser("mem2.del." + uid + "@gdgoc.edu", "Member Two", Set.of(RoleName.TEAM_MEMBER));
        RegisteredUser newMember = registerUser("mem3.del." + uid + "@gdgoc.edu", "Member Three", Set.of(RoleName.TEAM_MEMBER));

        Long teamId = createTeamVia3LeadConsensus("Obsolete Team " + uid, null, lead1, lead2, lead3);

        // Assign member1 and member2 to team
        mockMvc.perform(post("/api/v1/teams/" + teamId + "/members")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignTeamMemberRequestDTO(member1.id(), TeamRole.MEMBER))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/teams/" + teamId + "/members")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignTeamMemberRequestDTO(member2.id(), TeamRole.MEMBER))))
                .andExpect(status().isCreated());

        // Verify members are active in team
        assertThat(teamMembershipRepository.countByTeamIdAndActiveTrue(teamId)).isEqualTo(2);

        // Initiate DELETE_TEAM consensus request
        CreateConsensusRequestDTO deleteReq = new CreateConsensusRequestDTO(
                ConsensusActionType.DELETE_TEAM, teamId, null, "Decommissioning obsolete team", null
        );
        MvcResult delInit = mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long delReqId = objectMapper.readTree(delInit.getResponse().getContentAsString()).get("id").asLong();

        // Vote 2
        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + delReqId + "/vote")
                        .header("Authorization", "Bearer " + lead2.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Lead 2 confirms delete"))))
                .andExpect(status().isOk());

        // Vote 3 executes delete
        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + delReqId + "/vote")
                        .header("Authorization", "Bearer " + lead3.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Lead 3 confirms delete"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        // 1. Team is soft-deleted in DB
        Team deletedTeam = teamRepository.findById(teamId).orElseThrow();
        assertThat(deletedTeam.getStatus()).isEqualTo(TeamStatus.DELETED);
        assertThat(deletedTeam.getDeletedAt()).isNotNull();

        // 2. Active memberships are transitioned to inactive with exitReason = "TEAM_DELETED"
        assertThat(teamMembershipRepository.countByTeamIdAndActiveTrue(teamId)).isEqualTo(0);
        List<TeamMembership> past = teamMembershipRepository.findByTeamIdAndActiveFalseOrderByLeftAtDesc(teamId);
        assertThat(past).hasSize(2);
        assertThat(past).allMatch(m -> "TEAM_DELETED".equals(m.getExitReason()) && !m.isActive());

        // 3. Past-members endpoint reflects this history
        mockMvc.perform(get("/api/v1/teams/" + teamId + "/past-members")
                        .header("Authorization", "Bearer " + lead1.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].exitReason").value("TEAM_DELETED"));

        // 4. Assigning member to deleted team is rejected (400 Bad Request)
        mockMvc.perform(post("/api/v1/teams/" + teamId + "/members")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignTeamMemberRequestDTO(newMember.id(), TeamRole.MEMBER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("deleted team")));

        // 5. Consensus to rename a deleted team is rejected (400 Bad Request)
        CreateConsensusRequestDTO renameDeleted = new CreateConsensusRequestDTO(
                ConsensusActionType.RENAME_TEAM, teamId, "Ghost Team", "Renaming deleted team", null
        );
        mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(renameDeleted)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Cannot rename a deleted team")));

        // 6. Consensus to delete an already deleted team is rejected (409 Conflict)
        mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + lead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already deleted")));
    }

    // =========================================================================
    // 3. Reverse Dual-Approval Workflow: TEAM_LEAD Approves First, HR_LEAD Approves Second
    // =========================================================================
    @Test
    @DisplayName("Tier 5 Adversarial 3: Reverse dual approval (TEAM_LEAD first, then HR_LEAD) preserves partial state and only mutates DB on full consensus")
    void testAdv03_ReverseDualApprovalWorkflow_TeamLeadFirstThenHrLead() throws Exception {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        RegisteredUser gdgLead1 = registerUser("gdg1.rev." + uid + "@gdgoc.edu", "GDG Lead 1", Set.of(RoleName.GDG_LEAD));
        RegisteredUser gdgLead2 = registerUser("gdg2.rev." + uid + "@gdgoc.edu", "GDG Lead 2", Set.of(RoleName.GDG_LEAD));
        RegisteredUser gdgLead3 = registerUser("gdg3.rev." + uid + "@gdgoc.edu", "GDG Lead 3", Set.of(RoleName.GDG_LEAD));
        RegisteredUser hrLead = registerUser("hrlead.rev." + uid + "@gdgoc.edu", "HR Lead", Set.of(RoleName.HR_LEAD));
        RegisteredUser teamLead = registerUser("teamlead.rev." + uid + "@gdgoc.edu", "Web Team Lead", Set.of(RoleName.TEAM_LEAD));
        RegisteredUser hrMember = registerUser("hrmem.rev." + uid + "@gdgoc.edu", "HR Member", Set.of(RoleName.HR_MEMBER));

        Long webTeamId = createTeamVia3LeadConsensus("Web Dev Team " + uid, null, gdgLead1, gdgLead2, gdgLead3);

        // Assign teamLead to Web Team
        mockMvc.perform(post("/api/v1/teams/" + webTeamId + "/members")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignTeamMemberRequestDTO(teamLead.id(), TeamRole.TEAM_LEAD))))
                .andExpect(status().isCreated());

        String recruitEmail = "react.dev." + uid + "@gdgoc.edu";
        HrAddMemberRequestDTO addDto = new HrAddMemberRequestDTO(
                recruitEmail, "ReactPass123!", "React Developer " + uid,
                "22CS777", "CSE", 3, "+91-9871112222", "Frontend developer", "React, TypeScript",
                "https://github.com/react", "https://linkedin.com/in/react",
                Set.of(RoleName.TEAM_MEMBER), webTeamId, TeamRole.MEMBER, "Recruiting for web team"
        );

        // HR Member stages the addition
        MvcResult stageRes = mockMvc.perform(post("/api/v1/members")
                        .header("Authorization", "Bearer " + hrMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addDto)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionStatus").value("PENDING_AUTHORIZATION"))
                .andReturn();
        long reqId = objectMapper.readTree(stageRes.getResponse().getContentAsString()).get("changeRequestId").asLong();

        // Candidate must not exist
        assertThat(memberRepository.existsByEmailIgnoreCase(recruitEmail)).isFalse();

        // STEP 1: TEAM_LEAD approves first
        mockMvc.perform(post("/api/v1/change-requests/" + reqId + "/approve")
                        .header("Authorization", "Bearer " + teamLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveChangeRequestDTO(true, "Team Lead approves candidate first"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_APPROVED"))
                .andExpect(jsonPath("$.teamLeadApproved").value(true))
                .andExpect(jsonPath("$.hrLeadApproved").value(false));

        // DB still must not have the member
        assertThat(memberRepository.existsByEmailIgnoreCase(recruitEmail)).isFalse();

        // Candidate cannot login
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDTO(recruitEmail, "ReactPass123!"))))
                .andExpect(status().isUnauthorized());

        // STEP 2: HR_LEAD approves second -> Dual authorization completed!
        mockMvc.perform(post("/api/v1/change-requests/" + reqId + "/approve")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveChangeRequestDTO(true, "HR Lead approves second"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.teamLeadApproved").value(true))
                .andExpect(jsonPath("$.hrLeadApproved").value(true));

        // DB is now mutated: candidate exists and can log in!
        assertThat(memberRepository.existsByEmailIgnoreCase(recruitEmail)).isTrue();
        Member created = memberRepository.findByEmailIgnoreCase(recruitEmail).orElseThrow();
        assertThat(teamMembershipRepository.existsByTeamIdAndMemberIdAndActiveTrue(webTeamId, created.getId())).isTrue();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequestDTO(recruitEmail, "ReactPass123!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    // =========================================================================
    // 4. GDG_LEAD Direct Override on Staged Change Request
    // =========================================================================
    @Test
    @DisplayName("Tier 5 Adversarial 4: GDG_LEAD single approval directly executes staged change request with override tracking")
    void testAdv04_GdgLeadDirectOverrideOnStagedChangeRequest() throws Exception {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        RegisteredUser gdgLead = registerUser("gdg.override." + uid + "@gdgoc.edu", "GDG Super Lead", Set.of(RoleName.GDG_LEAD));
        RegisteredUser hrMember = registerUser("hrmem.ovr." + uid + "@gdgoc.edu", "HR Member", Set.of(RoleName.HR_MEMBER));
        RegisteredUser targetMember = registerUser("target.ovr." + uid + "@gdgoc.edu", "Target Member", Set.of(RoleName.TEAM_MEMBER));

        BioDataUpdateRequestDTO bioUpdate = new BioDataUpdateRequestDTO(
                "Target Member Promoted", "22CS999", "ECE", 4, "+91-9870003333",
                "Promoted to Senior Contributor", "Spring Boot, Kubernetes", "https://github.com/target", "https://linkedin.com/in/target",
                "Direct lead promotion"
        );

        // HR Member stages the bio-data update
        MvcResult stageRes = mockMvc.perform(put("/api/v1/members/" + targetMember.id() + "/bio-data")
                        .header("Authorization", "Bearer " + hrMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bioUpdate)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionStatus").value("PENDING_AUTHORIZATION"))
                .andReturn();
        long reqId = objectMapper.readTree(stageRes.getResponse().getContentAsString()).get("changeRequestId").asLong();

        // GDG_LEAD approves directly -> Executes immediately with gdgLeadOverrideById
        mockMvc.perform(post("/api/v1/change-requests/" + reqId + "/approve")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveChangeRequestDTO(true, "Super Admin direct override"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.gdgLeadOverrideById").value(gdgLead.id()))
                .andExpect(jsonPath("$.hrLeadApproved").value(true))
                .andExpect(jsonPath("$.teamLeadApproved").value(true));

        // DB bio-data is immediately mutated
        Member updated = memberRepository.findById(targetMember.id()).orElseThrow();
        assertThat(updated.getFullName()).isEqualTo("Target Member Promoted");
        assertThat(updated.getDepartment()).isEqualTo("ECE");
        assertThat(updated.getAcademicYear()).isEqualTo(4);
        assertThat(updated.getBio()).isEqualTo("Promoted to Senior Contributor");
    }

    // =========================================================================
    // 5. Project Contributor Agreement (PCA) Adversarial Boundary Attacks
    // =========================================================================
    @Test
    @DisplayName("Tier 5 Adversarial 5: PCA tampering tests - cross-signing rejection (403), re-signing conflict (409), premature download rejection (400), and non-lead creation blocking (403)")
    void testAdv05_ProjectContributorAgreementTamperingAndSignatureSecurity() throws Exception {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        RegisteredUser gdgLead = registerUser("gdg.pca." + uid + "@gdgoc.edu", "GDG Lead", Set.of(RoleName.GDG_LEAD));
        RegisteredUser hrLead = registerUser("hr.pca." + uid + "@gdgoc.edu", "HR Lead", Set.of(RoleName.HR_LEAD));
        RegisteredUser regularUser = registerUser("reg.pca." + uid + "@gdgoc.edu", "Regular Member", Set.of(RoleName.TEAM_MEMBER));
        RegisteredUser signerA = registerUser("signerA." + uid + "@gdgoc.edu", "Signer Alpha", Set.of(RoleName.TEAM_MEMBER));
        RegisteredUser signerB = registerUser("signerB." + uid + "@gdgoc.edu", "Signer Beta", Set.of(RoleName.TEAM_MEMBER));

        CreateAgreementRequestDTO pcaReq = new CreateAgreementRequestDTO(
                "v2026.adv." + uid, "Adversarial Test PCA Agreement", "<p>Terms of contribution</p>"
        );

        // 5.1 Non-lead attempting to create PCA -> 403 Forbidden
        mockMvc.perform(post("/api/v1/agreements")
                        .header("Authorization", "Bearer " + regularUser.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pcaReq)))
                .andExpect(status().isForbidden());

        // 5.2 Valid creation by HR_LEAD
        MvcResult pcaRes = mockMvc.perform(post("/api/v1/agreements")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pcaReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long agreementId = objectMapper.readTree(pcaRes.getResponse().getContentAsString()).get("id").asLong();

        // 5.3 Duplicate version string -> 409 Conflict
        mockMvc.perform(post("/api/v1/agreements")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pcaReq)))
                .andExpect(status().isConflict());

        // 5.4 Issue PCA to signerA and signerB
        IssueAgreementRequestDTO issueReq = new IssueAgreementRequestDTO(List.of(signerA.id(), signerB.id()));
        MvcResult issueRes = mockMvc.perform(post("/api/v1/agreements/" + agreementId + "/issue")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(issueReq)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode sigList = objectMapper.readTree(issueRes.getResponse().getContentAsString());
        assertThat(sigList.size()).isEqualTo(2);

        long sigIdA = -1;
        long sigIdB = -1;
        for (JsonNode sn : sigList) {
            if (sn.get("memberId").asLong() == signerA.id()) {
                sigIdA = sn.get("id").asLong();
            } else if (sn.get("memberId").asLong() == signerB.id()) {
                sigIdB = sn.get("id").asLong();
            }
        }
        assertThat(sigIdA).isPositive();
        assertThat(sigIdB).isPositive();

        // 5.5 Premature download: Downloading unsigned PCA PDF -> 400 Bad Request
        mockMvc.perform(get("/api/v1/agreements/signatures/" + sigIdA + "/download")
                        .header("Authorization", "Bearer " + signerA.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not been signed yet")));

        // 5.6 Cross-signing tampering: Signer B attempts to sign Signer A's PCA signature -> 403 Forbidden
        SignAgreementRequestDTO forgedSign = new SignAgreementRequestDTO("Digitally signed by Forger");
        mockMvc.perform(post("/api/v1/agreements/signatures/" + sigIdA + "/sign")
                        .header("Authorization", "Bearer " + signerB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(forgedSign)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Only the assigned member")));

        // 5.7 Signer A successfully signs their own PCA
        SignAgreementRequestDTO validSign = new SignAgreementRequestDTO("Digitally signed by Signer Alpha");
        mockMvc.perform(post("/api/v1/agreements/signatures/" + sigIdA + "/sign")
                        .header("Authorization", "Bearer " + signerA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validSign)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"))
                .andExpect(jsonPath("$.signatureText").value("Digitally signed by Signer Alpha"));

        // 5.8 Re-signing attack: Signer A attempts to sign already signed PCA -> 409 Conflict
        mockMvc.perform(post("/api/v1/agreements/signatures/" + sigIdA + "/sign")
                        .header("Authorization", "Bearer " + signerA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validSign)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already been signed")));

        // 5.9 Unauthorized download: Signer B attempts to download Signer A's signed PCA PDF -> 403 Forbidden
        mockMvc.perform(get("/api/v1/agreements/signatures/" + sigIdA + "/download")
                        .header("Authorization", "Bearer " + signerB.token()))
                .andExpect(status().isForbidden());

        // 5.10 Authorized download: Signer A downloads their signed PCA PDF -> 200 OK, application/pdf
        MvcResult pdfRes = mockMvc.perform(get("/api/v1/agreements/signatures/" + sigIdA + "/download")
                        .header("Authorization", "Bearer " + signerA.token()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();
        assertThat(new String(pdfRes.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");
    }

    // =========================================================================
    // 6. Cross-Team Permission Grant Lifecycle and Rejection State Machine
    // =========================================================================
    @Test
    @DisplayName("Tier 5 Adversarial 6: Cross-team permission grant starts as PENDING_TEAM_LEAD_APPROVAL, confers no access, and rejection permanently denies access")
    void testAdv06_CrossTeamPermissionGrantLifecycleAndRejectionQuarantine() throws Exception {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        RegisteredUser gdgLead1 = registerUser("gdg1.grant." + uid + "@gdgoc.edu", "GDG Lead 1", Set.of(RoleName.GDG_LEAD));
        RegisteredUser gdgLead2 = registerUser("gdg2.grant." + uid + "@gdgoc.edu", "GDG Lead 2", Set.of(RoleName.GDG_LEAD));
        RegisteredUser gdgLead3 = registerUser("gdg3.grant." + uid + "@gdgoc.edu", "GDG Lead 3", Set.of(RoleName.GDG_LEAD));
        RegisteredUser teamLeadA = registerUser("tla.grant." + uid + "@gdgoc.edu", "Lead Team A", Set.of(RoleName.TEAM_LEAD));
        RegisteredUser teamLeadB = registerUser("tlb.grant." + uid + "@gdgoc.edu", "Lead Team B", Set.of(RoleName.TEAM_LEAD));
        RegisteredUser grantee = registerUser("grantee." + uid + "@gdgoc.edu", "Grantee Member", Set.of(RoleName.TEAM_MEMBER));

        Long teamA = createTeamVia3LeadConsensus("Team Alpha " + uid, null, gdgLead1, gdgLead2, gdgLead3);
        Long teamB = createTeamVia3LeadConsensus("Team Beta " + uid, null, gdgLead1, gdgLead2, gdgLead3);

        // Assign teamLeadA to Team Alpha, teamLeadB to Team Beta, and grantee to Team Alpha
        mockMvc.perform(post("/api/v1/teams/" + teamA + "/members")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignTeamMemberRequestDTO(teamLeadA.id(), TeamRole.TEAM_LEAD))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/teams/" + teamB + "/members")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignTeamMemberRequestDTO(teamLeadB.id(), TeamRole.TEAM_LEAD))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/teams/" + teamA + "/members")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignTeamMemberRequestDTO(grantee.id(), TeamRole.MEMBER))))
                .andExpect(status().isCreated());

        // Team Lead A creates a cross-team grant targeting Team Beta
        CreatePermissionGrantRequestDTO crossGrant = new CreatePermissionGrantRequestDTO(
                grantee.id(), null, PermissionCode.ASSIGN_TASK_CROSS_TEAM, teamA, null, teamB,
                Instant.now().minusSeconds(60), Instant.now().plus(7, ChronoUnit.DAYS), "Cross team access to Team Beta"
        );
        MvcResult grantRes = mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crossGrant)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_TEAM_LEAD_APPROVAL"))
                .andExpect(jsonPath("$.currentlyActive").value(false))
                .andReturn();
        long grantId = objectMapper.readTree(grantRes.getResponse().getContentAsString()).get("id").asLong();

        // Unauthorized member attempts to approve grant -> 403 Forbidden
        mockMvc.perform(post("/api/v1/permissions/grants/" + grantId + "/approve")
                        .header("Authorization", "Bearer " + grantee.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveGrantRequestDTO(true, "Self approve"))))
                .andExpect(status().isForbidden());

        // Team Lead of unrelated team attempts to approve -> 403 Forbidden
        mockMvc.perform(post("/api/v1/permissions/grants/" + grantId + "/approve")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveGrantRequestDTO(true, "Grantor approving"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Only the target team's TEAM_LEAD")));

        // Target Team Lead B rejects the grant
        mockMvc.perform(post("/api/v1/permissions/grants/" + grantId + "/approve")
                        .header("Authorization", "Bearer " + teamLeadB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveGrantRequestDTO(false, "Denied: insufficient seniority"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.currentlyActive").value(false));

        // Grantee does not have permission in DB
        var grantInDb = permissionGrantRepository.findById(grantId).orElseThrow();
        assertThat(grantInDb.getStatus()).isEqualTo(GrantStatus.REJECTED);
    }

    // =========================================================================
    // 7. Stateless JWT Stale Token Invalidation Post-Termination
    // =========================================================================
    @Test
    @DisplayName("Tier 5 Adversarial 7: Cryptographically valid JWT immediately loses operational clearance upon member termination")
    void testAdv07_StatelessJwtTokenPreTerminationInvalidation() throws Exception {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        RegisteredUser hrLead = registerUser("hr.term." + uid + "@gdgoc.edu", "HR Lead", Set.of(RoleName.HR_LEAD));
        RegisteredUser gdgLead = registerUser("gdg.term." + uid + "@gdgoc.edu", "GDG Lead", Set.of(RoleName.GDG_LEAD));
        RegisteredUser member = registerUser("victim.term." + uid + "@gdgoc.edu", "Victim Member", Set.of(RoleName.TEAM_MEMBER));

        String cachedToken = member.token();

        // 1. Token works while ACTIVE
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + cachedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/teams")
                        .header("Authorization", "Bearer " + cachedToken))
                .andExpect(status().isOk());

        // 2. Terminate the member
        Instant termTime = Instant.now().minus(1, ChronoUnit.DAYS);
        mockMvc.perform(post("/api/v1/members/" + member.id() + "/terminate")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TerminateMemberRequestDTO("Violation of conduct", termTime))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"));

        // 3. Using the SAME cached token issued BEFORE termination:
        // Operational endpoint GET /api/v1/teams is immediately BLOCKED (403 Forbidden)
        mockMvc.perform(get("/api/v1/teams")
                        .header("Authorization", "Bearer " + cachedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("All operational permissions are revoked")));

        // Operational mutation endpoint POST /api/v1/profile/me/bio-data-requests is BLOCKED (403 Forbidden)
        mockMvc.perform(post("/api/v1/profile/me/bio-data-requests")
                        .header("Authorization", "Bearer " + cachedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Trying to edit after termination\"}"))
                .andExpect(status().isForbidden());

        // Operational mutation endpoint POST /api/v1/profile/me/team-change-requests is BLOCKED (403 Forbidden)
        mockMvc.perform(post("/api/v1/profile/me/team-change-requests")
                        .header("Authorization", "Bearer " + cachedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentTeamId\":1,\"targetTeamId\":2,\"reason\":\"Transfer\"}"))
                .andExpect(status().isForbidden());

        // 4. Allowed profile endpoint returns TERMINATED status and cleared roles
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + cachedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"))
                .andExpect(jsonPath("$.roles.length()").value(0));

        mockMvc.perform(get("/api/v1/profile/me")
                        .header("Authorization", "Bearer " + cachedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"))
                .andExpect(jsonPath("$.roles.length()").value(0))
                .andExpect(jsonPath("$.letterDownloadWindowActive").value(true));
    }

    // =========================================================================
    // 8. Invalid Self-Service Change Requests Boundary Attacks
    // =========================================================================
    @Test
    @DisplayName("Tier 5 Adversarial 8: Boundary attacks on self-service requests - non-existent target team (404), deleted target team (400), invalid academic year (400)")
    void testAdv08_InvalidSelfServiceChangeRequestsBoundaryAttacks() throws Exception {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        RegisteredUser gdgLead1 = registerUser("gdg1.bnd." + uid + "@gdgoc.edu", "GDG Lead 1", Set.of(RoleName.GDG_LEAD));
        RegisteredUser gdgLead2 = registerUser("gdg2.bnd." + uid + "@gdgoc.edu", "GDG Lead 2", Set.of(RoleName.GDG_LEAD));
        RegisteredUser gdgLead3 = registerUser("gdg3.bnd." + uid + "@gdgoc.edu", "GDG Lead 3", Set.of(RoleName.GDG_LEAD));
        RegisteredUser member = registerUser("member.bnd." + uid + "@gdgoc.edu", "Boundary Member", Set.of(RoleName.TEAM_MEMBER));
        RegisteredUser otherUser = registerUser("other.bnd." + uid + "@gdgoc.edu", "Other Member", Set.of(RoleName.TEAM_MEMBER));

        Long activeTeam = createTeamVia3LeadConsensus("Active Team " + uid, null, gdgLead1, gdgLead2, gdgLead3);
        Long deletedTeam = createTeamVia3LeadConsensus("Soon Deleted Team " + uid, null, gdgLead1, gdgLead2, gdgLead3);

        // Delete the second team
        CreateConsensusRequestDTO delReq = new CreateConsensusRequestDTO(
                ConsensusActionType.DELETE_TEAM, deletedTeam, null, "Delete for boundary test", null
        );
        MvcResult delRes = mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(delReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long delId = objectMapper.readTree(delRes.getResponse().getContentAsString()).get("id").asLong();
        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + delId + "/vote")
                        .header("Authorization", "Bearer " + gdgLead2.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Approve del"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + delId + "/vote")
                        .header("Authorization", "Bearer " + gdgLead3.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Approve del"))))
                .andExpect(status().isOk());

        // 8.1 Team change to non-existent targetTeamId (999999L) -> 404 Not Found
        mockMvc.perform(post("/api/v1/profile/me/team-change-requests")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentTeamId\":" + activeTeam + ",\"targetTeamId\":999999,\"reason\":\"Moving to nonexistent\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Target team not found")));

        // 8.2 Team change to deleted team -> 400 Bad Request
        mockMvc.perform(post("/api/v1/profile/me/team-change-requests")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentTeamId\":" + activeTeam + ",\"targetTeamId\":" + deletedTeam + ",\"reason\":\"Moving to deleted team\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Cannot request transfer to a deleted team")));

        // 8.3 Bio-data update with invalid academic year: academicYear = 0 -> 400 Bad Request
        mockMvc.perform(post("/api/v1/profile/me/bio-data-requests")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Test\",\"academicYear\":0,\"reason\":\"Invalid year\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("academicYear must be between 1 and 6")));

        // 8.4 Bio-data update with invalid academic year: academicYear = 8 -> 400 Bad Request
        mockMvc.perform(post("/api/v1/profile/me/bio-data-requests")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Test\",\"academicYear\":8,\"reason\":\"Invalid year\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("academicYear must be between 1 and 6")));

        // 8.5 Bio-data update with blank fullName -> 400 Bad Request
        mockMvc.perform(post("/api/v1/profile/me/bio-data-requests")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"   \",\"academicYear\":3,\"reason\":\"Blank name\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("fullName cannot be blank")));

        // 8.6 Valid change request created
        MvcResult validReq = mockMvc.perform(post("/api/v1/profile/me/bio-data-requests")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Valid Name\",\"academicYear\":4,\"reason\":\"Valid reason\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long changeReqId = objectMapper.readTree(validReq.getResponse().getContentAsString()).get("id").asLong();

        // 8.7 Other unauthorized regular member cannot view this change request -> 403 Forbidden
        mockMvc.perform(get("/api/v1/change-requests/" + changeReqId)
                        .header("Authorization", "Bearer " + otherUser.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Not authorized to view this change request")));

        // Owner CAN view their own change request -> 200 OK
        mockMvc.perform(get("/api/v1/change-requests/" + changeReqId)
                        .header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(changeReqId));
    }
}
