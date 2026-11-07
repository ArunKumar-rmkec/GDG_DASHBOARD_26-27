package com.gdgoc.dashboard.request;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "member_change_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemberChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_member_id")
    private Long targetMemberId;

    @Column(name = "initiated_by_id", nullable = false)
    private Long initiatedById;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 50)
    private ChangeRequestType requestType;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "current_team_id")
    private Long currentTeamId;

    @Column(name = "target_team_id")
    private Long targetTeamId;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private ChangeRequestStatus status = ChangeRequestStatus.PENDING_AUTHORIZATION;

    @Column(name = "hr_lead_approved", nullable = false)
    @Builder.Default
    private boolean hrLeadApproved = false;

    @Column(name = "hr_lead_approved_by_id")
    private Long hrLeadApprovedById;

    @Column(name = "hr_lead_approved_at")
    private Instant hrLeadApprovedAt;

    @Column(name = "team_lead_approved", nullable = false)
    @Builder.Default
    private boolean teamLeadApproved = false;

    @Column(name = "team_lead_approved_by_id")
    private Long teamLeadApprovedById;

    @Column(name = "team_lead_approved_at")
    private Instant teamLeadApprovedAt;

    @Column(name = "gdg_lead_override_by_id")
    private Long gdgLeadOverrideById;

    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    @Column(name = "executed_member_id")
    private Long executedMemberId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "executed_at")
    private Instant executedAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.status == null) {
            this.status = ChangeRequestStatus.PENDING_AUTHORIZATION;
        }
    }
}
