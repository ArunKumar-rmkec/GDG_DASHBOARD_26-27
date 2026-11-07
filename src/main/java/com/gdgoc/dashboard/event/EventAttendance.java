package com.gdgoc.dashboard.event;

import com.gdgoc.dashboard.member.Member;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
    name = "event_attendances",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_attendance_event_email", columnNames = {"event_id", "participant_email"}),
        @UniqueConstraint(name = "uk_attendance_event_barcode", columnNames = {"event_id", "barcode_token"})
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventAttendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @Column(name = "participant_name", nullable = false, length = 255)
    private String participantName;

    @Column(name = "participant_email", nullable = false, length = 255)
    private String participantEmail;

    @Column(name = "roll_number", length = 100)
    private String rollNumber;

    @Column(name = "barcode_token", length = 255)
    private String barcodeToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_method", nullable = false, length = 32)
    private AttendanceEntryMethod entryMethod;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checked_in_by_id", nullable = false)
    private Member checkedInBy;

    @Column(name = "checked_in_at", nullable = false, updatable = false)
    private Instant checkedInAt;

    @PrePersist
    protected void onCreate() {
        if (checkedInAt == null) checkedInAt = Instant.now();
    }
}
