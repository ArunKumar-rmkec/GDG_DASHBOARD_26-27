package com.gdgoc.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdgoc.dashboard.auth.AuthDtos.RegisterRequestDTO;
import com.gdgoc.dashboard.certificate.CertificateDtos.GenerateCertificatesRequest;
import com.gdgoc.dashboard.event.AttendanceEntryMethod;
import com.gdgoc.dashboard.event.EventAttendanceRepository;
import com.gdgoc.dashboard.event.EventDtos.*;
import com.gdgoc.dashboard.event.EventRole;
import com.gdgoc.dashboard.mail.MailDtos.BounceWebhookRequestDTO;
import com.gdgoc.dashboard.mail.MailDtos.MailCertificatesRequestDTO;
import com.gdgoc.dashboard.mail.MailService;
import com.gdgoc.dashboard.member.MemberRepository;
import com.gdgoc.dashboard.member.RoleName;
import com.gdgoc.dashboard.permission.PermissionCode;
import com.gdgoc.dashboard.permission.PermissionDtos.CreatePermissionGrantRequestDTO;
import com.gdgoc.dashboard.task.TaskAssignmentType;
import com.gdgoc.dashboard.task.TaskDtos.*;
import com.gdgoc.dashboard.task.TaskPriority;
import com.gdgoc.dashboard.task.TaskRepository;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tier 5 Milestone 2 Adversarial Stress Testing & Security Boundary Hardening Suite.
 *
 * White-box adversarial coverage for Features F14–F19 and F23–F25:
 * 1. Strict Assignee-Only progress update validation (ensure NO role, not even assignor, lead, or GDG lead, can update progress if not assignee).
 * 2. State transition protection (cannot update progress on PENDING_TEAM_LEAD_APPROVAL, REJECTED, or CANCELLED tasks).
 * 3. Mandatory future deadline validation across negative edge cases (epoch, past, blank, boundary timestamps).
 * 4. Cross-team task approval workflow and anti-self-approval restrictions.
 * 5. Overdue detection calculation across dynamic state transitions.
 * 6. Barcode and manual check-in duplicate prevention under high concurrency / race conditions.
 * 7. Event-specific lead delegation and permission scope boundaries.
 * 8. PDF Certificate generation validation (rejecting non-attendees, empty attendance lists, binary integrity).
 * 9. Certificate mailing with CC/BCC (MIME header injection attempts, malformed emails, null recipients, bounce webhook resilience & idempotency).
 * 10. Actuator & OpenAPI observability security.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class Tier5Milestone2AdversarialHardeningTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private EventAttendanceRepository attendanceRepository;

    @Autowired
    private MailService mailService;

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
    private TestUser hrLead;
    private TestUser eventLead;

    private Long teamAId;
    private Long teamBId;
    private Long teamCId;

    private static int seq = 9000;

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

    private void grantPermission(TestUser grantor, Long granteeId, PermissionCode code, Long teamId) throws Exception {
        CreatePermissionGrantRequestDTO req = new CreatePermissionGrantRequestDTO(
                granteeId, null, code, teamId, null, null,
                Instant.now().minusSeconds(60),
                Instant.now().plus(7, ChronoUnit.DAYS),
                "Tier 5 Adversarial Test Grant"
        );
        mockMvc.perform(post("/api/v1/permissions/grants")
                        .header("Authorization", "Bearer " + grantor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }

    private long createEvent(TestUser creator, String name) throws Exception {
        CreateEventRequest req = new CreateEventRequest(
                name + "_" + (seq++),
                "Tier 5 Event Description",
                Instant.now().plus(14, ChronoUnit.DAYS),
                "Main Auditorium"
        );
        MvcResult res = mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + creator.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    @BeforeEach
    void setUp() throws Exception {
        gdgLead1 = register("t5_gdg1_", Set.of(RoleName.GDG_LEAD));
        gdgLead2 = register("t5_gdg2_", Set.of(RoleName.GDG_LEAD));
        gdgLead3 = register("t5_gdg3_", Set.of(RoleName.GDG_LEAD));

        teamLeadA = register("t5_leadA_", Set.of(RoleName.TEAM_LEAD));
        teamLeadB = register("t5_leadB_", Set.of(RoleName.TEAM_LEAD));
        teamLeadC = register("t5_leadC_", Set.of(RoleName.TEAM_LEAD));

        teamMemberA = register("t5_memA_", Set.of(RoleName.TEAM_MEMBER));
        teamMemberB = register("t5_memB_", Set.of(RoleName.TEAM_MEMBER));
        outsiderMember = register("t5_out_", Set.of(RoleName.TEAM_MEMBER));

        hrLead = register("t5_hrlead_", Set.of(RoleName.HR_LEAD));
        eventLead = register("t5_evlead_", Set.of(RoleName.EVENT_LEAD));

        teamAId = createTeamConsensus("T5 Alpha Team " + seq, gdgLead1, gdgLead2, gdgLead3);
        teamBId = createTeamConsensus("T5 Beta Team " + seq, gdgLead1, gdgLead2, gdgLead3);
        teamCId = createTeamConsensus("T5 Gamma Team " + seq, gdgLead1, gdgLead2, gdgLead3);

        addMemberToTeam(teamAId, teamLeadA.id(), TeamRole.TEAM_LEAD, gdgLead1);
        addMemberToTeam(teamAId, teamMemberA.id(), TeamRole.MEMBER, gdgLead1);

        addMemberToTeam(teamBId, teamLeadB.id(), TeamRole.TEAM_LEAD, gdgLead1);
        addMemberToTeam(teamBId, teamMemberB.id(), TeamRole.MEMBER, gdgLead1);

        addMemberToTeam(teamCId, teamLeadC.id(), TeamRole.TEAM_LEAD, gdgLead1);

        mailService.clearCapturedEmails();
    }

    // =========================================================================
    // Attack 1: Strict Assignee-Only Progress Update RBAC Enforcement (F15)
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Attack 1: Strict Assignee-Only progress update validation — NO role, not even assignor, lead, or GDG lead, can update progress if not assignee")
    void testAttack01_StrictAssigneeOnlyProgressUpdates_AllOtherPersonasForbidden() throws Exception {
        // Create an intra-team task where teamLeadA assigns to teamMemberA
        CreateTaskRequestDTO createReq = new CreateTaskRequestDTO(
                "Implement Security Audit Endpoint",
                "Detailed requirements for audit logging",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.HIGH,
                Instant.now().plus(5, ChronoUnit.DAYS)
        );

        MvcResult taskRes = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();

        long taskId = objectMapper.readTree(taskRes.getResponse().getContentAsString()).get("id").asLong();

        UpdateTaskProgressRequestDTO progressReq = new UpdateTaskProgressRequestDTO(
                50, TaskStatus.IN_PROGRESS, "Halfway done", "https://evidence.org/audit"
        );

        // Sub-attack 1.1: Assignor (teamLeadA) attempts to update progress -> MUST fail 403 Forbidden
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(progressReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("Only the assigned member can update task progress")));

        // Sub-attack 1.2: GDG Lead (Chapter Super Admin) attempts to update progress -> MUST fail 403 Forbidden
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(progressReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("Only the assigned member can update task progress")));

        // Sub-attack 1.3: HR Lead attempts to update progress -> MUST fail 403 Forbidden
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(progressReq)))
                .andExpect(status().isForbidden());

        // Sub-attack 1.4: Event Lead attempts to update progress -> MUST fail 403 Forbidden
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(progressReq)))
                .andExpect(status().isForbidden());

        // Sub-attack 1.5: Another team member attempts to update progress -> MUST fail 403 Forbidden
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(progressReq)))
                .andExpect(status().isForbidden());

        // Sub-attack 1.6: Outsider member attempts to update progress -> MUST fail 403 Forbidden
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + outsiderMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(progressReq)))
                .andExpect(status().isForbidden());

        // Sub-attack 1.7: Unauthenticated request -> MUST fail 401 Unauthorized
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(progressReq)))
                .andExpect(status().isUnauthorized());

        // Legitimate execution: The designated assignee (teamMemberA) updates progress -> MUST succeed 200 OK
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(progressReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressPercentage", is(50)))
                .andExpect(jsonPath("$.status", is("IN_PROGRESS")));

        // Sub-attack 1.8: Assignee sends boundary violation progressPercentage (<0 or >100) -> MUST fail 400 Bad Request
        UpdateTaskProgressRequestDTO negativeProgress = new UpdateTaskProgressRequestDTO(-10, TaskStatus.IN_PROGRESS, "Negative", null);
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(negativeProgress)))
                .andExpect(status().isBadRequest());

        UpdateTaskProgressRequestDTO excessiveProgress = new UpdateTaskProgressRequestDTO(101, TaskStatus.IN_PROGRESS, "Over 100", null);
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(excessiveProgress)))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Attack 2: State Transition Protection (PENDING, REJECTED, CANCELLED) (F15, F19)
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Attack 2: State transition protection — cannot update progress on PENDING_TEAM_LEAD_APPROVAL, REJECTED, or CANCELLED tasks")
    void testAttack02_StateTransitionProtection_CannotUpdateProgressOnPendingOrRejectedOrCancelled() throws Exception {
        // Create cross-team task (TeamLeadA assigns to TeamMemberB in Team B)
        CreateTaskRequestDTO crossReq = new CreateTaskRequestDTO(
                "Cross-Team AI Architecture Review",
                "Review machine learning model architecture",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberB.id(),
                teamAId,
                teamBId,
                null,
                TaskPriority.HIGH,
                Instant.now().plus(6, ChronoUnit.DAYS)
        );

        MvcResult crossRes = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crossReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("PENDING_TEAM_LEAD_APPROVAL")))
                .andReturn();

        long taskId = objectMapper.readTree(crossRes.getResponse().getContentAsString()).get("id").asLong();

        UpdateTaskProgressRequestDTO updateReq = new UpdateTaskProgressRequestDTO(
                20, TaskStatus.IN_PROGRESS, "Started review early", null
        );

        // Sub-attack 2.1: Assignee attempts progress update while task is PENDING_TEAM_LEAD_APPROVAL -> MUST return 409 Conflict
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Cannot update progress on task pending Team Lead approval")));

        // Target Team Lead rejects the task
        ApproveCrossTeamTaskRequestDTO rejectReq = new ApproveCrossTeamTaskRequestDTO(false, "Capacity full this sprint");
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + teamLeadB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rejectReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")));

        // Sub-attack 2.2: Assignee attempts progress update on REJECTED task -> MUST return 409 Conflict
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Cannot update progress on rejected task")));

        // Update task status directly in database to CANCELLED
        entityManager.flush();
        jdbcTemplate.update("UPDATE tasks SET status = 'CANCELLED' WHERE id = ?", taskId);
        entityManager.clear();

        // Sub-attack 2.3: Assignee attempts progress update on CANCELLED task -> MUST return 409 Conflict
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Cannot update progress on cancelled task")));

        // Create a new cross-team task and have Target Team Lead approve it
        MvcResult validTaskRes = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crossReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long validTaskId = objectMapper.readTree(validTaskRes.getResponse().getContentAsString()).get("id").asLong();

        ApproveCrossTeamTaskRequestDTO approveReq = new ApproveCrossTeamTaskRequestDTO(true, "Approved for sprint");
        mockMvc.perform(post("/api/v1/tasks/" + validTaskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + teamLeadB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ASSIGNED")));

        // Assignee can now update progress to 100% -> Auto-completes with COMPLETED status
        UpdateTaskProgressRequestDTO completeReq = new UpdateTaskProgressRequestDTO(
                100, TaskStatus.COMPLETED, "Sprint deliverable finished", "https://github.com/deliverable"
        );
        mockMvc.perform(patch("/api/v1/tasks/" + validTaskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(completeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.progressPercentage", is(100)))
                .andExpect(jsonPath("$.completedAt", notNullValue()));
    }

    // =========================================================================
    // Attack 3: Mandatory Future Deadline Boundary and Negative Edge Cases (F14)
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Attack 3: Mandatory future deadline validation across negative edge cases (epoch, past, blank, boundary timestamps)")
    void testAttack03_MandatoryFutureDeadline_BoundaryAndNegativeEdgeCases() throws Exception {
        // Sub-attack 3.1: Null deadline
        String nullDeadlineJson = """
                {
                    "title": "Null Deadline Task",
                    "assignmentType": "TEAM_LEAD_TO_TEAM",
                    "assigneeId": %d,
                    "sourceTeamId": %d,
                    "targetTeamId": %d,
                    "deadline": null
                }
                """.formatted(teamMemberA.id(), teamAId, teamAId);

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nullDeadlineJson))
                .andExpect(status().isBadRequest());

        // Sub-attack 3.2: Missing deadline attribute
        String missingDeadlineJson = """
                {
                    "title": "Missing Deadline Task",
                    "assignmentType": "TEAM_LEAD_TO_TEAM",
                    "assigneeId": %d,
                    "sourceTeamId": %d,
                    "targetTeamId": %d
                }
                """.formatted(teamMemberA.id(), teamAId, teamAId);

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingDeadlineJson))
                .andExpect(status().isBadRequest());

        // Sub-attack 3.3: Epoch timestamp (1970-01-01T00:00:00Z)
        CreateTaskRequestDTO epochTask = new CreateTaskRequestDTO(
                "Epoch Deadline Task", "Description", TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(), teamAId, teamAId, null, TaskPriority.LOW, Instant.EPOCH
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(epochTask)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("deadline must be non-null and in the future")));

        // Sub-attack 3.4: Negative epoch timestamp (1969-12-31T23:59:59Z)
        CreateTaskRequestDTO negativeEpoch = new CreateTaskRequestDTO(
                "Negative Epoch Task", "Description", TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(), teamAId, teamAId, null, TaskPriority.LOW, Instant.parse("1969-12-31T23:59:59Z")
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(negativeEpoch)))
                .andExpect(status().isBadRequest());

        // Sub-attack 3.5: Far past timestamp (2015-06-01T12:00:00Z)
        CreateTaskRequestDTO pastTask = new CreateTaskRequestDTO(
                "Far Past Task", "Description", TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(), teamAId, teamAId, null, TaskPriority.LOW, Instant.parse("2015-06-01T12:00:00Z")
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pastTask)))
                .andExpect(status().isBadRequest());

        // Sub-attack 3.6: Boundary past timestamp (Instant.now() - 5 seconds)
        CreateTaskRequestDTO immediatePast = new CreateTaskRequestDTO(
                "Immediate Past Task", "Description", TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(), teamAId, teamAId, null, TaskPriority.LOW, Instant.now().minusSeconds(5)
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(immediatePast)))
                .andExpect(status().isBadRequest());

        // Sub-attack 3.7: Blank title
        CreateTaskRequestDTO blankTitleTask = new CreateTaskRequestDTO(
                "   ", "Description", TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(), teamAId, teamAId, null, TaskPriority.LOW, Instant.now().plus(5, ChronoUnit.DAYS)
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(blankTitleTask)))
                .andExpect(status().isBadRequest());

        // Sub-attack 3.8: Assigning to non-existent assignee ID
        CreateTaskRequestDTO nonExistentAssignee = new CreateTaskRequestDTO(
                "Ghost Assignee Task", "Description", TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                999999L, teamAId, teamAId, null, TaskPriority.LOW, Instant.now().plus(5, ChronoUnit.DAYS)
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nonExistentAssignee)))
                .andExpect(status().isBadRequest());

        // Sub-attack 3.9: Assigning to terminated member
        TestUser doomedMember = register("t5_doomed_", Set.of(RoleName.TEAM_MEMBER));
        addMemberToTeam(teamAId, doomedMember.id(), TeamRole.MEMBER, gdgLead1);

        mockMvc.perform(post("/api/v1/members/" + doomedMember.id() + "/terminate")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Terminated for testing\"}"))
                .andExpect(status().isOk());

        CreateTaskRequestDTO terminatedAssignee = new CreateTaskRequestDTO(
                "Terminated Member Task", "Description", TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                doomedMember.id(), teamAId, teamAId, null, TaskPriority.LOW, Instant.now().plus(5, ChronoUnit.DAYS)
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(terminatedAssignee)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Cannot assign task to terminated member")));

        // Valid future deadline -> MUST succeed 201 Created
        CreateTaskRequestDTO validTask = new CreateTaskRequestDTO(
                "Valid Future Task", "Valid Description", TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(), teamAId, teamAId, null, TaskPriority.MEDIUM, Instant.now().plus(1, ChronoUnit.DAYS)
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validTask)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title", is("Valid Future Task")));
    }

    // =========================================================================
    // Attack 4: Cross-Team Task Approval & Anti-Self-Approval Restrictions (F19)
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Attack 4: Cross-team task approval workflow and anti-self-approval restrictions")
    void testAttack04_CrossTeamApproval_AntiSelfApprovalAndStrictWorkflow() throws Exception {
        // Grant Member A ASSIGN_TASK_CROSS_TEAM permission
        grantPermission(teamLeadA, teamMemberA.id(), PermissionCode.ASSIGN_TASK_CROSS_TEAM, teamAId);

        // Member A creates a cross-team task assigning to Member B in Team B
        CreateTaskRequestDTO crossReq = new CreateTaskRequestDTO(
                "Cross-Team Pipeline Integration",
                "Integrate CI/CD pipeline",
                TaskAssignmentType.MEMBER_CROSS_TEAM,
                teamMemberB.id(),
                teamAId,
                teamBId,
                null,
                TaskPriority.HIGH,
                Instant.now().plus(10, ChronoUnit.DAYS)
        );

        MvcResult crossRes = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crossReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("PENDING_TEAM_LEAD_APPROVAL")))
                .andReturn();

        long taskId = objectMapper.readTree(crossRes.getResponse().getContentAsString()).get("id").asLong();

        ApproveCrossTeamTaskRequestDTO approveReq = new ApproveCrossTeamTaskRequestDTO(true, "Approved");

        // Sub-attack 4.1: Assignor (Member A) attempts to approve -> MUST fail 403 Forbidden
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveReq)))
                .andExpect(status().isForbidden());

        // Sub-attack 4.2: Source Team Lead A attempts to approve -> MUST fail 403 Forbidden (Anti-Self-Approval)
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("Source Team Lead cannot self-approve cross-team task")));

        // Sub-attack 4.3: Assignee (Member B) attempts to approve -> MUST fail 403 Forbidden
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + teamMemberB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveReq)))
                .andExpect(status().isForbidden());

        // Sub-attack 4.4: Unrelated Team Lead C attempts to approve -> MUST fail 403 Forbidden
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + teamLeadC.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveReq)))
                .andExpect(status().isForbidden());

        // Legitimate execution: Target Team Lead B approves -> MUST succeed 200 OK
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + teamLeadB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ASSIGNED")))
                .andExpect(jsonPath("$.crossTeamApprovedById", is(teamLeadB.id().intValue())));

        // Sub-attack 4.5: Re-approval attack on already approved (ASSIGNED) task -> MUST fail 409 Conflict
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + teamLeadB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Task is not pending team lead approval")));

        // GDG Lead Override: Create another task and verify GDG Lead can approve
        MvcResult gdgCrossRes = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crossReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long gdgTaskId = objectMapper.readTree(gdgCrossRes.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/v1/tasks/" + gdgTaskId + "/approve-cross-team")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(approveReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ASSIGNED")));
    }

    // =========================================================================
    // Attack 5: Dynamic Overdue Detection Across State Transitions (F15)
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Attack 5: Overdue detection calculation across dynamic state transitions")
    void testAttack05_OverdueDetectionCalculation_AcrossDynamicStateTransitions() throws Exception {
        // Create a task with future deadline
        CreateTaskRequestDTO req = new CreateTaskRequestDTO(
                "Dynamic Overdue Verification Task",
                "Tracking overdue status across lifecycle",
                TaskAssignmentType.TEAM_LEAD_TO_TEAM,
                teamMemberA.id(),
                teamAId,
                teamAId,
                null,
                TaskPriority.HIGH,
                Instant.now().plus(2, ChronoUnit.HOURS)
        );

        MvcResult res = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + teamLeadA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        long taskId = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();

        // 5.1: Initially not overdue (deadline is in future)
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + teamLeadA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue", is(false)));

        // 5.2: Directly update deadline in database to 3 hours ago
        entityManager.flush();
        jdbcTemplate.update("UPDATE tasks SET deadline = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(3, ChronoUnit.HOURS)), taskId);
        entityManager.clear();

        // While in ASSIGNED status -> MUST be overdue == true
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + teamLeadA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue", is(true)));

        // Verify task appears in overdueOnly query filter
        mockMvc.perform(get("/api/v1/tasks?overdueOnly=true")
                        .header("Authorization", "Bearer " + teamLeadA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem((int) taskId)));

        // 5.3: Assignee updates progress to 40% (IN_PROGRESS) -> Still overdue == true
        UpdateTaskProgressRequestDTO progressReq = new UpdateTaskProgressRequestDTO(
                40, TaskStatus.IN_PROGRESS, "Working on it late", null
        );
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(progressReq)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + teamLeadA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue", is(true)));

        // 5.4: Assignee updates progress to 100% (COMPLETED) -> MUST transition overdue to FALSE!
        UpdateTaskProgressRequestDTO completeReq = new UpdateTaskProgressRequestDTO(
                100, TaskStatus.COMPLETED, "Completed after deadline", null
        );
        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/progress")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(completeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")));

        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/tracking")
                        .header("Authorization", "Bearer " + teamLeadA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue", is(false)));

        // Verify completed task is EXCLUDED from overdueOnly query filter
        mockMvc.perform(get("/api/v1/tasks?overdueOnly=true")
                        .header("Authorization", "Bearer " + teamLeadA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem((int) taskId))));
    }

    // =========================================================================
    // Attack 6: High Concurrency Check-In Duplicate Prevention Stress Test (F23)
    // =========================================================================

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Attack 6: Barcode and manual check-in duplicate prevention under high concurrency / race conditions")
    void testAttack06_ConcurrentRaceConditionDuplicatePrevention_HighContentionStress() throws Exception {
        long eventId = createEvent(eventLead, "HighContentionRaceEvent");
        String raceEmail = "stress_race_" + (seq++) + "@rmkec.ac.in";
        String raceBarcode = "BARCODE-RACE-" + (seq++);

        int concurrencyLevel = 12;
        ExecutorService executor = Executors.newFixedThreadPool(concurrencyLevel);
        CountDownLatch readyLatch = new CountDownLatch(concurrencyLevel);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < concurrencyLevel; i++) {
            final int index = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // Synchronized release of all 12 threads
                    RecordAttendanceRequest req = new RecordAttendanceRequest(
                            null, "Contender " + index, raceEmail, "22CS9" + (index + 10), raceBarcode, AttendanceEntryMethod.BARCODE
                    );
                    MvcResult res = mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                                    .header("Authorization", "Bearer " + eventLead.token())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(req)))
                            .andReturn();

                    int status = res.getResponse().getStatus();
                    if (status == 201) {
                        successCount.incrementAndGet();
                    } else if (status == 409) {
                        conflictCount.incrementAndGet();
                    } else {
                        errorCount.incrementAndGet();
                    }
                } catch (Exception ex) {
                    errorCount.incrementAndGet();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // FIRE!
        executor.shutdown();
        boolean finished = executor.awaitTermination(15, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        // High contention verification: EXACTLY 1 succeeds, remaining 11 get 409 Conflict
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(concurrencyLevel - 1);
        assertThat(errorCount.get()).isEqualTo(0);

        // Verify database contains exactly 1 attendance record
        assertThat(attendanceRepository.findByEventIdOrderByCheckedInAtAsc(eventId))
                .filteredOn(a -> a.getParticipantEmail().equalsIgnoreCase(raceEmail))
                .hasSize(1);

        // Sub-attack 6.2: Case-insensitive email duplicate check-in
        RecordAttendanceRequest upperCaseEmailReq = new RecordAttendanceRequest(
                null, "Upper Case Duplicate", raceEmail.toUpperCase(), "22CS999", null, AttendanceEntryMethod.MANUAL
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(upperCaseEmailReq)))
                .andExpect(status().isConflict());

        // Sub-attack 6.3: Null or blank barcode tokens must NOT collide across distinct participants
        String email1 = "attendee_nobarcode_1_" + (seq++) + "@rmkec.ac.in";
        String email2 = "attendee_nobarcode_2_" + (seq++) + "@rmkec.ac.in";

        RecordAttendanceRequest noBarcodeReq1 = new RecordAttendanceRequest(
                null, "No Barcode 1", email1, "22CS801", null, AttendanceEntryMethod.MANUAL
        );
        RecordAttendanceRequest noBarcodeReq2 = new RecordAttendanceRequest(
                null, "No Barcode 2", email2, "22CS802", "   ", AttendanceEntryMethod.MANUAL
        );

        mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noBarcodeReq1)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noBarcodeReq2)))
                .andExpect(status().isCreated());
    }

    // =========================================================================
    // Attack 7: Event-Specific Lead Delegation Scope Boundaries (F23)
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Attack 7: Event-specific lead delegation and permission scope boundaries")
    void testAttack07_EventSpecificLeadDelegation_ScopeBoundariesAndPermissionLimits() throws Exception {
        long eventAlpha = createEvent(gdgLead1, "ScopeEventAlpha");
        long eventBeta = createEvent(gdgLead1, "ScopeEventBeta");

        TestUser delegatedLead = register("t5_delegated_", Set.of(RoleName.TEAM_MEMBER));

        // Assign delegatedLead as EVENT_LEAD for Event Alpha ONLY
        AssignEventLeadRequest assignLeadReq = new AssignEventLeadRequest(delegatedLead.id(), EventRole.EVENT_LEAD);
        mockMvc.perform(post("/api/v1/events/" + eventAlpha + "/leads")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(assignLeadReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventRole", is("EVENT_LEAD")));

        RecordAttendanceRequest checkinAlpha = new RecordAttendanceRequest(
                null, "Alpha Guest", "alpha_guest_" + (seq++) + "@rmkec.ac.in", "22CS101", null, AttendanceEntryMethod.MANUAL
        );
        RecordAttendanceRequest checkinBeta = new RecordAttendanceRequest(
                null, "Beta Guest", "beta_guest_" + (seq++) + "@rmkec.ac.in", "22CS102", null, AttendanceEntryMethod.MANUAL
        );

        // Sub-attack 7.1: Delegated lead checks in for Event Alpha -> MUST succeed 201 Created
        mockMvc.perform(post("/api/v1/events/" + eventAlpha + "/attendance")
                        .header("Authorization", "Bearer " + delegatedLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(checkinAlpha)))
                .andExpect(status().isCreated());

        // Sub-attack 7.2: Delegated lead attempts to check in for Event Beta -> MUST fail 403 Forbidden
        mockMvc.perform(post("/api/v1/events/" + eventBeta + "/attendance")
                        .header("Authorization", "Bearer " + delegatedLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(checkinBeta)))
                .andExpect(status().isForbidden());

        // Sub-attack 7.3: Delegated lead attempts to assign leads on Event Beta -> MUST fail 403 Forbidden
        AssignEventLeadRequest rogueAssign = new AssignEventLeadRequest(teamMemberA.id(), EventRole.EVENT_LEAD);
        mockMvc.perform(post("/api/v1/events/" + eventBeta + "/leads")
                        .header("Authorization", "Bearer " + delegatedLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rogueAssign)))
                .andExpect(status().isForbidden());

        // Sub-attack 7.4: Assigning user as EVENT_TEAM_MEMBER (sub-lead) can check in but CANNOT assign leads
        TestUser eventHelper = register("t5_helper_", Set.of(RoleName.TEAM_MEMBER));
        AssignEventLeadRequest assignHelperReq = new AssignEventLeadRequest(eventHelper.id(), EventRole.EVENT_TEAM_MEMBER);
        mockMvc.perform(post("/api/v1/events/" + eventAlpha + "/leads")
                        .header("Authorization", "Bearer " + delegatedLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(assignHelperReq)))
                .andExpect(status().isCreated());

        // Helper tries to assign leads -> MUST fail 403 Forbidden
        mockMvc.perform(post("/api/v1/events/" + eventAlpha + "/leads")
                        .header("Authorization", "Bearer " + eventHelper.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rogueAssign)))
                .andExpect(status().isForbidden());

        // Regular unprivileged member trying to create events -> MUST fail 403 Forbidden
        CreateEventRequest unprivEvent = new CreateEventRequest("Rogue Event", "Desc", Instant.now().plus(5, ChronoUnit.DAYS), "Auditorium");
        mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + teamMemberA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unprivEvent)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Attack 8: PDF Certificate Generation Boundaries & Binary Validation (F24)
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Attack 8: PDF Certificate generation validation (rejecting non-attendees, empty attendance lists)")
    void testAttack08_PdfCertificateGeneration_NonAttendeesEmptyListsAndBinaryIntegrity() throws Exception {
        long eventAlpha = createEvent(eventLead, "CertValidationEventAlpha");
        long eventBeta = createEvent(eventLead, "CertValidationEventBeta");

        // Sub-attack 8.1: Generate for all attendees on an event with ZERO attendees -> MUST fail 400 Bad Request
        GenerateCertificatesRequest emptyAllReq = new GenerateCertificatesRequest(null, true, "Participation Certificate");
        mockMvc.perform(post("/api/v1/events/" + eventAlpha + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyAllReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("No attendees found for event")));

        // Sub-attack 8.2: Generate with empty attendance IDs list and generateForAllAttendees = false -> MUST fail 400 Bad Request
        GenerateCertificatesRequest emptyListReq = new GenerateCertificatesRequest(Collections.emptyList(), false, "Title");
        mockMvc.perform(post("/api/v1/events/" + eventAlpha + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyListReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("attendanceIds must not be empty")));

        // Sub-attack 8.3: Generate with non-existent attendance ID -> MUST fail 400 Bad Request
        GenerateCertificatesRequest nonExistentAttReq = new GenerateCertificatesRequest(List.of(999999L), false, "Title");
        mockMvc.perform(post("/api/v1/events/" + eventAlpha + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nonExistentAttReq)))
                .andExpect(status().isBadRequest());

        // Check in one participant for Event Alpha
        RecordAttendanceRequest attAlpha = new RecordAttendanceRequest(
                null, "Charlie Alpha", "charlie_alpha_" + (seq++) + "@rmkec.ac.in", "22CS301", null, AttendanceEntryMethod.MANUAL
        );
        MvcResult attRes = mockMvc.perform(post("/api/v1/events/" + eventAlpha + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attAlpha)))
                .andExpect(status().isCreated())
                .andReturn();
        long attendanceAlphaId = objectMapper.readTree(attRes.getResponse().getContentAsString()).get("id").asLong();

        // Sub-attack 8.4: Cross-event tampering: Generate cert on Event Beta endpoint using attendance ID from Event Alpha -> MUST fail 400 Bad Request
        GenerateCertificatesRequest crossEventCertReq = new GenerateCertificatesRequest(List.of(attendanceAlphaId), false, "Tampered");
        mockMvc.perform(post("/api/v1/events/" + eventBeta + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crossEventCertReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("does not belong to event " + eventBeta)));

        // Legitimate cert generation on Event Alpha -> MUST succeed 201 Created
        GenerateCertificatesRequest validGenReq = new GenerateCertificatesRequest(List.of(attendanceAlphaId), false, "Valid Cert");
        MvcResult genRes = mockMvc.perform(post("/api/v1/events/" + eventAlpha + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validGenReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].certificateCode", startsWith("GDGOC-CERT-")))
                .andReturn();

        long certificateId = objectMapper.readTree(genRes.getResponse().getContentAsString()).get(0).get("id").asLong();

        // Idempotency: Re-generating for the same attendee returns the existing certificate without creating duplicate entries
        mockMvc.perform(post("/api/v1/events/" + eventAlpha + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validGenReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].id", is((int) certificateId)));

        // Sub-attack 8.5: Download certificate binary and verify real %PDF-1.x bytes
        MvcResult pdfDownloadRes = mockMvc.perform(get("/api/v1/certificates/" + certificateId + "/download")
                        .header("Authorization", "Bearer " + eventLead.token()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn();

        byte[] pdfBytes = pdfDownloadRes.getResponse().getContentAsByteArray();
        assertThat(pdfBytes).isNotEmpty();
        assertThat(new String(Arrays.copyOfRange(pdfBytes, 0, 5))).isEqualTo("%PDF-");
        assertThat(pdfBytes.length).isGreaterThan(500);
    }

    // =========================================================================
    // Attack 9: Certificate Mailing Validation, MIME Injection & Webhooks (F25)
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Attack 9: Certificate mailing with CC/BCC (MIME header injection attempts, malformed emails, null recipients)")
    void testAttack09_CertificateMailing_MimeInjectionMalformedEmailsAndBounceWebhookResilience() throws Exception {
        long eventId = createEvent(eventLead, "MailInjectionEvent");

        // Check in attendee and generate certificate
        String recipient = "recipient_" + (seq++) + "@rmkec.ac.in";
        RecordAttendanceRequest att = new RecordAttendanceRequest(
                null, "Mail Recipient", recipient, "22CS401", null, AttendanceEntryMethod.MANUAL
        );
        MvcResult attRes = mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(att)))
                .andExpect(status().isCreated())
                .andReturn();
        long attId = objectMapper.readTree(attRes.getResponse().getContentAsString()).get("id").asLong();

        GenerateCertificatesRequest genReq = new GenerateCertificatesRequest(List.of(attId), false, "DevFest Certificate");
        MvcResult genRes = mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(genReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long certId = objectMapper.readTree(genRes.getResponse().getContentAsString()).get(0).get("id").asLong();

        // Sub-attack 9.1: MIME Header Injection Attempt in CC (CRLF \r\n with injected Bcc)
        MailCertificatesRequestDTO injectionCcReq = new MailCertificatesRequestDTO(
                List.of(certId),
                List.of("victim@example.com\r\nBcc: evil@attacker.com"),
                null,
                "Subject",
                "Body"
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/mail")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(injectionCcReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Invalid CC email format")));

        // Sub-attack 9.2: MIME Header Injection in BCC (LF \n with injected Subject)
        MailCertificatesRequestDTO injectionBccReq = new MailCertificatesRequestDTO(
                List.of(certId),
                null,
                List.of("safe@example.com\nSubject: Injected Subject"),
                "Subject",
                "Body"
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/mail")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(injectionBccReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Invalid BCC email format")));

        // Sub-attack 9.3: Malformed emails in CC list
        List<String> malformedEmails = List.of("plainaddress", "@missinguser.com", "user@.invalid", "spaces in@mail.com");
        for (String badEmail : malformedEmails) {
            MailCertificatesRequestDTO badMailReq = new MailCertificatesRequestDTO(
                    List.of(certId), List.of(badEmail), null, "Subject", "Body"
            );
            mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/mail")
                            .header("Authorization", "Bearer " + eventLead.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(badMailReq)))
                    .andExpect(status().isBadRequest());
        }

        // Sub-attack 9.4: Mailing a certificate ID that does NOT belong to this event
        long otherEventId = createEvent(eventLead, "UnrelatedMailEvent");
        MailCertificatesRequestDTO wrongEventCertReq = new MailCertificatesRequestDTO(
                List.of(certId), null, null, "Subject", "Body"
        );
        mockMvc.perform(post("/api/v1/events/" + otherEventId + "/certificates/mail")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongEventCertReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("does not belong to event " + otherEventId)));

        // Legitimate certificate mailing with valid CC and BCC -> MUST succeed 200 OK
        MailCertificatesRequestDTO validMailReq = new MailCertificatesRequestDTO(
                List.of(certId),
                List.of("lead.archive@gdgoc.edu"),
                List.of("bcc.backup@gdgoc.edu"),
                "Congratulations on your GDGoC Certificate!",
                "Attached is your certificate of participation."
        );
        MvcResult mailRes = mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/mail")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validMailReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].deliveryStatus", is("SENT")))
                .andReturn();

        String providerMessageId = objectMapper.readTree(mailRes.getResponse().getContentAsString()).get(0).get("providerMessageId").asText();

        // Sub-attack 9.5: Bounce Webhook Resilience & Idempotency
        // 9.5a: Unknown provider message ID and unknown email -> MUST return 404 Not Found
        BounceWebhookRequestDTO unknownWebhook = new BounceWebhookRequestDTO(
                "non-existent-msg-id-12345", "unknown@test.com", eventId, "BOUNCED", "550 User unknown"
        );
        mockMvc.perform(post("/api/v1/webhooks/email/bounce")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unknownWebhook)))
                .andExpect(status().isNotFound());

        // 9.5b: Valid bounce webhook signal -> MUST update deliveryStatus to BOUNCED
        BounceWebhookRequestDTO validBounce = new BounceWebhookRequestDTO(
                providerMessageId, recipient, eventId, "BOUNCED", "550 5.1.1 Mailbox unavailable"
        );
        mockMvc.perform(post("/api/v1/webhooks/email/bounce")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBounce)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryStatus", is("BOUNCED")))
                .andExpect(jsonPath("$.bounceReason", is("550 5.1.1 Mailbox unavailable")))
                .andExpect(jsonPath("$.bouncedAt", notNullValue()));

        // 9.5c: Repeated bounce webhook invocation (Idempotency) -> MUST remain BOUNCED without error
        mockMvc.perform(post("/api/v1/webhooks/email/bounce")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validBounce)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryStatus", is("BOUNCED")));

        // 9.5d: Verify mail bounce reports reflect the recorded bounce
        mockMvc.perform(get("/api/v1/events/mail-bounces?eventId=" + eventId)
                        .header("Authorization", "Bearer " + eventLead.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].providerMessageId", hasItem(providerMessageId)));
    }

    // =========================================================================
    // Attack 10: Actuator & OpenAPI Observability Security (F25)
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Attack 10: Actuator & OpenAPI observability endpoints availability and security")
    void testAttack10_ActuatorAndOpenApiObservability_SecurityAndSanitization() throws Exception {
        // 10.1: Health endpoint is available and UP
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));

        // 10.2: Prometheus metric scraping endpoint is available and contains JVM metrics
        MvcResult promRes = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn();
        String promBody = promRes.getResponse().getContentAsString();
        assertThat(promBody).isNotEmpty();
        assertThat(promBody).containsAnyOf("jvm_memory", "process_uptime", "system_cpu_usage", "http_server_requests");

        // 10.3: OpenAPI 3 documentation endpoint (/v3/api-docs) is available and documents core endpoints
        MvcResult openApiRes = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        String openApiJson = openApiRes.getResponse().getContentAsString();
        assertThat(openApiJson).contains("/api/v1/tasks");
        assertThat(openApiJson).contains("/api/v1/events");
        assertThat(openApiJson).contains("/api/v1/certificates/{certificateId}/download");
        assertThat(openApiJson).contains("/api/v1/webhooks/email/bounce");

        // 10.4: Swagger UI entry point is accessible (returns 200 or 302 redirect)
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());

        // 10.5: Verify terminated member permissions are completely revoked
        TestUser terminatedUser = register("t5_term_obs_", Set.of(RoleName.TEAM_MEMBER));
        mockMvc.perform(post("/api/v1/members/" + terminatedUser.id() + "/terminate")
                        .header("Authorization", "Bearer " + gdgLead1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Terminated member observability check\"}"))
                .andExpect(status().isOk());

        // Anonymous unauthenticated request to public health endpoint succeeds
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));

        // Active member authenticated request to health endpoint succeeds
        mockMvc.perform(get("/actuator/health")
                        .header("Authorization", "Bearer " + teamMemberA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));

        // Terminated member token is blocked by TerminationAccessFilter on non-whitelisted operational/actuator endpoints (403 Forbidden)
        mockMvc.perform(get("/actuator/health")
                        .header("Authorization", "Bearer " + terminatedUser.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("All operational permissions are revoked after termination")));

        // Operational task listing endpoint blocked for terminated member with 403 Forbidden
        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", "Bearer " + terminatedUser.token()))
                .andExpect(status().isForbidden());
    }
}
