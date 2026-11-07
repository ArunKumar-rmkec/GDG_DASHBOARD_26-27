package com.gdgoc.dashboard.collaboration;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "collaborations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Collaboration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "collaboration_type", nullable = false, length = 50)
    @Builder.Default
    private CollaborationType collaborationType = CollaborationType.CROSS_TEAM;

    @Column(name = "lead_team_id")
    private Long leadTeamId;

    @Column(name = "created_by_id", nullable = false)
    private Long createdById;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private CollaborationStatus status = CollaborationStatus.PENDING_APPROVAL;

    @Column(name = "deadline")
    private Instant deadline;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "collaboration", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("id ASC")
    @Builder.Default
    private List<CollaborationTeamApproval> teamApprovals = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.collaborationType == null) {
            this.collaborationType = CollaborationType.CROSS_TEAM;
        }
        if (this.status == null) {
            this.status = CollaborationStatus.PENDING_APPROVAL;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
