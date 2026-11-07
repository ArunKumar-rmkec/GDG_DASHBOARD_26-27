package com.gdgoc.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdgoc.dashboard.auth.AuthDtos.RegisterRequestDTO;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TaskAssigningAndTrackingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record TestUser(Long id, String token, String email) {}

    private TestUser gdgLead1;
    private TestUser gdgLead2;
    private TestUser gdgLead3;
    private TestUser teamLeadA;
    private TestUser teamLeadB;
    private TestUser teamMemberA;
    private TestUser teamMemberB;
    private Long teamAId;
    private Long teamBId;

    private static int userSeq = 1;

    private TestUser register(String prefix, Set<RoleName> roles) throws Exception {
        String email = prefix + (userSeq++) + "@rmkec.ac.in";
        RegisterRequestDTO req = new RegisterRequestDTO(
                email, "Password123!", "User " + prefix, "22CS" + (100 + userSeq), "CSE", 3, "+91-9876543210", roles
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
                ConsensusActionType.CREATE_TEAM, null, name, name + " Desc", null
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
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "ok"))))
                .andExpect(status().isOk());

        MvcResult r3 = mockMvc.perform(post("/api/v1/teams/consensus-requests/" + reqId + "/vote")
                        .header("Authorization", "Bearer " + l3.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConsensusVoteRequestDTO(true, "ok"))))
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
        gdgLead1 = register("gdg1_", Set.of(RoleName.GDG_LEAD));
        gdgLead2 = register("gdg2_", Set.of(RoleName.GDG_LEAD));
        gdgLead3 = register("gdg3_", Set.of(RoleName.GDG_LEAD));

        teamLeadA = register("leadA_", Set.of(RoleName.TEAM_LEAD));
        teamLeadB = register("leadB_", Set.of(RoleName.TEAM_LEAD));

        teamMemberA = register("memA_", Set.of(RoleName.TEAM_MEMBER));
        teamMemberB = register("memB_", Set.of(RoleName.TEAM_MEMBER));

        teamAId = createTeamConsensus("Web Team " + (userSeq++), gdgLead1, gdgLead2, gdgLead3);
        teamBId = createTeamConsensus("Cloud Team " + (userSeq++), gdgLead1, gdgLead2, gdgLead3);

        addMemberToTeam(teamAId, teamLeadA.id(), TeamRole.TEAM_LEAD, gdgLead1);
        addMemberToTeam(teamAId, teamMemberA.id(), TeamRole.MEMBER, gdgLead1);

        addMemberToTeam(teamBId, teamLeadB.id(), TeamRole.TEAM_LEAD, gdgLead1);
        addMemberToTeam(teamBId, teamMemberB.id(), TeamRole.MEMBER, gdgLead1);
    }

    // =========================================================================
    // Feature F14: Global Task Rule — Mandatory Scheduled Deadline
    // =========================================================================

    @Test
    void testF14_ValidFutureDeadlineCreatesTaskSuccessfully() throws Exception {
        Instant future = Instant.now().plus(7, ChronoUnit.DAYS);
        CreateTaskRequestDTO req = new CreateTaskRequestDTO(
                "Build Dashboard UI",
                "Implement reactive task cards",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.HIGH,
                future
        );

        MvcResult res = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.progressPercentage").value(0))
                .andReturn();

        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        assertThat(node.get("deadline").asText()).isNotNull();
    }

    @Test
    void testF14_RejectsNullDeadline_400() throws Exception {
        String json = """
                {
                    "title": "Missing Deadline Task",
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
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testF14_RejectsPastDeadline_400() throws Exception {
        Instant past = Instant.now().minus(2, ChronoUnit.DAYS);
        CreateTaskRequestDTO req = new CreateTaskRequestDTO(
                "Past Task",
                "Deadline was two days ago",
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
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testF14_RejectsBlankTitle_400() throws Exception {
        CreateTaskRequestDTO req = new CreateTaskRequestDTO(
                "   ",
                "Blank title task",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(),
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
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Feature F15: Assignee-Only Progress Updates & Tracking
    // =========================================================================

    @Test
    void testF15_AssigneeUpdatesProgressAndTracksAuditLog() throws Exception {
        CreateTaskRequestDTO createReq = new CreateTaskRequestDTO(
                "Task Progress Workflow",
                "Assignee update test",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.MEDIUM,
                Instant.now().plus(10, ChronoUnit.DAYS)
        );

        MvcResult createRes = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long taskId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // 1. Assignee updates to 45% IN_PROGRESS
        UpdateTaskProgressRequestDTO update1 = new UpdateTaskProgressRequestDTO(
                45, TaskStatus.IN_PROGRESS, "Initial work completed", "https://github.com/pr/1"
        );
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressPercentage").value(45))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // 2. Assignee updates to 100% -> Auto-completes
        UpdateTaskProgressRequestDTO update2 = new UpdateTaskProgressRequestDTO(
                100, TaskStatus.COMPLETED, "Completed all acceptance criteria", "https://github.com/pr/1/merged"
        );
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressPercentage").value(100))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());

        // 3. Assignor (Team Lead A) tracks progress
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + teamLeadA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressLogs.length()").value(2))
                .andExpect(jsonPath("$.overdue").value(false));

        // 4. GDG Lead tracks progress
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + gdgLead1.token()))
                .andExpect(status().isOk());
    }

    @Test
    void testF15_NonAssigneeUpdateProgress_Rejected_403() throws Exception {
        CreateTaskRequestDTO createReq = new CreateTaskRequestDTO(
                "Non-Assignee Progress Test",
                "Assignor should be forbidden to update progress",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.MEDIUM,
                Instant.now().plus(10, ChronoUnit.DAYS)
        );

        MvcResult createRes = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long taskId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        UpdateTaskProgressRequestDTO updateReq = new UpdateTaskProgressRequestDTO(
                50, TaskStatus.IN_PROGRESS, "Assignor trying to update", null
        );

        // Assignor (teamLeadA) attempting update -> 403 Forbidden
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isForbidden());

        // Unrelated member (teamMemberB) attempting update -> 403 Forbidden
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isForbidden());
    }

    @Test
    void testF15_InvalidProgressPercentage_Rejected_400() throws Exception {
        CreateTaskRequestDTO createReq = new CreateTaskRequestDTO(
                "Boundary Progress Test",
                "Out of bounds percentage",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.MEDIUM,
                Instant.now().plus(5, ChronoUnit.DAYS)
        );

        MvcResult createRes = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long taskId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // Negative percentage -> 400
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTaskProgressRequestDTO(-10, null, null, null))))
                .andExpect(status().isBadRequest());

        // Greater than 100 -> 400
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTaskProgressRequestDTO(110, null, null, null))))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Feature F16: GDG Leads Assign Tasks to Team Leads
    // =========================================================================

    @Test
    void testF16_GdgLeadAssignsToTeamLead_Success() throws Exception {
        CreateTaskRequestDTO req = new CreateTaskRequestDTO(
                "Sprint Planning Lead Task",
                "Prepare sprint backlog",
                TaskAssignmentType.GDG_LEAD_TO_TEAM_LEAD,
                teamLeadA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.CRITICAL,
                Instant.now().plus(14, ChronoUnit.DAYS)
        );

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.assignmentType").value("GDG_LEAD_TO_TEAM_LEAD"));
    }

    @Test
    void testF16_NonGdgLeadAttemptsGdgLeadToTeamLead_Rejected_403() throws Exception {
        CreateTaskRequestDTO req = new CreateTaskRequestDTO(
                "Unauthorized Task",
                "Non-GDG lead assignor",
                TaskAssignmentType.GDG_LEAD_TO_TEAM_LEAD,
                teamLeadA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.HIGH,
                Instant.now().plus(14, ChronoUnit.DAYS)
        );

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void testF16_GdgLeadAssignsToNonTeamLead_Rejected_400() throws Exception {
        CreateTaskRequestDTO req = new CreateTaskRequestDTO(
                "Invalid Target Lead Task",
                "Target is only a member",
                TaskAssignmentType.GDG_LEAD_TO_TEAM_LEAD,
                teamMemberA.id(), // Not a team lead!
                teamAId,
                teamAId,
                null,
                TaskPriority.HIGH,
                Instant.now().plus(14, ChronoUnit.DAYS)
        );

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Feature F17: Team Leads (and GDG Leads) Assign Tasks to Team
    // =========================================================================

    @Test
    void testF17_TeamLeadAssignsIntraTeamTask_StartsAssigned() throws Exception {
        CreateTaskRequestDTO req = new CreateTaskRequestDTO(
                "Intra Team Task",
                "Assigned directly to own team member",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.MEDIUM,
                Instant.now().plus(8, ChronoUnit.DAYS)
        );

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ASSIGNED"));
    }

    @Test
    void testF17_TeamLeadAssignsCrossTeamTask_StartsPendingApproval() throws Exception {
        CreateTaskRequestDTO req = new CreateTaskRequestDTO(
                "Cross Team Task from Lead A to Member B",
                "Requires Team B Lead approval",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberB.id(),
                teamAId,
                teamBId,
                null,
                TaskPriority.HIGH,
                Instant.now().plus(8, ChronoUnit.DAYS)
        );

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_TEAM_LEAD_APPROVAL"));
    }

    @Test
    void testF17_CompletingTaskUpdatesTeamStats() throws Exception {
        CreateTaskRequestDTO req = new CreateTaskRequestDTO(
                "Team Stats Completion Task",
                "Stats check",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.LOW,
                Instant.now().plus(5, ChronoUnit.DAYS)
        );

        MvcResult createRes = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        long taskId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // Complete the task
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTaskProgressRequestDTO(100, TaskStatus.COMPLETED, "done", null))))
                .andExpect(status().isOk());

        // Check Team A stats
        mockMvc.perform(get("/api/v1/teams/" + teamAId + "/stats")
                        .header("Authorization", "Bearer " + teamLeadA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedTasks").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    // =========================================================================
    // Feature F18: Team Members Assign Tasks Within / Across Teams
    // =========================================================================

    @Test
    void testF18_MemberWithIntraTeamGrant_AssignsTaskSuccessfully() throws Exception {
        // Grant ASSIGN_TASK_INTRA_TEAM to teamMemberA
        CreatePermissionGrantRequestDTO grantReq = new CreatePermissionGrantRequestDTO(
                teamMemberA.id(),
                null,
                PermissionCode.ASSIGN_TASK_INTRA_TEAM,
                teamAId,
                null,
                null,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(5, ChronoUnit.DAYS),
                "Allow peer task assignment"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grantReq)))
                .andExpect(status().isCreated());

        TestUser peer = register("peerA_", Set.of(RoleName.TEAM_MEMBER));
        addMemberToTeam(teamAId, peer.id(), TeamRole.MEMBER, gdgLead1);

        CreateTaskRequestDTO taskReq = new CreateTaskRequestDTO(
                "Peer Intra Task",
                "Peer coordination",
                TaskAssignmentType.MEMBER_INTRA_TEAM,
                peer.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.MEDIUM,
                Instant.now().plus(6, ChronoUnit.DAYS)
        );

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(taskReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ASSIGNED"));
    }

    @Test
    void testF18_MemberWithCrossTeamGrant_AssignsCrossTeamTask_PendingApproval() throws Exception {
        // Grant ASSIGN_TASK_CROSS_TEAM to teamMemberA
        CreatePermissionGrantRequestDTO grantReq = new CreatePermissionGrantRequestDTO(
                teamMemberA.id(),
                null,
                PermissionCode.ASSIGN_TASK_CROSS_TEAM,
                teamAId,
                null,
                teamBId,
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(5, ChronoUnit.DAYS),
                "Allow cross team task assignment"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(grantReq)))
                .andExpect(status().isCreated());

        CreateTaskRequestDTO taskReq = new CreateTaskRequestDTO(
                "Peer Cross Team Task",
                "Cross team coordination",
                TaskAssignmentType.MEMBER_CROSS_TEAM,
                teamMemberB.id(),
                teamAId,
                teamBId,
                null,
                TaskPriority.MEDIUM,
                Instant.now().plus(6, ChronoUnit.DAYS)
        );

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(taskReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_TEAM_LEAD_APPROVAL"));
    }

    @Test
    void testF18_MemberWithoutGrant_Rejected_403() throws Exception {
        CreateTaskRequestDTO taskReq = new CreateTaskRequestDTO(
                "Unpermitted Task",
                "No grant",
                TaskAssignmentType.MEMBER_INTRA_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.MEDIUM,
                Instant.now().plus(6, ChronoUnit.DAYS)
        );

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamMemberB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(taskReq)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Feature F19: Cross-Team Task Approval Workflow
    // =========================================================================

    @Test
    void testF19_TargetTeamLeadApprovesCrossTeamTask_UnlocksProgress() throws Exception {
        // Create cross team task
        CreateTaskRequestDTO createReq = new CreateTaskRequestDTO(
                "Cross Team Task for Approval",
                "Needs Lead B approval",
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
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_TEAM_LEAD_APPROVAL"))
                .andReturn();
        long taskId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // Assignee progress update while pending approval -> 409 Conflict
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTaskProgressRequestDTO(10, TaskStatus.IN_PROGRESS, "starting", null))))
                .andExpect(status().isConflict());

        // Target Team Lead (Lead B) approves
        ApproveCrossTeamTaskRequestDTO approveReq = new ApproveCrossTeamTaskRequestDTO(true, "Approved for sprint");
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + teamLeadB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"));

        // Progress update now unlocked!
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTaskProgressRequestDTO(25, TaskStatus.IN_PROGRESS, "started work", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressPercentage").value(25));
    }

    @Test
    void testF19_SourceTeamLeadCannotSelfApprove_Rejected_403() throws Exception {
        CreateTaskRequestDTO createReq = new CreateTaskRequestDTO(
                "Self Approval Task",
                "Lead A cannot approve",
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
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long taskId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // Source lead A tries to approve -> 403 Forbidden
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveCrossTeamTaskRequestDTO(true, "self approval"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void testF19_TargetTeamLeadRejection_LeavesTaskRejected() throws Exception {
        CreateTaskRequestDTO createReq = new CreateTaskRequestDTO(
                "Task to be Rejected",
                "Lead B rejects",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberB.id(),
                teamAId,
                teamBId,
                null,
                TaskPriority.MEDIUM,
                Instant.now().plus(10, ChronoUnit.DAYS)
        );

        MvcResult createRes = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long taskId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // Lead B rejects
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + teamLeadB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApproveCrossTeamTaskRequestDTO(false, "Capacity full"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // Progress update remains blocked
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTaskProgressRequestDTO(50, null, "try update", null))))
                .andExpect(status().isConflict());
    }
}
