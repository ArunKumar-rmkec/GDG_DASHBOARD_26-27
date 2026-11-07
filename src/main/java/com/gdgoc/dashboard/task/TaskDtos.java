package com.gdgoc.dashboard.task;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public class TaskDtos {

    public record CreateTaskRequestDTO(
            @NotBlank(message = "title cannot be blank")
            String title,

            String description,

            @NotNull(message = "assignmentType is required")
            TaskAssignmentType assignmentType,

            @NotNull(message = "assigneeId is required")
            Long assigneeId,

            Long sourceTeamId,

            Long targetTeamId,

            Long collaborationId,

            TaskPriority priority,

            @NotNull(message = "deadline is required")
            Instant deadline
    ) {}

    public record UpdateTaskProgressRequestDTO(
            @NotNull(message = "progressPercentage is required")
            @Min(value = 0, message = "progressPercentage must be >= 0")
            @Max(value = 100, message = "progressPercentage must be <= 100")
            Integer progressPercentage,

            TaskStatus status,

            String note,

            String evidenceUrl
    ) {}

    public record ApproveCrossTeamTaskRequestDTO(
            @NotNull(message = "approved is required")
            Boolean approved,

            String comment
    ) {}

    public record TaskDTO(
            Long id,
            String title,
            String description,
            TaskAssignmentType assignmentType,
            Long assignorId,
            String assignorName,
            Long assigneeId,
            String assigneeName,
            Long sourceTeamId,
            Long targetTeamId,
            Long collaborationId,
            TaskPriority priority,
            Instant deadline,
            TaskStatus status,
            Integer progressPercentage,
            Long crossTeamApprovedById,
            String crossTeamApprovalComment,
            Instant crossTeamApprovedAt,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt
    ) {}

    public record TaskProgressLogDTO(
            Long id,
            Long taskId,
            Long updatedById,
            String updatedByName,
            Integer previousProgress,
            Integer newProgress,
            Integer progressPercentage,
            TaskStatus previousStatus,
            TaskStatus newStatus,
            TaskStatus status,
            String note,
            String evidenceUrl,
            Instant loggedAt
    ) {}

    public record TaskTrackingDTO(
            Long id,
            String title,
            String description,
            TaskAssignmentType assignmentType,
            Long assignorId,
            String assignorName,
            Long assigneeId,
            String assigneeName,
            Long sourceTeamId,
            Long targetTeamId,
            Long collaborationId,
            TaskPriority priority,
            Instant deadline,
            TaskStatus status,
            Integer progressPercentage,
            boolean overdue,
            List<TaskProgressLogDTO> progressLogs,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt
    ) {}
}
