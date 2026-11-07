package com.gdgoc.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdgoc.dashboard.auth.AuthDtos.RegisterRequestDTO;
import com.gdgoc.dashboard.member.Member;
import com.gdgoc.dashboard.member.MemberRepository;
import com.gdgoc.dashboard.member.MemberStatus;
import com.gdgoc.dashboard.member.RoleName;
import com.gdgoc.dashboard.permission.PermissionCode;
import com.gdgoc.dashboard.permission.PermissionDtos.CreatePermissionGrantRequestDTO;
import com.gdgoc.dashboard.task.TaskAssignmentType;
import com.gdgoc.dashboard.task.TaskDtos.*;
import com.gdgoc.dashboard.task.TaskPriority;
import com.gdgoc.dashboard.task.TaskStatus;
import com.gdgoc.dashboard.team.ConsensusActionType;
import com.gdgoc.dashboard.team.TeamDtos.*;
import com.gdgoc.dashboard.team.TeamRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Adversarial Stress-Testing and Boundary Condition Probing Suite
 * for Milestone 2: Task Assigning & Tracking Core (Features F14–F19).
 *
 * Covers:
 * 1. Strict Assignee-Only Progress Update (Sheet 2 Row 2): Assignor, team lead, and even GDG Lead MUST receive 403 Forbidden if not assignee.
 * 2. Progress update rejection when task is in PENDING_TEAM_LEAD_APPROVAL, REJECTED, or CANCELLED status (409 Conflict).
 * 3. Inverted, null, or past deadlines on task creation (400 Bad Request).
 * 4. Progress percentage boundary values (<0 or >100 return 400) and auto-completion when reaching 100%.
 * 5. Non-GDG-Lead trying to assign GDG_LEAD_TO_TEAM_LEAD (403), or assigning to non-team-lead (400).
 * 6. Regular team member without grant trying to assign tasks (403). Cross-team assignment by member with grant starting in PENDING_TEAM_LEAD_APPROVAL.
 * 7. Unauthorized user trying to approve cross-team task (403; only target team lead or GDG Lead can approve; source lead self-approval prevented).
 * 8. Overdue calculation accuracy when deadline is in the past and task is incomplete vs complete.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TaskAdversarialIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private com.gdgoc.dashboard.task.TaskRepository taskRepository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private record TestUser(Long id, String token, String email) {}

    private TestUser gdgLead1;
    private TestUser gdgLead2;
    private TestUser gdgLead3;
    private TestUser teamLeadA;
    private TestUser teamLeadB;
    private TestUser teamLeadC;
    private TestUser teamMemberA;
    private TestUser teamMemberB;
    private TestUser outsiderMember;
    private Long teamAId;
    private Long teamBId;
    private Long teamCId;

    private static int seq = 8000;

    private TestUser register(String prefix, Set<RoleName> roles) throws Exception {
        String email = prefix + (seq++) + "@rmkec.ac.in";
        RegisterRequestDTO req = new RegisterRequestDTO(
                email, "Password123!", "User " + prefix, "22CS" + (seq % 1000), "CSE", 3, "+91-9876543210", roles
        );
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        return new TestUser(
                node.get("member").get("id").asLong(),
                node.get("accessToken").asText(),
                email
        );
    }

    private Long createTeamConsensus(String name, TestUser l1, TestUser l2, TestUser l3) throws Exception {
        CreateConsensusRequestDTO req = new CreateConsensusRequestDTO(
                ConsensusActionType.CREATE_TEAM, null, name, name + " Description", null
        );
        MvcResult r1 = mockMvc.perform(post("/api/v1/teams/consensus-requests")
                        .header("Authorization", "Bearer " + l1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        long reqId = objectMapper.readTree(r1.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + l2.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Approve"))))
                .andExpect(status().isOk());

        MvcResult r3 = mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + l3.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "Execute"))))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(r3.getResponse().getContentAsString()).get("executedTeamId").asLong();
    }

    private void addMemberToTeam(Long teamId, Long memberId, TeamRole teamRole, TestUser actor) throws Exception {
        AssignTeamMemberRequestDTO req = new AssignTeamMemberRequestDTO(memberId, teamRole);
        mockMvc.perform(post("/api/v1/teams/" + teamId + "/members")
                        .header("Authorization", "Bearer " + actor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    @BeforeEach
    void setUp() throws Exception {
        gdgLead1 = register("adv_gdg1_", Set.of(RoleName.GDG_LEAD));
        gdgLead2 = register("adv_gdg2_", Set.of(RoleName.GDG_LEAD));
        gdgLead3 = register("adv_gdg3_", Set.of(RoleName.GDG_LEAD));

        teamLeadA = register("adv_leadA_", Set.of(RoleName.TEAM_LEAD));
        teamLeadB = register("adv_leadB_", Set.of(RoleName.TEAM_LEAD));
        teamLeadC = register("adv_leadC_", Set.of(RoleName.TEAM_LEAD));

        teamMemberA = register("adv_memA_", Set.of(RoleName.TEAM_MEMBER));
        teamMemberB = register("adv_memB_", Set.of(RoleName.TEAM_MEMBER));
        outsiderMember = register("adv_out_", Set.of(RoleName.TEAM_MEMBER));

        teamAId = createTeamConsensus("Alpha Team " + seq, gdgLead1, gdgLead2, gdgLead3);
        teamBId = createTeamConsensus("Beta Team " + seq, gdgLead1, gdgLead2, gdgLead3);
        teamCId = createTeamConsensus("Gamma Team " + seq, gdgLead1, gdgLead2, gdgLead3);

        addMemberToTeam(teamAId, teamLeadA.id(), TeamRole.TEAM_LEAD, gdgLead1);
        addMemberToTeam(teamAId, teamMemberA.id(), TeamRole.MEMBER, gdgLead1);

        addMemberToTeam(teamBId, teamLeadB.id(), TeamRole.TEAM_LEAD, gdgLead1);
        addMemberToTeam(teamBId, teamMemberB.id(), TeamRole.MEMBER, gdgLead1);

        addMemberToTeam(teamCId, teamLeadC.id(), TeamRole.TEAM_LEAD, gdgLead1);
    }

    // =========================================================================
    // 1. Strict Assignee-Only Progress Update (Sheet 2 Row 2)
    // =========================================================================

    @Test
    @DisplayName("Attack 1: Assignor, Team Leads, and GDG Lead MUST be rejected with 403 when updating progress if not assignee")
    void testStrictAssigneeOnlyProgressUpdate() throws Exception {
        // Create an intra-team task from Team Lead A to Team Member A
        CreateTaskRequestDTO createReq = new CreateTaskRequestDTO(
                "Strict Assignee Test Task",
                "Testing unauthorized progress updates",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.HIGH,
                Instant.now().plus(7, ChronoUnit.DAYS)
        );

        MvcResult createRes = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long taskId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        UpdateTaskProgressRequestDTO updateReq = new UpdateTaskProgressRequestDTO(
                30, TaskStatus.IN_PROGRESS, "Unauthorized update attempt", null
        );
        String updatePayload = objectMapper.writeValueAsString(updateReq);

        // Sub-attack 1.1: Assignor (Team Lead A) attempts update -> 403 Forbidden
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("Only the assigned member can update task progress")));

        // Sub-attack 1.2: GDG Lead (supreme role) attempts update -> 403 Forbidden (No role bypass allowed!)
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("Only the assigned member can update task progress")));

        // Sub-attack 1.3: External Team Lead (Team Lead B) attempts update -> 403 Forbidden
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamLeadB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isForbidden());

        // Sub-attack 1.4: Peer team member (outsider) attempts update -> 403 Forbidden
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + outsiderMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isForbidden());

        // Sub-attack 1.5: Unauthenticated request -> 401 Unauthorized
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isUnauthorized());

        // Legitimate update by Assignee (teamMemberA) -> 200 OK
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressPercentage").value(30))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    // =========================================================================
    // 2. Progress Update on Tasks in PENDING_APPROVAL, REJECTED, or CANCELLED Status
    // =========================================================================

    @Test
    @DisplayName("Attack 2: Progress update on tasks pending approval or rejected must be rejected (409 Conflict)")
    void testProgressUpdateOnPendingOrRejectedTasks_Rejected() throws Exception {
        // Create cross-team task (starts in PENDING_TEAM_LEAD_APPROVAL)
        CreateTaskRequestDTO crossReq = new CreateTaskRequestDTO(
                "Cross-Team Unapproved Task",
                "Assigning across team boundary",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberB.id(),
                teamAId,
                teamBId,
                null,
                TaskPriority.MEDIUM,
                Instant.now().plus(5, ChronoUnit.DAYS)
        );

        MvcResult createRes = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crossReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_TEAM_LEAD_APPROVAL"))
                .andReturn();
        long taskId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        UpdateTaskProgressRequestDTO progressReq = new UpdateTaskProgressRequestDTO(
                20, TaskStatus.IN_PROGRESS, "Premature progress update", null
        );

        // Sub-attack 2.1: Assignee attempts progress update while task is PENDING_TEAM_LEAD_APPROVAL -> 409 Conflict
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(progressReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("pending Team Lead approval")));

        // Reject the cross-team task
        ApproveCrossTeamTaskRequestDTO rejectReq = new ApproveCrossTeamTaskRequestDTO(false, "Rejected due to capacity");
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + teamLeadB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rejectReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // Sub-attack 2.2: Assignee attempts progress update while task is REJECTED -> 409 Conflict
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(progressReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("rejected task")));

        // Sub-attack 2.3: Task in CANCELLED status -> 409 Conflict
        taskRepository.findById(taskId).ifPresent(t -> {
            t.setStatus(TaskStatus.CANCELLED);
            taskRepository.saveAndFlush(t);
        });
        entityManager.clear();
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(progressReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("cancelled task")));
    }

    // =========================================================================
    // 3. Inverted, Null, or Past Deadlines on Task Creation
    // =========================================================================

    @Test
    @DisplayName("Attack 3: Task creation with inverted, null, past, or ancient deadlines must return 400 Bad Request")
    void testDeadlinesBoundaryOnCreation() throws Exception {
        // Sub-attack 3.1: Explicit null deadline
        String nullDeadlineJson = """
                {
                    "title": "Null Deadline Task",
                    "assignmentType": "TEAM_LEAD_TO_TEAM",
                    "assigneeId": %d,
                    "sourceTeamId": %d,
                    "targetTeamId": %d,
                    "priority": "MEDIUM",
                    "deadline": null
                }
                """.formatted(teamMemberA.id(), teamAId, teamAId);

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nullDeadlineJson))
                .andExpect(status().isBadRequest());

        // Sub-attack 3.2: Missing deadline field entirely
        String missingDeadlineJson = """
                {
                    "title": "Missing Deadline Task",
                    "assignmentType": "TEAM_LEAD_TO_TEAM",
                    "assigneeId": %d,
                    "sourceTeamId": %d,
                    "targetTeamId": %d,
                    "priority": "MEDIUM"
                }
                """.formatted(teamMemberA.id(), teamAId, teamAId);

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingDeadlineJson))
                .andExpect(status().isBadRequest());

        // Sub-attack 3.3: Inverted/Past deadline (yesterday)
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        CreateTaskRequestDTO pastReq = new CreateTaskRequestDTO(
                "Past Deadline Task",
                "Past deadline",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.MEDIUM,
                past
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pastReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("deadline must be non-null and in the future")));

        // Sub-attack 3.4: Ancient inverted deadline (Unix Epoch 1970-01-01)
        CreateTaskRequestDTO epochReq = new CreateTaskRequestDTO(
                "Epoch Deadline Task",
                "Unix epoch",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.LOW,
                Instant.EPOCH
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(epochReq)))
                .andExpect(status().isBadRequest());

        // Sub-attack 3.5: Deadline 10 seconds in the past
        CreateTaskRequestDTO tenSecPastReq = new CreateTaskRequestDTO(
                "Ten Seconds Past Task",
                "Near past deadline",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.LOW,
                Instant.now().minus(10, ChronoUnit.SECONDS)
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tenSecPastReq)))
                .andExpect(status().isBadRequest());

        // Sub-attack 3.6: Malformed deadline string
        String malformedDateJson = """
                {
                    "title": "Malformed Date Task",
                    "assignmentType": "TEAM_LEAD_TO_TEAM",
                    "assigneeId": %d,
                    "sourceTeamId": %d,
                    "targetTeamId": %d,
                    "deadline": "not-a-valid-iso-instant"
                }
                """.formatted(teamMemberA.id(), teamAId, teamAId);
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedDateJson))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // 4. Progress Percentage Boundaries and Auto-Completion
    // =========================================================================

    @Test
    @DisplayName("Attack 4: Progress percentage <0 or >100 must return 400; reaching 100% must auto-complete task")
    void testProgressBoundariesAndAutoCompletion() throws Exception {
        CreateTaskRequestDTO createReq = new CreateTaskRequestDTO(
                "Boundary & Auto-Complete Task",
                "Testing boundary values and 100% auto completion",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.MEDIUM,
                Instant.now().plus(7, ChronoUnit.DAYS)
        );

        MvcResult createRes = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long taskId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // Sub-attack 4.1: progressPercentage = -1 -> 400
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTaskProgressRequestDTO(-1, null, null, null))))
                .andExpect(status().isBadRequest());

        // Sub-attack 4.2: progressPercentage = -100 -> 400
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTaskProgressRequestDTO(-100, null, null, null))))
                .andExpect(status().isBadRequest());

        // Sub-attack 4.3: progressPercentage = 101 -> 400
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTaskProgressRequestDTO(101, null, null, null))))
                .andExpect(status().isBadRequest());

        // Sub-attack 4.4: progressPercentage = 9999 -> 400
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTaskProgressRequestDTO(9999, null, null, null))))
                .andExpect(status().isBadRequest());

        // Sub-attack 4.5: Valid boundary 0% -> 200 OK
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTaskProgressRequestDTO(0, TaskStatus.ASSIGNED, "Just initialized", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressPercentage").value(0))
                .andExpect(jsonPath("$.status").value("ASSIGNED"));

        // Sub-attack 4.6: Adversarial Auto-Completion Probe:
        // Client maliciously or erroneously sends progressPercentage = 100 with status = IN_PROGRESS.
        // The service MUST override status to COMPLETED and set completedAt!
        UpdateTaskProgressRequestDTO adversarial100 = new UpdateTaskProgressRequestDTO(
                100, TaskStatus.IN_PROGRESS, "Reaching 100% should force COMPLETED status", null
        );
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adversarial100)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressPercentage").value(100))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());

        // Sub-attack 4.7: Tracking verification of completed task
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + teamMemberA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.progressPercentage").value(100))
                .andExpect(jsonPath("$.overdue").value(false));
    }

    // =========================================================================
    // 5. GDG_LEAD_TO_TEAM_LEAD Role and Assignee Type Validation
    // =========================================================================

    @Test
    @DisplayName("Attack 5: Non-GDG-Lead cannot assign GDG_LEAD_TO_TEAM_LEAD (403); assigning to non-team-lead returns 400")
    void testGdgLeadToTeamLeadValidation() throws Exception {
        Instant deadline = Instant.now().plus(14, ChronoUnit.DAYS);

        // Sub-attack 5.1: Team Lead A tries to use GDG_LEAD_TO_TEAM_LEAD -> 403 Forbidden
        CreateTaskRequestDTO leadAttempt = new CreateTaskRequestDTO(
                "Lead Usurpation Task",
                "Team lead impersonating GDG lead",
                TaskAssignmentType.GDG_LEAD_TO_TEAM_LEAD,
                teamLeadB.id(),
                teamAId,
                teamBId,
                null,
                TaskPriority.HIGH,
                deadline
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(leadAttempt)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("Only GDG Leads can assign tasks of type GDG_LEAD_TO_TEAM_LEAD")));

        // Sub-attack 5.2: Regular Team Member tries to use GDG_LEAD_TO_TEAM_LEAD -> 403 Forbidden
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(leadAttempt)))
                .andExpect(status().isForbidden());

        // Sub-attack 5.3: GDG Lead tries to assign GDG_LEAD_TO_TEAM_LEAD to a regular member (not a lead) -> 400 Bad Request
        CreateTaskRequestDTO nonLeadAssigneeReq = new CreateTaskRequestDTO(
                "Invalid Target Lead Task",
                "Target is regular member",
                TaskAssignmentType.GDG_LEAD_TO_TEAM_LEAD,
                teamMemberA.id(), // Not a team lead!
                teamAId,
                teamAId,
                null,
                TaskPriority.HIGH,
                deadline
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nonLeadAssigneeReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Assignee does not hold TEAM_LEAD role")));

        // Legitimate GDG_LEAD_TO_TEAM_LEAD assignment to actual Team Lead -> 201 Created
        CreateTaskRequestDTO validGdgTask = new CreateTaskRequestDTO(
                "Valid Strategic Task",
                "Strategic mandate to lead",
                TaskAssignmentType.GDG_LEAD_TO_TEAM_LEAD,
                teamLeadA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.CRITICAL,
                deadline
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validGdgTask)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.assignmentType").value("GDG_LEAD_TO_TEAM_LEAD"));
    }

    // =========================================================================
    // 6. Regular Member Permissions and Cross-Team Delegation
    // =========================================================================

    @Test
    @DisplayName("Attack 6: Regular member without grant is rejected with 403; cross-team with grant starts PENDING_APPROVAL")
    void testMemberTaskAssignmentPermissions() throws Exception {
        Instant deadline = Instant.now().plus(10, ChronoUnit.DAYS);

        // Sub-attack 6.1: Regular member without any grant attempts MEMBER_INTRA_TEAM -> 403 Forbidden
        CreateTaskRequestDTO intraNoGrant = new CreateTaskRequestDTO(
                "Unpermitted Intra Task",
                "No grant",
                TaskAssignmentType.MEMBER_INTRA_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.LOW,
                deadline
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(intraNoGrant)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("ASSIGN_TASK_INTRA_TEAM grant")));

        // Sub-attack 6.2: Regular member without any grant attempts MEMBER_CROSS_TEAM -> 403 Forbidden
        CreateTaskRequestDTO crossNoGrant = new CreateTaskRequestDTO(
                "Unpermitted Cross Task",
                "No cross grant",
                TaskAssignmentType.MEMBER_CROSS_TEAM,
                teamMemberB.id(),
                teamAId,
                teamBId,
                null,
                TaskPriority.LOW,
                deadline
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crossNoGrant)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("ASSIGN_TASK_CROSS_TEAM grant")));

        // Sub-attack 6.3: Regular member without lead role attempts TEAM_LEAD_TO_TEAM -> 403 Forbidden
        CreateTaskRequestDTO leadToTeamAttempt = new CreateTaskRequestDTO(
                "Member Impersonating Lead",
                "Member tries TEAM_LEAD_TO_TEAM",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.LOW,
                deadline
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(leadToTeamAttempt)))
                .andExpect(status().isForbidden());

        // Grant ASSIGN_TASK_CROSS_TEAM to teamMemberA
        CreatePermissionGrantRequestDTO crossGrant = new CreatePermissionGrantRequestDTO(
                teamMemberA.id(),
                null,
                PermissionCode.ASSIGN_TASK_CROSS_TEAM,
                teamAId,
                null,
                teamBId,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(7, ChronoUnit.DAYS),
                "Allow cross team delegation"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crossGrant)))
                .andExpect(status().isCreated());

        // Sub-attack 6.4: Member with cross grant creates cross-team task -> MUST start in PENDING_TEAM_LEAD_APPROVAL
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crossNoGrant)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_TEAM_LEAD_APPROVAL"))
                .andExpect(jsonPath("$.assignmentType").value("MEMBER_CROSS_TEAM"));
    }

    // =========================================================================
    // 7. Cross-Team Task Approval Authorization & Anti-Self-Approval
    // =========================================================================

    @Test
    @DisplayName("Attack 7: Only target Team Lead or GDG Lead can approve cross-team tasks; source lead self-approval rejected")
    void testCrossTeamApprovalAuthorization() throws Exception {
        // Create cross-team task from Team A to Member B in Team B
        CreateTaskRequestDTO crossReq = new CreateTaskRequestDTO(
                "Approval Security Test Task",
                "Testing approval clearance",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberB.id(),
                teamAId,
                teamBId,
                null,
                TaskPriority.HIGH,
                Instant.now().plus(10, ChronoUnit.DAYS)
        );

        MvcResult createRes = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crossReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_TEAM_LEAD_APPROVAL"))
                .andReturn();
        long taskId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        ApproveCrossTeamTaskRequestDTO approveReq = new ApproveCrossTeamTaskRequestDTO(true, "Approve task");
        String approvePayload = objectMapper.writeValueAsString(approveReq);

        // Sub-attack 7.1: Source Team Lead (Team Lead A) attempts self-approval -> 403 Forbidden!
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("Source Team Lead cannot self-approve")));

        // Sub-attack 7.2: Unrelated Team Lead (Team Lead C) attempts approval -> 403 Forbidden!
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + teamLeadC.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("Only target Team Lead or GDG Lead can approve")));

        // Sub-attack 7.3: Target Team Member (assignee teamMemberB) attempts approval -> 403 Forbidden!
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + teamMemberB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isForbidden());

        // Sub-attack 7.4: Unauthenticated attempt -> 401 Unauthorized
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve-cross-team")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isUnauthorized());

        // Legitimate approval by Target Team Lead (Team Lead B) -> 200 OK, transitions to ASSIGNED
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + teamLeadB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.crossTeamApprovedById").value(teamLeadB.id()));

        // Sub-attack 7.5: Attempting to approve an already approved task -> 409 Conflict
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + teamLeadB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("Task is not pending team lead approval")));
    }

    // =========================================================================
    // 8. Overdue Calculation Accuracy: Incomplete vs Complete Tasks
    // =========================================================================

    @Test
    @DisplayName("Attack 8: Overdue flag accuracy across lifecycle (ASSIGNED/IN_PROGRESS overdue vs COMPLETED/REJECTED not overdue)")
    void testOverdueCalculationAccuracy() throws Exception {
        // 1. Create a task with valid future deadline
        CreateTaskRequestDTO createReq = new CreateTaskRequestDTO(
                "Overdue Audit Task",
                "Testing temporal overdue status transitions",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.HIGH,
                Instant.now().plus(7, ChronoUnit.DAYS)
        );

        MvcResult createRes = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long taskId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // Initially with future deadline: overdue == false
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + teamMemberA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(false));

        // 2. Adversarial temporal simulation: Move deadline to 3 hours in the past
        Instant pastDeadline = Instant.now().minus(3, ChronoUnit.HOURS);
        taskRepository.findById(taskId).ifPresent(t -> {
            t.setDeadline(pastDeadline);
            taskRepository.saveAndFlush(t);
        });
        entityManager.clear();

        // Sub-check 8.1: Incomplete task (ASSIGNED) with past deadline MUST be overdue == true
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + teamMemberA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(true));

        // Sub-check 8.2: Filter GET /api/v1/tasks?overdueOnly=true MUST include this task
        mockMvc.perform(get("/api/v1/tasks?overdueOnly=true")
                        .header("Authorization", "Bearer " + teamLeadA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem((int) taskId)));

        // Sub-check 8.3: Team stats MUST count this overdue task
        mockMvc.perform(get("/api/v1/teams/" + teamAId + "/stats")
                        .header("Authorization", "Bearer " + teamLeadA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdueTasks", greaterThanOrEqualTo(1)));

        // Sub-check 8.4: Member profile MUST count this overdue task
        mockMvc.perform(get("/api/v1/members/" + teamMemberA.id())
                        .header("Authorization", "Bearer " + gdgLead1.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskSummary.overdueTasks", greaterThanOrEqualTo(1)));

        // 3. Move task to IN_PROGRESS (50%)
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTaskProgressRequestDTO(50, TaskStatus.IN_PROGRESS, "Halfway done", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // Still overdue while IN_PROGRESS
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + teamMemberA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(true));

        // 4. Assignee finishes task (100% -> COMPLETED)
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTaskProgressRequestDTO(100, TaskStatus.COMPLETED, "Done!", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // Sub-check 8.5: COMPLETED task with past deadline MUST NOT be overdue (overdue == false)
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + teamMemberA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(false));

        // Sub-check 8.6: Filter GET /api/v1/tasks?overdueOnly=true MUST NOT include completed task
        mockMvc.perform(get("/api/v1/tasks?overdueOnly=true")
                        .header("Authorization", "Bearer " + teamLeadA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem((int) taskId))));

        // Sub-check 8.7: Team stats completedTasks increases, overdueTasks decrements
        mockMvc.perform(get("/api/v1/teams/" + teamAId + "/stats")
                        .header("Authorization", "Bearer " + teamLeadA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedTasks", greaterThanOrEqualTo(1)));

        // Sub-check 8.8: REJECTED status with past deadline is also NOT overdue
        taskRepository.findById(taskId).ifPresent(t -> {
            t.setStatus(TaskStatus.REJECTED);
            taskRepository.saveAndFlush(t);
        });
        entityManager.clear();
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + teamMemberA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(false));

        // Sub-check 8.9: CANCELLED status with past deadline is also NOT overdue
        taskRepository.findById(taskId).ifPresent(t -> {
            t.setStatus(TaskStatus.CANCELLED);
            taskRepository.saveAndFlush(t);
        });
        entityManager.clear();
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + teamMemberA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(false));
    }

    // =========================================================================
    // 9. Unauthorized Task Tracking Access Probing
    // =========================================================================

    @Test
    @DisplayName("Attack 9: Unauthorized member cannot view tracking details for a task outside their scope (403 Forbidden)")
    void testUnauthorizedTrackingAccess_Forbidden() throws Exception {
        CreateTaskRequestDTO createReq = new CreateTaskRequestDTO(
                "Confidential Intra Task",
                "Internal to Team A",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.HIGH,
                Instant.now().plus(7, ChronoUnit.DAYS)
        );

        MvcResult createRes = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long taskId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // Outsider member (from outside Team A) attempts to access tracking -> 403 Forbidden
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + outsiderMember.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("Not authorized to view tracking for task")));

        // Team Lead B (unrelated team) attempts to access tracking -> 403 Forbidden
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + teamLeadB.token()))
                .andExpect(status().isForbidden());

        // Assignee (teamMemberA) can view tracking -> 200 OK
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + teamMemberA.token()))
                .andExpect(status().isOk());

        // Assignor (teamLeadA) can view tracking -> 200 OK
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + teamLeadA.token()))
                .andExpect(status().isOk());

        // GDG Lead can view tracking -> 200 OK
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + gdgLead1.token()))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // 10. Terminated Member Assignment Prevention
    // =========================================================================

    @Test
    @DisplayName("Attack 10: Task assignment to terminated member must be rejected (400 Bad Request)")
    void testAssignTaskToTerminatedMember_Rejected() throws Exception {
        TestUser terminatedMember = register("term_user_", Set.of(RoleName.TEAM_MEMBER));
        addMemberToTeam(teamAId, terminatedMember.id(), TeamRole.MEMBER, gdgLead1);

        // Terminate member
        Member m = memberRepository.findById(terminatedMember.id()).orElseThrow();
        m.setStatus(MemberStatus.TERMINATED);
        memberRepository.save(m);

        CreateTaskRequestDTO req = new CreateTaskRequestDTO(
                "Task for Terminated Member",
                "Should fail",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                terminatedMember.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.LOW,
                Instant.now().plus(5, ChronoUnit.DAYS)
        );

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Cannot assign task to terminated member")));
    }
}
