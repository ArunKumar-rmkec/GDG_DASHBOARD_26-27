package com.gdgoc.dashboard.task;

import com.gdgoc.dashboard.security.UserPrincipal;
import com.gdgoc.dashboard.task.TaskDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "Tasks", description = "Task Assigning & Tracking Core API (Features F14–F19)")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @Operation(summary = "Create and assign a task (F14, F16, F17, F18)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TaskDTO> createTask(
            @Valid @RequestBody CreateTaskRequestDTO request,
            @AuthenticationPrincipal UserPrincipal actor
    ) {
        TaskDTO created = taskService.createTask(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}/progress")
    @Operation(summary = "Update task progress (assignee only) (F15)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TaskDTO> updateProgress(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTaskProgressRequestDTO request,
            @AuthenticationPrincipal UserPrincipal actor
    ) {
        TaskDTO updated = taskService.updateProgress(id, request, actor);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{id}/approve-cross-team")
    @Operation(summary = "Approve or reject cross-team task (target Team Lead or GDG Lead) (F19)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TaskDTO> approveCrossTeamTask(
            @PathVariable Long id,
            @Valid @RequestBody ApproveCrossTeamTaskRequestDTO request,
            @AuthenticationPrincipal UserPrincipal actor
    ) {
        TaskDTO result = taskService.approveCrossTeamTask(id, request, actor);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/tracking")
    @Operation(summary = "Get task tracking details with progress logs and overdue status (F15)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TaskTrackingDTO> getTaskTracking(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal actor
    ) {
        TaskTrackingDTO tracking = taskService.getTaskTracking(id, actor);
        return ResponseEntity.ok(tracking);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get task by ID", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<TaskDTO> getTask(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal actor
    ) {
        TaskDTO task = taskService.getTask(id, actor);
        return ResponseEntity.ok(task);
    }

    @GetMapping
    @Operation(summary = "List and filter tasks (F14, F15, F17)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<TaskDTO>> listTasks(
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) Long assignorId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false, defaultValue = "false") Boolean overdueOnly,
            @AuthenticationPrincipal UserPrincipal actor
    ) {
        List<TaskDTO> tasks = taskService.listTasks(assigneeId, assignorId, teamId, status, overdueOnly, actor);
        return ResponseEntity.ok(tasks);
    }
}
