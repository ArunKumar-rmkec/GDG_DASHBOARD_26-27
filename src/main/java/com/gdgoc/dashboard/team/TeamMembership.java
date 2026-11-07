package com.gdgoc.dashboard.team;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "team_memberships")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeamMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "team_role", nullable = false, length = 50)
    @Builder.Default
    private TeamRole teamRole = TeamRole.MEMBER;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "joined_at", nullable = false)
    @Builder.Default
    private Instant joinedAt = Instant.now();

    @Column(name = "left_at")
    private Instant leftAt;

    @Column(name = "exit_reason", length = 255)
    private String exitReason;

    @PrePersist
    public void prePersist() {
        if (this.joinedAt == null) {
            this.joinedAt = Instant.now();
        }
        if (this.teamRole == null) {
            this.teamRole = TeamRole.MEMBER;
        }
    }
}
