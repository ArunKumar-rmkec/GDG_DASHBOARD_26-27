package com.gdgoc.dashboard.mail;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "email_dispatch_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailDispatchLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "certificate_id")
    private Long certificateId;

    @Column(name = "recipient_email", nullable = false)
    private String recipientEmail;

    @Column(name = "cc_emails", columnDefinition = "TEXT")
    private String ccEmails;

    @Column(name = "bcc_emails", columnDefinition = "TEXT")
    private String bccEmails;

    @Column(name = "subject", length = 500)
    private String subject;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "provider_message_id", nullable = false, unique = true)
    private String providerMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 50)
    @Builder.Default
    private EmailDeliveryStatus deliveryStatus = EmailDeliveryStatus.SENT;

    @Column(name = "bounce_reason", columnDefinition = "TEXT")
    private String bounceReason;

    @Column(name = "bounced_at")
    private Instant bouncedAt;

    @Column(name = "sent_by_id")
    private Long sentById;

    @Column(name = "sent_at", nullable = false)
    @Builder.Default
    private Instant sentAt = Instant.now();

    @PrePersist
    public void prePersist() {
        if (this.sentAt == null) {
            this.sentAt = Instant.now();
        }
        if (this.deliveryStatus == null) {
            this.deliveryStatus = EmailDeliveryStatus.SENT;
        }
    }
}
