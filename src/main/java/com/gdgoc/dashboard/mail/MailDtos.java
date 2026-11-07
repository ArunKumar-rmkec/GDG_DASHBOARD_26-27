package com.gdgoc.dashboard.mail;

import java.time.Instant;
import java.util.List;

public class MailDtos {

    public record MailCertificatesRequestDTO(
            List<Long> certificateIds,
            List<String> cc,
            List<String> bcc,
            String subject,
            String body
    ) {}

    public record EmailDispatchLogDTO(
            Long id,
            Long eventId,
            Long certificateId,
            String recipientEmail,
            String ccEmails,
            String bccEmails,
            String subject,
            String body,
            String providerMessageId,
            String deliveryStatus,
            String bounceReason,
            Instant bouncedAt,
            Long sentById,
            Instant sentAt
    ) {}

    public record BounceWebhookRequestDTO(
            String providerMessageId,
            String recipientEmail,
            Long eventId,
            String eventType,
            String bounceReason
    ) {}

    public record WebhookResponseDTO(
            boolean success,
            String message,
            Long dispatchLogId,
            String deliveryStatus,
            String bounceReason
    ) {}
}
