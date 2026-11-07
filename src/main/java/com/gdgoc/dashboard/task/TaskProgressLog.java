package com.gdgoc.dashboard.task;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "task_progress_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskProgressLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "updated_by_id", nullable = false)
    private Long updatedById;

    @Column(name = "previous_progress", nullable = false)
    private Integer previousProgress;

    @Column(name = "new_progress", nullable = false)
    private Integer newProgress;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, length = 64)
    private TaskStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 64)
    private TaskStatus newStatus;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "evidence_url", length = 500)
    private String evidenceUrl;

    @Column(name = "logged_at", nullable = false, updatable = false)
    private Instant loggedAt;

    @PrePersist
    public void prePersist() {
        if (this.loggedAt == null) {
            this.loggedAt = Instant.now();
        }
    }
}
