package com.gdgoc.dashboard.mail;

import com.gdgoc.dashboard.certificate.Certificate;
import com.gdgoc.dashboard.certificate.CertificateRepository;
import com.gdgoc.dashboard.common.BadRequestException;
import com.gdgoc.dashboard.common.ResourceNotFoundException;
import com.gdgoc.dashboard.common.StorageService;
import com.gdgoc.dashboard.event.Event;
import com.gdgoc.dashboard.event.EventRepository;
import com.gdgoc.dashboard.letter.PdfRenderService;
import com.gdgoc.dashboard.mail.MailDtos.*;
import com.gdgoc.dashboard.security.UserPrincipal;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final EmailDispatchLogRepository dispatchLogRepository;
    private final CertificateRepository certificateRepository;
    private final EventRepository eventRepository;
    private final StorageService storageService;
    private final PdfRenderService pdfRenderService;
    private final JavaMailSender mailSender;
    private final boolean mockEnabled;

    private final Queue<CapturedEmail> capturedEmails = new ConcurrentLinkedQueue<>();

    public record CapturedEmail(
            String to,
            List<String> cc,
            List<String> bcc,
            String subject,
            String body,
            String providerMessageId,
            String attachmentName,
            byte[] attachmentBytes
    ) {}

    public MailService(
            EmailDispatchLogRepository dispatchLogRepository,
            CertificateRepository certificateRepository,
            EventRepository eventRepository,
            StorageService storageService,
            PdfRenderService pdfRenderService,
            @Autowired(required = false) JavaMailSender mailSender,
            @Value("${app.mail.mock-enabled:true}") boolean mockEnabled
    ) {
        this.dispatchLogRepository = dispatchLogRepository;
        this.certificateRepository = certificateRepository;
        this.eventRepository = eventRepository;
        this.storageService = storageService;
        this.pdfRenderService = pdfRenderService;
        this.mailSender = mailSender != null ? mailSender : new JavaMailSenderImpl();
        this.mockEnabled = mockEnabled;
    }

    public List<CapturedEmail> getCapturedEmails() {
        return new ArrayList<>(this.capturedEmails);
    }

    public void clearCapturedEmails() {
        this.capturedEmails.clear();
    }

    @Transactional
    public List<EmailDispatchLogDTO> mailCertificates(Long eventId, MailCertificatesRequestDTO request, UserPrincipal actor) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id: " + eventId));

        // 1. Validate CC and BCC email formats (Sheet 3 Row 4 & 8)
        if (request != null) {
            EmailValidator.validateAll(request.cc(), "CC");
            EmailValidator.validateAll(request.bcc(), "BCC");
        }

        // 2. Resolve certificates to mail
        List<Certificate> targets = new ArrayList<>();
        if (request != null && request.certificateIds() != null && !request.certificateIds().isEmpty()) {
            for (Long certId : request.certificateIds()) {
                Certificate cert = certificateRepository.findById(certId)
                        .orElseThrow(() -> new BadRequestException("Certificate not found: " + certId));
                if (!eventId.equals(cert.getEvent().getId())) {
                    throw new BadRequestException("Certificate " + certId + " does not belong to event " + eventId);
                }
                targets.add(cert);
            }
        } else {
            targets = certificateRepository.findByEventIdOrderByIdAsc(eventId);
            if (targets.isEmpty()) {
                throw new BadRequestException("No certificates found to mail for event " + eventId);
            }
        }

        String subject = (request != null && request.subject() != null && !request.subject().isBlank())
                ? request.subject().trim()
                : "Your Certificate for " + event.getName();

        String body = (request != null && request.body() != null && !request.body().isBlank())
                ? request.body()
                : "Dear Participant,\n\nPlease find attached your certificate for participating in " + event.getName() + ".\n\nWarm regards,\nGoogle Developer Groups on Campus";

        String ccJoined = (request != null && request.cc() != null && !request.cc().isEmpty())
                ? String.join(",", request.cc()) : null;
        String bccJoined = (request != null && request.bcc() != null && !request.bcc().isEmpty())
                ? String.join(",", request.bcc()) : null;

        List<EmailDispatchLogDTO> results = new ArrayList<>();

        for (Certificate cert : targets) {
            String recipient = cert.getRecipientEmail();
            EmailValidator.validate(recipient, "Recipient");

            String providerMessageId = "msg-" + UUID.randomUUID();

            // Load or generate PDF attachment binary
            byte[] pdfBytes = storageService.readBytes(cert.getStorageKey());
            if (pdfBytes == null || pdfBytes.length == 0) {
                String formattedDate = cert.getEvent().getEventDate() != null
                        ? DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(cert.getEvent().getEventDate())
                        : "2026-11-20";

                pdfBytes = pdfRenderService.renderCertificatePdf(
                        cert.getTemplateTitle(),
                        cert.getRecipientName(),
                        event.getName(),
                        formattedDate,
                        cert.getCertificateCode()
                );
            }

            String attachmentFilename = "Certificate-" + cert.getCertificateCode() + ".pdf";

            // Construct MIME message (always validates message structure)
            try {
                MimeMessage mimeMessage = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, StandardCharsets.UTF_8.name());
                helper.setTo(recipient);
                if (request != null && request.cc() != null && !request.cc().isEmpty()) {
                    helper.setCc(request.cc().toArray(new String[0]));
                }
                if (request != null && request.bcc() != null && !request.bcc().isEmpty()) {
                    helper.setBcc(request.bcc().toArray(new String[0]));
                }
                helper.setSubject(subject);
                helper.setText(body);
                helper.addAttachment(attachmentFilename, new ByteArrayResource(pdfBytes), "application/pdf");

                if (mockEnabled) {
                    capturedEmails.add(new CapturedEmail(
                            recipient,
                            request != null ? request.cc() : null,
                            request != null ? request.bcc() : null,
                            subject,
                            body,
                            providerMessageId,
                            attachmentFilename,
                            pdfBytes
                    ));
                    log.info("[MOCK MAIL] Dispatched certificate email to {} with messageId {}", recipient, providerMessageId);
                } else {
                    mailSender.send(mimeMessage);
                }
            } catch (Exception ex) {
                log.error("Failed to construct or dispatch email to " + recipient, ex);
                throw new IllegalStateException("Failed to dispatch certificate email: " + ex.getMessage(), ex);
            }

            // Persist EmailDispatchLog
            EmailDispatchLog logEntity = EmailDispatchLog.builder()
                    .eventId(eventId)
                    .certificateId(cert.getId())
                    .recipientEmail(recipient)
                    .ccEmails(ccJoined)
                    .bccEmails(bccJoined)
                    .subject(subject)
                    .body(body)
                    .providerMessageId(providerMessageId)
                    .deliveryStatus(EmailDeliveryStatus.SENT)
                    .sentById(actor != null ? actor.getId() : null)
                    .sentAt(Instant.now())
                    .build();

            logEntity = dispatchLogRepository.save(logEntity);
            results.add(toDto(logEntity));
        }

        return results;
    }

    @Transactional
    public EmailDispatchLogDTO handleBounceWebhook(BounceWebhookRequestDTO request) {
        EmailDispatchLog logEntity = null;

        // Matching priority:
        // 1. providerMessageId
        if (request.providerMessageId() != null && !request.providerMessageId().isBlank()) {
            logEntity = dispatchLogRepository.findByProviderMessageId(request.providerMessageId().trim()).orElse(null);
        }

        // 2. (eventId, recipientEmail)
        if (logEntity == null && request.eventId() != null && request.recipientEmail() != null && !request.recipientEmail().isBlank()) {
            logEntity = dispatchLogRepository.findFirstByEventIdAndRecipientEmailOrderBySentAtDesc(
                    request.eventId(), request.recipientEmail().trim().toLowerCase(Locale.ROOT)
            ).orElse(null);
        }

        // 3. recipientEmail alone
        if (logEntity == null && request.recipientEmail() != null && !request.recipientEmail().isBlank()) {
            logEntity = dispatchLogRepository.findFirstByRecipientEmailOrderBySentAtDesc(
                    request.recipientEmail().trim().toLowerCase(Locale.ROOT)
            ).orElse(null);
        }

        if (logEntity == null) {
            throw new ResourceNotFoundException("No matching email dispatch log found for webhook payload");
        }

        String eventType = request.eventType() != null ? request.eventType().trim().toUpperCase(Locale.ROOT) : "BOUNCED";
        if ("BOUNCED".equals(eventType)) {
            logEntity.setDeliveryStatus(EmailDeliveryStatus.BOUNCED);
            logEntity.setBounceReason(request.bounceReason());
            logEntity.setBouncedAt(Instant.now());
        } else if ("DELIVERED".equals(eventType)) {
            logEntity.setDeliveryStatus(EmailDeliveryStatus.DELIVERED);
        } else {
            try {
                logEntity.setDeliveryStatus(EmailDeliveryStatus.valueOf(eventType));
            } catch (IllegalArgumentException e) {
                logEntity.setDeliveryStatus(EmailDeliveryStatus.BOUNCED);
            }
        }

        logEntity = dispatchLogRepository.save(logEntity);
        return toDto(logEntity);
    }

    @Transactional(readOnly = true)
    public List<EmailDispatchLogDTO> getEventMailLogs(Long eventId, EmailDeliveryStatus status) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResourceNotFoundException("Event not found with id: " + eventId);
        }
        List<EmailDispatchLog> logs = (status != null)
                ? dispatchLogRepository.findByEventIdAndDeliveryStatusOrderBySentAtDesc(eventId, status)
                : dispatchLogRepository.findByEventIdOrderBySentAtDesc(eventId);
        return logs.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<EmailDispatchLogDTO> getMailBounces(Long eventId) {
        List<EmailDispatchLog> bounces = (eventId != null)
                ? dispatchLogRepository.findByEventIdAndDeliveryStatusOrderBySentAtDesc(eventId, EmailDeliveryStatus.BOUNCED)
                : dispatchLogRepository.findByDeliveryStatusOrderBySentAtDesc(EmailDeliveryStatus.BOUNCED);
        return bounces.stream().map(this::toDto).toList();
    }

    public EmailDispatchLogDTO toDto(EmailDispatchLog l) {
        return new EmailDispatchLogDTO(
                l.getId(),
                l.getEventId(),
                l.getCertificateId(),
                l.getRecipientEmail(),
                l.getCcEmails(),
                l.getBccEmails(),
                l.getSubject(),
                l.getBody(),
                l.getProviderMessageId(),
                l.getDeliveryStatus() != null ? l.getDeliveryStatus().name() : "SENT",
                l.getBounceReason(),
                l.getBouncedAt(),
                l.getSentById(),
                l.getSentAt()
        );
    }
}
