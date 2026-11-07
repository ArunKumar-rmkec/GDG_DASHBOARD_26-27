package com.gdgoc.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdgoc.dashboard.auth.AuthDtos.RegisterRequestDTO;
import com.gdgoc.dashboard.certificate.CertificateDtos.GenerateCertificatesRequest;
import com.gdgoc.dashboard.event.AttendanceEntryMethod;
import com.gdgoc.dashboard.event.EventAttendanceRepository;
import com.gdgoc.dashboard.event.EventDtos.*;
import com.gdgoc.dashboard.event.EventRole;
import com.gdgoc.dashboard.mail.MailDtos.*;
import com.gdgoc.dashboard.mail.MailService;
import com.gdgoc.dashboard.member.MemberDtos.TerminateMemberRequestDTO;
import com.gdgoc.dashboard.member.RoleName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Adversarial Stress-Testing and Security Boundary Probing Suite
 * for Milestone 2: Event Management, Attendance, Certificates, Mailing & Webhooks (Features F23–F25).
 *
 * Probes:
 * 1. Duplicate check-in race conditions and constraints (same email or barcode token MUST return 409 Conflict).
 * 2. Event clearance enforcement (unauthorized members attempting event CRUD, lead assignment, check-in, or cert generation MUST receive 403 Forbidden).
 * 3. Certificate generation for non-attendees or invalid attendance IDs MUST return 400 Bad Request.
 * 4. Certificate PDF integrity (verify rendered bytes start with '%PDF-' and have non-zero size).
 * 5. Invalid email addresses in recipient, CC, or BCC lists during certificate mailing MUST return 400 Bad Request.
 * 6. Bounce webhook processing (verify webhook correctly updates status to BOUNCED or DELIVERED, sets bounceReason, and handles unknown/unmatched message IDs gracefully without crashing).
 * 7. Terminated member attempting to access event management endpoints MUST receive 403 Forbidden.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class EventAndMailAdversarialIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MailService mailService;

    @Autowired
    private EventAttendanceRepository attendanceRepository;

    private record TestUser(Long id, String token, String email) {}

    private TestUser gdgLead;
    private TestUser eventLead;
    private TestUser hrLead;
    private TestUser regularMember;
    private TestUser hrMember;

    private static int seq = 5000;

    private TestUser registerUser(String prefix, Set<RoleName> roles) throws Exception {
        String email = prefix + (seq++) + "@rmkec.ac.in";
        RegisterRequestDTO req = new RegisterRequestDTO(
                email, "Password123!", "User " + prefix, "22CS" + (700 + (seq % 1000)), "CSE", 3, "+91-9876543210", roles
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

    private long createEvent(TestUser creator, String name) throws Exception {
        CreateEventRequest req = new CreateEventRequest(
                name + "_" + (seq++),
                "Adversarial test event",
                Instant.now().plus(10, ChronoUnit.DAYS),
                "Hall " + (seq % 10)
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
        gdgLead = registerUser("adv_gdg_", Set.of(RoleName.GDG_LEAD));
        eventLead = registerUser("adv_evlead_", Set.of(RoleName.EVENT_LEAD));
        hrLead = registerUser("adv_hrlead_", Set.of(RoleName.HR_LEAD));
        regularMember = registerUser("adv_reg_", Set.of(RoleName.TEAM_MEMBER));
        hrMember = registerUser("adv_hrmem_", Set.of(RoleName.HR_MEMBER));
        mailService.clearCapturedEmails();
    }

    // =========================================================================
    // Attack 1: Duplicate Check-In Constraints (Email & Barcode Token)
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Attack 1: Duplicate check-in with same (eventId, email) or (eventId, barcodeToken) MUST return 409 Conflict")
    void testAttack01_DuplicateCheckinConstraints_Return409() throws Exception {
        long eventId = createEvent(eventLead, "DuplicateCheckinEvent");

        String email = "victim" + (seq++) + "@rmkec.ac.in";
        String barcode = "BARCODE-UNIQUE-" + (seq++);

        // 1. Initial successful check-in
        RecordAttendanceRequest initialReq = new RecordAttendanceRequest(
                null, "Victim One", email, "22CS901", barcode, AttendanceEntryMethod.BARCODE
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(initialReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participantEmail").value(email.toLowerCase()))
                .andExpect(jsonPath("$.barcodeToken").value(barcode));

        // 2. Duplicate check-in: identical email, different barcode -> 409 Conflict
        RecordAttendanceRequest dupeEmailReq = new RecordAttendanceRequest(
                null, "Victim Dupe", email, "22CS902", "BARCODE-DIFFERENT-" + (seq++), AttendanceEntryMethod.BARCODE
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dupeEmailReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 3. Duplicate check-in: case-insensitive email (uppercase) -> 409 Conflict
        RecordAttendanceRequest upperEmailReq = new RecordAttendanceRequest(
                null, "Victim Upper", email.toUpperCase(), "22CS903", "BARCODE-ANOTHER-" + (seq++), AttendanceEntryMethod.BARCODE
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(upperEmailReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 4. Duplicate check-in: different email, identical barcode token -> 409 Conflict
        RecordAttendanceRequest dupeBarcodeReq = new RecordAttendanceRequest(
                null, "Victim Different", "diff" + (seq++) + "@rmkec.ac.in", "22CS904", barcode, AttendanceEntryMethod.BARCODE
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dupeBarcodeReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 5. Cross-event isolation: same email and same barcode on a DIFFERENT event MUST succeed (201 Created)
        long anotherEventId = createEvent(eventLead, "IsolatedEvent");
        RecordAttendanceRequest crossEventReq = new RecordAttendanceRequest(
                null, "Victim CrossEvent", email, "22CS905", barcode, AttendanceEntryMethod.BARCODE
        );
        mockMvc.perform(post("/api/v1/events/" + anotherEventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crossEventReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(anotherEventId));
    }

    // =========================================================================
    // Attack 2: Duplicate Check-In Multi-Threaded Race Condition
    // =========================================================================

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Attack 2: Concurrent race-condition check-in attempts MUST result in exactly 1 success and remaining 409 Conflicts")
    void testAttack02_DuplicateCheckin_ConcurrentRaceCondition() throws Exception {
        long eventId = createEvent(eventLead, "ConcurrencyRaceEvent");
        String raceEmail = "race_victim_" + (seq++) + "@rmkec.ac.in";
        String raceBarcode = "RACE-BARCODE-" + (seq++);

        int concurrencyLevel = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrencyLevel);
        CountDownLatch readyLatch = new CountDownLatch(concurrencyLevel);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger createdCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger otherCount = new AtomicInteger(0);
        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < concurrencyLevel; i++) {
            final int idx = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // Synchronized release of all threads at the exact same millisecond
                    RecordAttendanceRequest req = new RecordAttendanceRequest(
                            null, "Race Runner " + idx, raceEmail, "22CS8" + (10 + idx), raceBarcode, AttendanceEntryMethod.BARCODE
                    );
                    MvcResult res = mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                                    .header("Authorization", "Bearer " + eventLead.token())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(req)))
                            .andReturn();
                    int status = res.getResponse().getStatus();
                    statusCodes.add(status);
                    if (status == 201) {
                        createdCount.incrementAndGet();
                    } else if (status == 409) {
                        conflictCount.incrementAndGet();
                    } else {
                        otherCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    otherCount.incrementAndGet();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // FIRE!
        executor.shutdown();
        boolean finished = executor.awaitTermination(15, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        // Exactly one thread MUST succeed (201 Created)
        assertThat(createdCount.get()).isEqualTo(1);
        // All remaining threads MUST receive 409 Conflict
        assertThat(conflictCount.get()).isEqualTo(concurrencyLevel - 1);
        // Exactly ZERO 500s or unexpected errors
        assertThat(otherCount.get()).isEqualTo(0);

        // Verify database contains exactly 1 attendance record for this event and email
        assertThat(attendanceRepository.findByEventIdOrderByCheckedInAtAsc(eventId))
                .filteredOn(att -> att.getParticipantEmail().equalsIgnoreCase(raceEmail))
                .hasSize(1);
    }

    // =========================================================================
    // Attack 3: Event Clearance Enforcement
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Attack 3: Unauthorized regular members attempting event CRUD, lead assignment, check-in, or cert generation MUST receive 403 Forbidden")
    void testAttack03_EventClearanceEnforcement_UnauthorizedMembers_403() throws Exception {
        long eventId = createEvent(eventLead, "ClearanceBoundaryEvent");

        // 1. Regular member attempts to create an event -> 403
        CreateEventRequest createReq = new CreateEventRequest("Illicit Event", "Desc", Instant.now().plus(5, ChronoUnit.DAYS), "Auditorium");
        mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + regularMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isForbidden());

        // 2. Regular member attempts to view all events -> 403
        mockMvc.perform(get("/api/v1/events")
                        .header("Authorization", "Bearer " + regularMember.token()))
                .andExpect(status().isForbidden());

        // 3. Regular member attempts to view event details -> 403
        mockMvc.perform(get("/api/v1/events/" + eventId)
                        .header("Authorization", "Bearer " + regularMember.token()))
                .andExpect(status().isForbidden());

        // 4. Regular member attempts to assign event lead -> 403
        AssignEventLeadRequest assignReq = new AssignEventLeadRequest(regularMember.id(), EventRole.EVENT_LEAD);
        mockMvc.perform(post("/api/v1/events/" + eventId + "/leads")
                        .header("Authorization", "Bearer " + regularMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(assignReq)))
                .andExpect(status().isForbidden());

        // 5. Regular member attempts to record attendance -> 403
        RecordAttendanceRequest attReq = new RecordAttendanceRequest(
                null, "Att Hacker", "hacker@rmkec.ac.in", "22CS999", null, AttendanceEntryMethod.MANUAL
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + regularMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attReq)))
                .andExpect(status().isForbidden());

        // 6. Regular member attempts to view attendances -> 403
        mockMvc.perform(get("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + regularMember.token()))
                .andExpect(status().isForbidden());

        // 7. Regular member attempts to generate certificates -> 403
        GenerateCertificatesRequest genReq = new GenerateCertificatesRequest(List.of(1L), false, "Cert Title");
        mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/generate")
                        .header("Authorization", "Bearer " + regularMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(genReq)))
                .andExpect(status().isForbidden());

        // 8. Regular member attempts to view generated certificates -> 403
        mockMvc.perform(get("/api/v1/events/" + eventId + "/certificates")
                        .header("Authorization", "Bearer " + regularMember.token()))
                .andExpect(status().isForbidden());

        // 9. Regular member attempts to mail certificates -> 403
        MailCertificatesRequestDTO mailReq = new MailCertificatesRequestDTO(List.of(1L), null, null, "Subject", "Body");
        mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/mail")
                        .header("Authorization", "Bearer " + regularMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mailReq)))
                .andExpect(status().isForbidden());

        // 10. Regular member attempts to view mail logs -> 403
        mockMvc.perform(get("/api/v1/events/" + eventId + "/mail-logs")
                        .header("Authorization", "Bearer " + regularMember.token()))
                .andExpect(status().isForbidden());

        // 11. Regular member attempts to view mail bounces -> 403
        mockMvc.perform(get("/api/v1/events/mail-bounces")
                        .header("Authorization", "Bearer " + regularMember.token()))
                .andExpect(status().isForbidden());

        // 12. Regular member attempts to view participant history -> 403
        mockMvc.perform(get("/api/v1/events/participants/history")
                        .header("Authorization", "Bearer " + regularMember.token()))
                .andExpect(status().isForbidden());

        // 13. HR Member (without event clearance) also receives 403 Forbidden
        mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + hrMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isForbidden());

        // 14. Unauthenticated caller receives 401 Unauthorized
        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Attack 4: Event-Specific Clearance Boundary & Scoping
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Attack 4: Event-specific lead clearance delegates control to assigned event but strictly forbids unassigned events")
    void testAttack04_EventSpecificClearanceScoping_CannotTamperUnassignedEvents() throws Exception {
        long eventA = createEvent(gdgLead, "EventAlpha");
        long eventB = createEvent(gdgLead, "EventBeta");

        TestUser delegatedMember = registerUser("delegated_", Set.of(RoleName.TEAM_MEMBER));

        // Assign delegatedMember as EVENT_LEAD for Event A ONLY
        AssignEventLeadRequest assignReq = new AssignEventLeadRequest(delegatedMember.id(), EventRole.EVENT_LEAD);
        mockMvc.perform(post("/api/v1/events/" + eventA + "/leads")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(assignReq)))
                .andExpect(status().isCreated());

        // delegatedMember CAN manage Event A: Check-in attendee
        RecordAttendanceRequest attA = new RecordAttendanceRequest(
                null, "Attendee Alpha", "alpha" + (seq++) + "@rmkec.ac.in", "22CS111", null, AttendanceEntryMethod.MANUAL
        );
        mockMvc.perform(post("/api/v1/events/" + eventA + "/attendance")
                        .header("Authorization", "Bearer " + delegatedMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attA)))
                .andExpect(status().isCreated());

        // delegatedMember CANNOT manage Event B: Attendance check-in MUST return 403 Forbidden
        RecordAttendanceRequest attB = new RecordAttendanceRequest(
                null, "Attendee Beta", "beta" + (seq++) + "@rmkec.ac.in", "22CS222", null, AttendanceEntryMethod.MANUAL
        );
        mockMvc.perform(post("/api/v1/events/" + eventB + "/attendance")
                        .header("Authorization", "Bearer " + delegatedMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attB)))
                .andExpect(status().isForbidden());

        // delegatedMember CANNOT assign leads on Event B -> 403 Forbidden
        mockMvc.perform(post("/api/v1/events/" + eventB + "/leads")
                        .header("Authorization", "Bearer " + delegatedMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(assignReq)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Attack 5: Certificate Generation for Non-Attendees / Invalid Attendance IDs
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Attack 5: Certificate generation for non-attendees or invalid attendance IDs MUST return 400 Bad Request")
    void testAttack05_CertificateGeneration_InvalidAttendanceIds_Returns400() throws Exception {
        long eventId1 = createEvent(eventLead, "CertGenValidationEvent1");
        long eventId2 = createEvent(eventLead, "CertGenValidationEvent2");

        // Check in attendee to event 1
        RecordAttendanceRequest attReq1 = new RecordAttendanceRequest(
                null, "Valid Attendee 1", "val1_" + (seq++) + "@rmkec.ac.in", "22CS301", null, AttendanceEntryMethod.MANUAL
        );
        MvcResult attRes1 = mockMvc.perform(post("/api/v1/events/" + eventId1 + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attReq1)))
                .andExpect(status().isCreated())
                .andReturn();
        long attId1 = objectMapper.readTree(attRes1.getResponse().getContentAsString()).get("id").asLong();

        // 1. Non-existent attendance ID -> 400 Bad Request
        GenerateCertificatesRequest nonExistentReq = new GenerateCertificatesRequest(
                List.of(99999999L), false, "Cert of Merit"
        );
        mockMvc.perform(post("/api/v1/events/" + eventId1 + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nonExistentReq)))
                .andExpect(status().isBadRequest());

        // 2. Attendance ID from Event 1 sent to Event 2 -> 400 Bad Request
        GenerateCertificatesRequest foreignAttReq = new GenerateCertificatesRequest(
                List.of(attId1), false, "Cert of Merit"
        );
        mockMvc.perform(post("/api/v1/events/" + eventId2 + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(foreignAttReq)))
                .andExpect(status().isBadRequest());

        // 3. Empty attendanceIds list when generateForAllAttendees is false -> 400 Bad Request
        GenerateCertificatesRequest emptyIdsReq = new GenerateCertificatesRequest(
                List.of(), false, "Cert of Merit"
        );
        mockMvc.perform(post("/api/v1/events/" + eventId1 + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyIdsReq)))
                .andExpect(status().isBadRequest());

        // 4. Zero attendees on event when generateForAllAttendees is true -> 400 Bad Request
        GenerateCertificatesRequest zeroAttendeesReq = new GenerateCertificatesRequest(
                List.of(), true, "Cert of Merit"
        );
        mockMvc.perform(post("/api/v1/events/" + eventId2 + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(zeroAttendeesReq)))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Attack 6: Certificate PDF Integrity
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Attack 6: Certificate PDF integrity: verify rendered bytes start with '%PDF-' and have non-zero size")
    void testAttack06_CertificatePdfIntegrity_ValidHeaderAndNonZeroSize() throws Exception {
        long eventId = createEvent(eventLead, "PdfIntegrityEvent");

        RecordAttendanceRequest attReq = new RecordAttendanceRequest(
                null, "Honor Student", "honor" + (seq++) + "@rmkec.ac.in", "22CS401", null, AttendanceEntryMethod.MANUAL
        );
        MvcResult attRes = mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long attId = objectMapper.readTree(attRes.getResponse().getContentAsString()).get("id").asLong();

        GenerateCertificatesRequest genReq = new GenerateCertificatesRequest(
                List.of(attId), false, "Certificate of Excellence"
        );
        MvcResult genRes = mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(genReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long certId = objectMapper.readTree(genRes.getResponse().getContentAsString()).get(0).get("id").asLong();

        // Download Certificate PDF binary
        MvcResult dlRes = mockMvc.perform(get("/api/v1/certificates/" + certId + "/download")
                        .header("Authorization", "Bearer " + eventLead.token()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn();

        byte[] pdfBytes = dlRes.getResponse().getContentAsByteArray();

        // 1. Non-zero size (must be substantial document)
        assertThat(pdfBytes).isNotNull();
        assertThat(pdfBytes.length).isGreaterThan(1000);

        // 2. Starts with magic bytes '%PDF-'
        String header = new String(pdfBytes, 0, Math.min(pdfBytes.length, 8));
        assertThat(header).startsWith("%PDF-");

        // 3. Contains valid PDF end-of-file trailer marker
        String pdfContent = new String(pdfBytes);
        assertThat(pdfContent).contains("%%EOF");

        // 4. Non-existent certificate download returns 404 Not Found
        mockMvc.perform(get("/api/v1/certificates/88888888/download")
                        .header("Authorization", "Bearer " + eventLead.token()))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // Attack 7: Invalid Email Addresses in Mailing Recipient, CC, or BCC Lists
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Attack 7: Invalid email addresses in recipient, CC, or BCC lists during certificate mailing MUST return 400 Bad Request")
    void testAttack07_CertificateMailing_InvalidEmailSyntax_Returns400() throws Exception {
        long eventId = createEvent(eventLead, "MailValidationEvent");

        RecordAttendanceRequest attReq = new RecordAttendanceRequest(
                null, "Mail Attendee", "mailuser" + (seq++) + "@rmkec.ac.in", "22CS501", null, AttendanceEntryMethod.MANUAL
        );
        MvcResult attRes = mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long attId = objectMapper.readTree(attRes.getResponse().getContentAsString()).get("id").asLong();

        GenerateCertificatesRequest genReq = new GenerateCertificatesRequest(
                List.of(attId), false, "Certificate of Completion"
        );
        MvcResult genRes = mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(genReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long certId = objectMapper.readTree(genRes.getResponse().getContentAsString()).get(0).get("id").asLong();

        // 1. Invalid CC: malformed domain (no top-level domain) -> 400
        MailCertificatesRequestDTO invalidCcReq1 = new MailCertificatesRequestDTO(
                List.of(certId), List.of("plainaddress"), null, "Sub", "Body"
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/mail")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidCcReq1)))
                .andExpect(status().isBadRequest());

        // 2. Invalid CC: missing username (@domain.com) -> 400
        MailCertificatesRequestDTO invalidCcReq2 = new MailCertificatesRequestDTO(
                List.of(certId), List.of("@rmkec.ac.in"), null, "Sub", "Body"
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/mail")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidCcReq2)))
                .andExpect(status().isBadRequest());

        // 3. Invalid CC: contains spaces -> 400
        MailCertificatesRequestDTO invalidCcReq3 = new MailCertificatesRequestDTO(
                List.of(certId), List.of("user name@rmkec.ac.in"), null, "Sub", "Body"
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/mail")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidCcReq3)))
                .andExpect(status().isBadRequest());

        // 4. Invalid BCC: malformed email -> 400
        MailCertificatesRequestDTO invalidBccReq = new MailCertificatesRequestDTO(
                List.of(certId), null, List.of("bad..email@@domain.com"), "Sub", "Body"
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/mail")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidBccReq)))
                .andExpect(status().isBadRequest());

        // 5. Valid CC list with one poisoned entry -> 400
        MailCertificatesRequestDTO poisonedListReq = new MailCertificatesRequestDTO(
                List.of(certId), List.of("valid@rmkec.ac.in", "poisoned-format"), null, "Sub", "Body"
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/mail")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(poisonedListReq)))
                .andExpect(status().isBadRequest());

        // 6. Non-existent certificate ID -> 400 Bad Request
        MailCertificatesRequestDTO nonExistentCertReq = new MailCertificatesRequestDTO(
                List.of(99999999L), null, null, "Sub", "Body"
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/mail")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nonExistentCertReq)))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Attack 8: Bounce Webhook Processing & Resilience
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Attack 8: Bounce webhook correctly updates status to BOUNCED or DELIVERED, sets bounceReason, and handles unknown message IDs gracefully without crashing")
    void testAttack08_BounceWebhook_StatusUpdatesAndGracefulUnknownMessageHandling() throws Exception {
        long eventId = createEvent(eventLead, "WebhookResilienceEvent");
        String recipient = "bouncetarget" + (seq++) + "@rmkec.ac.in";

        RecordAttendanceRequest attReq = new RecordAttendanceRequest(
                null, "Webhook Participant", recipient, "22CS601", null, AttendanceEntryMethod.MANUAL
        );
        MvcResult attRes = mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long attId = objectMapper.readTree(attRes.getResponse().getContentAsString()).get("id").asLong();

        GenerateCertificatesRequest genReq = new GenerateCertificatesRequest(
                List.of(attId), false, "Cert of Participation"
        );
        MvcResult genRes = mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(genReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long certId = objectMapper.readTree(genRes.getResponse().getContentAsString()).get(0).get("id").asLong();

        // Dispatch mail
        MailCertificatesRequestDTO mailReq = new MailCertificatesRequestDTO(
                List.of(certId), null, null, "Your Certificate", "Body"
        );
        MvcResult mailRes = mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/mail")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mailReq)))
                .andExpect(status().isOk())
                .andReturn();

        String providerMessageId = objectMapper.readTree(mailRes.getResponse().getContentAsString())
                .get(0).get("providerMessageId").asText();

        // 1. Process BOUNCED event via public webhook
        String bounceReason = "550 5.1.1 User mailbox not found";
        BounceWebhookRequestDTO bounceWhReq = new BounceWebhookRequestDTO(
                providerMessageId, recipient, eventId, "BOUNCED", bounceReason
        );
        mockMvc.perform(post("/api/v1/webhooks/email/bounce")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bounceWhReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryStatus").value("BOUNCED"))
                .andExpect(jsonPath("$.bounceReason").value(bounceReason))
                .andExpect(jsonPath("$.bouncedAt").isNotEmpty());

        // Verify in mail bounces query
        mockMvc.perform(get("/api/v1/events/mail-bounces?eventId=" + eventId)
                        .header("Authorization", "Bearer " + eventLead.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].deliveryStatus").value("BOUNCED"))
                .andExpect(jsonPath("$[0].bounceReason").value(bounceReason));

        // 2. Process DELIVERED event via public webhook
        BounceWebhookRequestDTO deliverWhReq = new BounceWebhookRequestDTO(
                providerMessageId, recipient, eventId, "DELIVERED", null
        );
        mockMvc.perform(post("/api/v1/webhooks/email/bounce")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deliverWhReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryStatus").value("DELIVERED"));

        // 3. Fallback matching when providerMessageId is null (matches by eventId and recipientEmail)
        BounceWebhookRequestDTO fallbackWhReq = new BounceWebhookRequestDTO(
                null, recipient, eventId, "BOUNCED", "Fallback bounce"
        );
        mockMvc.perform(post("/api/v1/webhooks/email/bounce")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fallbackWhReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryStatus").value("BOUNCED"))
                .andExpect(jsonPath("$.bounceReason").value("Fallback bounce"));

        // 4. Resilience: Unknown / Unmatched providerMessageId and recipient MUST NOT crash with 500!
        BounceWebhookRequestDTO unknownWhReq = new BounceWebhookRequestDTO(
                "msg-unknown-ghost-99999999", "ghost@example.com", 99999L, "BOUNCED", "Ghost bounce"
        );
        mockMvc.perform(post("/api/v1/webhooks/email/bounce")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unknownWhReq)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // =========================================================================
    // Attack 9: Terminated Member Attempting Event Management Access
    // =========================================================================

    @Test
    @Transactional
    @DisplayName("Attack 9: Terminated member attempting to access event management endpoints MUST receive 403 Forbidden")
    void testAttack09_TerminatedMemberCannotAccessEventManagement_Strict403() throws Exception {
        // Register an EVENT_LEAD user
        TestUser victimLead = registerUser("term_evlead_", Set.of(RoleName.EVENT_LEAD));
        long eventId = createEvent(victimLead, "PreTerminationEvent");

        // Terminate victimLead via HR Lead
        mockMvc.perform(post("/api/v1/members/" + victimLead.id() + "/terminate")
                        .header("Authorization", "Bearer " + hrLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TerminateMemberRequestDTO("Disciplinary termination", Instant.now().minus(1, ChronoUnit.DAYS)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"));

        // 1. Terminated lead attempts to create event -> 403 Forbidden
        CreateEventRequest postTermReq = new CreateEventRequest("Post-term Event", "Desc", Instant.now().plus(4, ChronoUnit.DAYS), "Aud");
        mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + victimLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(postTermReq)))
                .andExpect(status().isForbidden());

        // 2. Terminated lead attempts to list events -> 403 Forbidden
        mockMvc.perform(get("/api/v1/events")
                        .header("Authorization", "Bearer " + victimLead.token()))
                .andExpect(status().isForbidden());

        // 3. Terminated lead attempts to view event details -> 403 Forbidden
        mockMvc.perform(get("/api/v1/events/" + eventId)
                        .header("Authorization", "Bearer " + victimLead.token()))
                .andExpect(status().isForbidden());

        // 4. Terminated lead attempts to assign leads -> 403 Forbidden
        AssignEventLeadRequest assignReq = new AssignEventLeadRequest(regularMember.id(), EventRole.EVENT_LEAD);
        mockMvc.perform(post("/api/v1/events/" + eventId + "/leads")
                        .header("Authorization", "Bearer " + victimLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(assignReq)))
                .andExpect(status().isForbidden());

        // 5. Terminated lead attempts to record attendance -> 403 Forbidden
        RecordAttendanceRequest attReq = new RecordAttendanceRequest(
                null, "Att PostTerm", "postterm@rmkec.ac.in", "22CS777", null, AttendanceEntryMethod.MANUAL
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + victimLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attReq)))
                .andExpect(status().isForbidden());

        // 6. Terminated lead attempts to generate certificates -> 403 Forbidden
        GenerateCertificatesRequest genReq = new GenerateCertificatesRequest(List.of(1L), false, "Cert Title");
        mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/generate")
                        .header("Authorization", "Bearer " + victimLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(genReq)))
                .andExpect(status().isForbidden());

        // 7. Terminated lead attempts to mail certificates -> 403 Forbidden
        MailCertificatesRequestDTO mailReq = new MailCertificatesRequestDTO(List.of(1L), null, null, "Sub", "Body");
        mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/mail")
                        .header("Authorization", "Bearer " + victimLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mailReq)))
                .andExpect(status().isForbidden());

        // 8. Terminated lead attempts to view mail logs -> 403 Forbidden
        mockMvc.perform(get("/api/v1/events/" + eventId + "/mail-logs")
                        .header("Authorization", "Bearer " + victimLead.token()))
                .andExpect(status().isForbidden());
    }
}
