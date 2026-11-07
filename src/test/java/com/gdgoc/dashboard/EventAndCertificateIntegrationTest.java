package com.gdgoc.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdgoc.dashboard.auth.AuthDtos.RegisterRequestDTO;
import com.gdgoc.dashboard.certificate.CertificateDtos.GenerateCertificatesRequest;
import com.gdgoc.dashboard.event.AttendanceEntryMethod;
import com.gdgoc.dashboard.event.EventDtos.*;
import com.gdgoc.dashboard.event.EventRole;
import com.gdgoc.dashboard.member.RoleName;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EventAndCertificateIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private record TestUser(Long id, String token, String email) {}

    private TestUser gdgLead;
    private TestUser eventLead;
    private TestUser eventTeamMember;
    private TestUser regularMember;
    private TestUser hrMember;

    private static int seq = 1;

    private TestUser register(String prefix, Set<RoleName> roles) throws Exception {
        String email = prefix + (seq++) + "@rmkec.ac.in";
        RegisterRequestDTO req = new RegisterRequestDTO(
                email, "Password123!", "User " + prefix, "22CS" + (200 + seq), "CSE", 3, "+91-9876543210", roles
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

    @BeforeEach
    void setUp() throws Exception {
        gdgLead = register("gdg_ev_", Set.of(RoleName.GDG_LEAD));
        eventLead = register("ev_lead_", Set.of(RoleName.EVENT_LEAD));
        eventTeamMember = register("ev_team_", Set.of(RoleName.EVENT_TEAM_MEMBER));
        regularMember = register("reg_mem_", Set.of(RoleName.TEAM_MEMBER));
        hrMember = register("hr_mem_", Set.of(RoleName.HR_MEMBER));
    }

    // =========================================================================
    // Feature F23: Event Management, Event Leads & Attendance Check-In
    // =========================================================================

    @Test
    void testF23_EventCRUDLifecycle() throws Exception {
        CreateEventRequest createReq = new CreateEventRequest(
                "DevFest On Campus 2026",
                "Flagship annual technical conference",
                Instant.now().plus(15, ChronoUnit.DAYS),
                "Main Auditorium"
        );

        MvcResult createRes = mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("DevFest On Campus 2026"))
                .andExpect(jsonPath("$.status").value("UPCOMING"))
                .andReturn();

        long eventId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // Get Event by ID
        mockMvc.perform(get("/api/v1/events/" + eventId)
                        .header("Authorization", "Bearer " + eventLead.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId))
                .andExpect(jsonPath("$.venue").value("Main Auditorium"));

        // List Events
        mockMvc.perform(get("/api/v1/events")
                        .header("Authorization", "Bearer " + eventLead.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void testF23_UnauthorizedMemberCannotCreateEvent_403() throws Exception {
        CreateEventRequest createReq = new CreateEventRequest(
                "Unauthorized Hackathon",
                "Regular member attempting create",
                Instant.now().plus(10, ChronoUnit.DAYS),
                "Room 101"
        );

        mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + regularMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + hrMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isForbidden());
    }

    @Test
    void testF23_AssignEventSpecificLead_DelegatesClearance() throws Exception {
        CreateEventRequest createReq = new CreateEventRequest(
                "Cloud Workshop",
                "GCP hands-on session",
                Instant.now().plus(20, ChronoUnit.DAYS),
                "Lab 3"
        );
        MvcResult createRes = mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long eventId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // Assign regularMember as EVENT_LEAD for this event
        AssignEventLeadRequest assignReq = new AssignEventLeadRequest(regularMember.id(), EventRole.EVENT_LEAD);
        mockMvc.perform(post("/api/v1/events/" + eventId + "/leads")
                        .header("Authorization", "Bearer " + gdgLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(assignReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberId").value(regularMember.id()))
                .andExpect(jsonPath("$.eventRole").value("EVENT_LEAD"));

        // Now regularMember can record attendance for this event!
        RecordAttendanceRequest attReq = new RecordAttendanceRequest(
                null, "Participant X", "partx@example.com", "22CS999", "BAR-001", AttendanceEntryMethod.BARCODE
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + regularMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participantName").value("Participant X"));
    }

    @Test
    void testF23_AttendanceCheckinBarcodeAndManual() throws Exception {
        CreateEventRequest createReq = new CreateEventRequest(
                "Mobile Dev Summit",
                "Flutter & Kotlin",
                Instant.now().plus(12, ChronoUnit.DAYS),
                "Hall A"
        );
        MvcResult createRes = mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long eventId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // 1. Check-in via Barcode
        RecordAttendanceRequest att1 = new RecordAttendanceRequest(
                null, "Alice Barcode", "alice.bc@example.com", "22CS101", "QR-ALICE-1", AttendanceEntryMethod.BARCODE
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(att1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryMethod").value("BARCODE"))
                .andExpect(jsonPath("$.barcodeToken").value("QR-ALICE-1"));

        // 2. Check-in via Manual
        RecordAttendanceRequest att2 = new RecordAttendanceRequest(
                null, "Bob Manual", "bob.man@example.com", "22CS102", null, AttendanceEntryMethod.MANUAL
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(att2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryMethod").value("MANUAL"));

        // 3. List attendance
        mockMvc.perform(get("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void testF23_DuplicateAttendanceRejected_409() throws Exception {
        CreateEventRequest createReq = new CreateEventRequest(
                "AI Workshop",
                "LLMs & Agentic systems",
                Instant.now().plus(5, ChronoUnit.DAYS),
                "Hall B"
        );
        MvcResult createRes = mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long eventId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        RecordAttendanceRequest att = new RecordAttendanceRequest(
                null, "Charlie Dupe", "charlie@example.com", "22CS201", "BAR-CHARLIE", AttendanceEntryMethod.BARCODE
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(att)))
                .andExpect(status().isCreated());

        // Duplicate Email -> 409 Conflict
        RecordAttendanceRequest dupEmail = new RecordAttendanceRequest(
                null, "Charlie Dupe 2", "charlie@example.com", "22CS202", "BAR-DIFF", AttendanceEntryMethod.BARCODE
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dupEmail)))
                .andExpect(status().isConflict());

        // Duplicate Barcode -> 409 Conflict
        RecordAttendanceRequest dupBarcode = new RecordAttendanceRequest(
                null, "Different Charlie", "different@example.com", "22CS203", "BAR-CHARLIE", AttendanceEntryMethod.BARCODE
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dupBarcode)))
                .andExpect(status().isConflict());
    }

    @Test
    void testF23_AttendanceNonexistentEvent_404() throws Exception {
        RecordAttendanceRequest att = new RecordAttendanceRequest(
                null, "Ghost", "ghost@example.com", "22CS000", null, AttendanceEntryMethod.MANUAL
        );
        mockMvc.perform(post("/api/v1/events/999999/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(att)))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // Feature F24: Certificate Generation, Storage & Download
    // =========================================================================

    @Test
    void testF24_GenerateAndDownloadCertificate_VerifiesPdfBytes() throws Exception {
        CreateEventRequest createReq = new CreateEventRequest(
                "Web3 Hackathon",
                "Smart contracts",
                Instant.now().plus(4, ChronoUnit.DAYS),
                "Hall C"
        );
        MvcResult createRes = mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long eventId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // Check in attendee
        RecordAttendanceRequest att = new RecordAttendanceRequest(
                null, "David Winner", "david@example.com", "22CS501", null, AttendanceEntryMethod.MANUAL
        );
        MvcResult attRes = mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(att)))
                .andExpect(status().isCreated())
                .andReturn();
        long attendanceId = objectMapper.readTree(attRes.getResponse().getContentAsString()).get("id").asLong();

        // Generate Certificate
        GenerateCertificatesRequest genReq = new GenerateCertificatesRequest(
                List.of(attendanceId), false, "Certificate of Merit"
        );
        MvcResult genRes = mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(genReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].recipientName").value("David Winner"))
                .andExpect(jsonPath("$[0].participantEmail").value("david@example.com"))
                .andExpect(jsonPath("$[0].certificateCode").isNotEmpty())
                .andReturn();

        long certId = objectMapper.readTree(genRes.getResponse().getContentAsString()).get(0).get("id").asLong();

        // Download Certificate PDF
        MvcResult dlRes = mockMvc.perform(get("/api/v1/certificates/" + certId + "/download")
                        .header("Authorization", "Bearer " + eventLead.token()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn();

        byte[] pdfBytes = dlRes.getResponse().getContentAsByteArray();
        assertThat(pdfBytes).isNotEmpty();
        String header = new String(pdfBytes, 0, Math.min(pdfBytes.length, 8));
        assertThat(header).startsWith("%PDF-1.");
    }

    @Test
    void testF24_GenerateCertificatesBatchAllAttendees() throws Exception {
        CreateEventRequest createReq = new CreateEventRequest(
                "Batch Cert Event",
                "Batch cert generation check",
                Instant.now().plus(6, ChronoUnit.DAYS),
                "Hall D"
        );
        MvcResult createRes = mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long eventId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        for (int i = 1; i <= 3; i++) {
            RecordAttendanceRequest att = new RecordAttendanceRequest(
                    null, "Batch Student " + i, "batch" + i + "@example.com", "22CS60" + i, null, AttendanceEntryMethod.MANUAL
            );
            mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                            .header("Authorization", "Bearer " + eventLead.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(att)))
                    .andExpect(status().isCreated());
        }

        // Generate for all attendees
        GenerateCertificatesRequest genReq = new GenerateCertificatesRequest(
                List.of(), true, "Certificate of Participation"
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(genReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3));

        // List generated certificates
        mockMvc.perform(get("/api/v1/events/" + eventId + "/certificates")
                        .header("Authorization", "Bearer " + eventLead.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void testF24_GenerateCertificatesInvalidAttendance_400() throws Exception {
        CreateEventRequest createReq = new CreateEventRequest(
                "Invalid Attendance Event",
                "Test invalid attendance IDs",
                Instant.now().plus(3, ChronoUnit.DAYS),
                "Auditorium"
        );
        MvcResult createRes = mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long eventId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        // Nonexistent attendance ID -> 400
        GenerateCertificatesRequest genReq1 = new GenerateCertificatesRequest(
                List.of(999999L), false, "Cert"
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(genReq1)))
                .andExpect(status().isBadRequest());

        // Zero attendees on event with generateForAllAttendees=true -> 400
        GenerateCertificatesRequest genReq2 = new GenerateCertificatesRequest(
                List.of(), true, "Cert"
        );
        mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(genReq2)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testF24_DownloadNonexistentCertificate_404() throws Exception {
        mockMvc.perform(get("/api/v1/certificates/999999/download")
                        .header("Authorization", "Bearer " + eventLead.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void testF23_CrossEventParticipantHistoryAggregation() throws Exception {
        // Event 1
        CreateEventRequest req1 = new CreateEventRequest("Hack 1", "Desc", Instant.now().plus(2, ChronoUnit.DAYS), "Hall 1");
        MvcResult r1 = mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated()).andReturn();
        long ev1 = objectMapper.readTree(r1.getResponse().getContentAsString()).get("id").asLong();

        // Event 2
        CreateEventRequest req2 = new CreateEventRequest("Hack 2", "Desc", Instant.now().plus(5, ChronoUnit.DAYS), "Hall 2");
        MvcResult r2 = mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated()).andReturn();
        long ev2 = objectMapper.readTree(r2.getResponse().getContentAsString()).get("id").asLong();

        String commonEmail = "cross.participant@example.com";

        // Check into Event 1 with Barcode
        MvcResult attRes1 = mockMvc.perform(post("/api/v1/events/" + ev1 + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecordAttendanceRequest(
                                null, "Cross Part", commonEmail, "22CS700", "BC-700", AttendanceEntryMethod.BARCODE
                        ))))
                .andExpect(status().isCreated()).andReturn();
        long att1Id = objectMapper.readTree(attRes1.getResponse().getContentAsString()).get("id").asLong();

        // Check into Event 2 with Manual
        mockMvc.perform(post("/api/v1/events/" + ev2 + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecordAttendanceRequest(
                                null, "Cross Part", commonEmail, "22CS700", null, AttendanceEntryMethod.MANUAL
                        ))))
                .andExpect(status().isCreated());

        // Generate cert for Event 1 only
        mockMvc.perform(post("/api/v1/events/" + ev1 + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GenerateCertificatesRequest(
                                List.of(att1Id), false, "Cert 1"
                        ))))
                .andExpect(status().isCreated());

        // Query Participant History
        MvcResult histRes = mockMvc.perform(get("/api/v1/events/participants/history?participantEmail=" + commonEmail)
                        .header("Authorization", "Bearer " + eventLead.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();

        JsonNode hist = objectMapper.readTree(histRes.getResponse().getContentAsString());
        assertThat(hist.get(0).get("participantEmail").asText()).isEqualTo(commonEmail);
        boolean hasCert = false;
        for (JsonNode rec : hist) {
            if (rec.get("certificateGenerated").asBoolean()) {
                hasCert = true;
                assertThat(rec.get("certificateId")).isNotNull();
            }
        }
        assertThat(hasCert).isTrue();
    }
}
