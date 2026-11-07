package com.gdgoc.dashboard.certificate;

import com.gdgoc.dashboard.event.Event;
import com.gdgoc.dashboard.event.EventAttendance;
import com.gdgoc.dashboard.member.Member;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
    name = "certificates",
    uniqueConstraints = @UniqueConstraint(name = "uk_certificate_event_attendance", columnNames = {"event_id", "attendance_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Certificate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attendance_id", nullable = false)
    private EventAttendance attendance;

    @Column(name = "recipient_name", nullable = false, length = 255)
    private String recipientName;

    @Column(name = "recipient_email", nullable = false, length = 255)
    private String recipientEmail;

    @Column(name = "template_title", nullable = false, length = 255)
    @Builder.Default
    private String templateTitle = "Certificate of Participation";

    @Column(name = "certificate_code", nullable = false, unique = true, length = 128)
    private String certificateCode;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_by_id", nullable = false)
    private Member generatedBy;

    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt;

    @PrePersist
    protected void onCreate() {
        if (generatedAt == null) generatedAt = Instant.now();
        if (templateTitle == null || templateTitle.isBlank()) templateTitle = "Certificate of Participation";
    }
}
