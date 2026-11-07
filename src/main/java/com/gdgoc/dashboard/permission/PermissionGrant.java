package com.gdgoc.dashboard.permission;

import com.gdgoc.dashboard.member.RoleName;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "permission_grants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PermissionGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "granted_by_id", nullable = false)
    private Long grantedById;

    @Column(name = "grantee_member_id")
    private Long granteeMemberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "grantee_role", length = 64)
    private RoleName granteeRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission_code", nullable = false, length = 100)
    private PermissionCode permissionCode;

    @Column(name = "scope_team_id")
    private Long scopeTeamId;

    @Column(name = "scope_event_id")
    private Long scopeEventId;

    @Column(name = "cross_team_target_team_id")
    private Long crossTeamTargetTeamId;

    @Column(name = "approved_by_team_lead_id")
    private Long approvedByTeamLeadId;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approval_comment", columnDefinition = "TEXT")
    private String approvalComment;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private GrantStatus status = GrantStatus.ACTIVE;

    @Column(name = "revoked", nullable = false)
    @Builder.Default
    private boolean revoked = false;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by_id")
    private Long revokedById;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.status == null) {
            this.status = GrantStatus.ACTIVE;
        }
    }
}
