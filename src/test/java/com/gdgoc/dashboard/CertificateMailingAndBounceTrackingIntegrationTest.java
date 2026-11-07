package com.gdgoc.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdgoc.dashboard.auth.AuthDtos.RegisterRequestDTO;
import com.gdgoc.dashboard.certificate.CertificateDtos.GenerateCertificatesRequest;
import com.gdgoc.dashboard.event.AttendanceEntryMethod;
import com.gdgoc.dashboard.event.EventDtos.*;
import com.gdgoc.dashboard.mail.MailDtos.*;
import com.gdgoc.dashboard.mail.MailService;
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
class CertificateMailingAndBounceTrackingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MailService mailService;

    private record TestUser(Long id, String token, String email) {}

    private TestUser eventLead;
    private TestUser regularMember;

    private static int mailSeq = 1;

    private TestUser register(String prefix, Set<RoleName> roles) throws Exception {
        String email = prefix + (mailSeq++) + "@rmkec.ac.in";
        RegisterRequestDTO req = new RegisterRequestDTO(
                email, "Password123!", "User " + prefix, "22CS" + (300 + mailSeq), "CSE", 3, "+91-9876543210", roles
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
        eventLead = register("ev_lead_m_", Set.of(RoleName.EVENT_LEAD));
        regularMember = register("reg_m_", Set.of(RoleName.TEAM_MEMBER));
        mailService.clearCapturedEmails();
    }

    private record EventWithCert(long eventId, long certId, String recipientEmail) {}

    private EventWithCert setupEventWithCertificate(String participantEmail) throws Exception {
        CreateEventRequest createReq = new CreateEventRequest(
                "Mail Test Event " + (mailSeq++),
                "Event for mail testing",
                Instant.now().plus(5, ChronoUnit.DAYS),
                "Hall M"
        );
        MvcResult createRes = mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long eventId = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asLong();

        RecordAttendanceRequest attReq = new RecordAttendanceRequest(
                null, "Mail Participant", participantEmail, "22CS801", null, AttendanceEntryMethod.MANUAL
        );
        MvcResult attRes = mockMvc.perform(post("/api/v1/events/" + eventId + "/attendance")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long attendanceId = objectMapper.readTree(attRes.getResponse().getContentAsString()).get("id").asLong();

        GenerateCertificatesRequest genReq = new GenerateCertificatesRequest(
                List.of(attendanceId), false, "Certificate of Merit"
        );
        MvcResult genRes = mockMvc.perform(post("/api/v1/events/" + eventId + "/certificates/generate")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(genReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long certId = objectMapper.readTree(genRes.getResponse().getContentAsString()).get(0).get("id").asLong();

        return new EventWithCert(eventId, certId, participantEmail);
    }

    // =========================================================================
    // Feature F25: Certificate Mailing, CC/BCC, Bounce Tracking & Observability
    // =========================================================================

    @Test
    void testF25_MailCertificates_WithCcAndBcc_Success() throws Exception {
        String recipient = "recipient" + (mailSeq++) + "@rmkec.ac.in";
        EventWithCert fixture = setupEventWithCertificate(recipient);

        MailCertificatesRequestDTO mailReq = new MailCertificatesRequestDTO(
                List.of(fixture.certId()),
                List.of("faculty.advisor@rmkec.ac.in", "lead@gdgoc.edu"),
                List.of("archive@gdgoc.edu"),
                "Your DevFest Certificate",
                "Congratulations on completing DevFest!"
        );

        MvcResult mailRes = mockMvc.perform(post("/api/v1/events/" + fixture.eventId() + "/certificates/mail")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mailReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].deliveryStatus").value("SENT"))
                .andExpect(jsonPath("$[0].providerMessageId").isNotEmpty())
                .andExpect(jsonPath("$[0].recipientEmail").value(recipient))
                .andReturn();

        JsonNode logNode = objectMapper.readTree(mailRes.getResponse().getContentAsString()).get(0);
        assertThat(logNode.get("ccEmails").asText()).contains("faculty.advisor@rmkec.ac.in");
        assertThat(logNode.get("bccEmails").asText()).contains("archive@gdgoc.edu");

        // Verify captured in mock mail engine
        List<MailService.CapturedEmail> captured = mailService.getCapturedEmails();
        assertThat(captured).hasSize(1);
        MailService.CapturedEmail email = captured.get(0);
        assertThat(email.to()).isEqualTo(recipient);
        assertThat(email.attachmentBytes()).isNotEmpty();
        String magic = new String(email.attachmentBytes(), 0, Math.min(email.attachmentBytes().length, 8));
        assertThat(magic).startsWith("%PDF-1.");
    }

    @Test
    void testF25_MailCertificates_InvalidCcEmail_Returns400() throws Exception {
        EventWithCert fixture = setupEventWithCertificate("valid" + (mailSeq++) + "@rmkec.ac.in");

        MailCertificatesRequestDTO mailReq = new MailCertificatesRequestDTO(
                List.of(fixture.certId()),
                List.of("not-an-email-format"),
                null,
                "Sub",
                "Body"
        );

        mockMvc.perform(post("/api/v1/events/" + fixture.eventId() + "/certificates/mail")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mailReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testF25_MailCertificates_InvalidBccEmail_Returns400() throws Exception {
        EventWithCert fixture = setupEventWithCertificate("valid2" + (mailSeq++) + "@rmkec.ac.in");

        MailCertificatesRequestDTO mailReq = new MailCertificatesRequestDTO(
                List.of(fixture.certId()),
                null,
                List.of("invalid@@domain..com"),
                "Sub",
                "Body"
        );

        mockMvc.perform(post("/api/v1/events/" + fixture.eventId() + "/certificates/mail")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mailReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testF25_MailCertificates_NonExistentCertId_Returns400() throws Exception {
        EventWithCert fixture = setupEventWithCertificate("valid3" + (mailSeq++) + "@rmkec.ac.in");

        MailCertificatesRequestDTO mailReq = new MailCertificatesRequestDTO(
                List.of(999999L),
                null,
                null,
                "Sub",
                "Body"
        );

        mockMvc.perform(post("/api/v1/events/" + fixture.eventId() + "/certificates/mail")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mailReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testF25_MailCertificates_UnauthorizedMember_Returns403() throws Exception {
        EventWithCert fixture = setupEventWithCertificate("valid4" + (mailSeq++) + "@rmkec.ac.in");

        MailCertificatesRequestDTO mailReq = new MailCertificatesRequestDTO(
                List.of(fixture.certId()),
                null,
                null,
                "Sub",
                "Body"
        );

        mockMvc.perform(post("/api/v1/events/" + fixture.eventId() + "/certificates/mail")
                        .header("Authorization", "Bearer " + regularMember.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mailReq)))
                .andExpect(status().isForbidden());
    }

    @Test
    void testF25_BounceWebhook_UpdatesLogToBouncedWithReason() throws Exception {
        String recipient = "bounced_bob" + (mailSeq++) + "@rmkec.ac.in";
        EventWithCert fixture = setupEventWithCertificate(recipient);

        MailCertificatesRequestDTO mailReq = new MailCertificatesRequestDTO(
                List.of(fixture.certId()), null, null, "Sub", "Body"
        );
        MvcResult mailRes = mockMvc.perform(post("/api/v1/events/" + fixture.eventId() + "/certificates/mail")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mailReq)))
                .andExpect(status().isOk())
                .andReturn();

        String providerMessageId = objectMapper.readTree(mailRes.getResponse().getContentAsString())
                .get(0).get("providerMessageId").asText();

        // Send Public Webhook POST
        String bounceReason = "550 5.1.1 User unknown";
        BounceWebhookRequestDTO whReq = new BounceWebhookRequestDTO(
                providerMessageId, recipient, fixture.eventId(), "BOUNCED", bounceReason
        );

        mockMvc.perform(post("/api/v1/webhooks/email/bounce")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(whReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryStatus").value("BOUNCED"))
                .andExpect(jsonPath("$.bounceReason").value(bounceReason))
                .andExpect(jsonPath("$.bouncedAt").isNotEmpty());

        // Verify in mail bounces query
        mockMvc.perform(get("/api/v1/events/mail-bounces?eventId=" + fixture.eventId())
                        .header("Authorization", "Bearer " + eventLead.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].deliveryStatus").value("BOUNCED"));
    }

    @Test
    void testF25_BounceWebhook_UpdatesLogToDelivered() throws Exception {
        String recipient = "delivered_dan" + (mailSeq++) + "@rmkec.ac.in";
        EventWithCert fixture = setupEventWithCertificate(recipient);

        MailCertificatesRequestDTO mailReq = new MailCertificatesRequestDTO(
                List.of(fixture.certId()), null, null, "Sub", "Body"
        );
        MvcResult mailRes = mockMvc.perform(post("/api/v1/events/" + fixture.eventId() + "/certificates/mail")
                        .header("Authorization", "Bearer " + eventLead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mailReq)))
                .andExpect(status().isOk())
                .andReturn();

        String providerMessageId = objectMapper.readTree(mailRes.getResponse().getContentAsString())
                .get(0).get("providerMessageId").asText();

        BounceWebhookRequestDTO whReq = new BounceWebhookRequestDTO(
                providerMessageId, recipient, fixture.eventId(), "DELIVERED", null
        );

        mockMvc.perform(post("/api/v1/webhooks/email/bounce")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(whReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryStatus").value("DELIVERED"));
    }

    @Test
    void testF25_Observability_OpenApiAndActuator() throws Exception {
        // OpenAPI 3 JSON
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(org.hamcrest.Matchers.startsWith("3.")))
                .andExpect(jsonPath("$.paths").isNotEmpty());

        // Swagger UI
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection()); // redirects to /swagger-ui/index.html

        // Actuator Health
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // Actuator Prometheus
        MvcResult promRes = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(promRes.getResponse().getContentAsString()).contains("jvm_memory");
    }
}
