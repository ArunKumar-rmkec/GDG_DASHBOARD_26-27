package com.gdgoc.dashboard.task;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_type", nullable = false, length = 64)
    private TaskAssignmentType assignmentType;

    @Column(name = "assignor_id", nullable = false)
    private Long assignorId;

    @Column(name = "assignee_id", nullable = false)
    private Long assigneeId;

    @Column(name = "source_team_id")
    private Long sourceTeamId;

    @Column(name = "target_team_id")
    private Long targetTeamId;

    @Column(name = "collaboration_id")
    private Long collaborationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private TaskPriority priority = TaskPriority.MEDIUM;

    @Column(nullable = false)
    private Instant deadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    @Builder.Default
    private TaskStatus status = TaskStatus.ASSIGNED;

    @Column(name = "progress_percentage", nullable = false)
    @Builder.Default
    private Integer progressPercentage = 0;

    @Column(name = "cross_team_approved_by_id")
    private Long crossTeamApprovedById;

    @Column(name = "cross_team_approval_comment", columnDefinition = "TEXT")
    private String crossTeamApprovalComment;

    @Column(name = "cross_team_approved_at")
    private Instant crossTeamApprovedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.progressPercentage == null) {
            this.progressPercentage = 0;
        }
        if (this.priority == null) {
            this.priority = TaskPriority.MEDIUM;
        }
        if (this.status == null) {
            this.status = TaskStatus.ASSIGNED;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
