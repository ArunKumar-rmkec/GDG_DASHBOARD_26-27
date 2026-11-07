package com.gdgoc.dashboard.task;

import com.gdgoc.dashboard.collaboration.CollaborationRepository;
import com.gdgoc.dashboard.common.BadRequestException;
import com.gdgoc.dashboard.common.ConflictException;
import com.gdgoc.dashboard.common.ForbiddenException;
import com.gdgoc.dashboard.common.ResourceNotFoundException;
import com.gdgoc.dashboard.member.*;
import com.gdgoc.dashboard.permission.PermissionCode;
import com.gdgoc.dashboard.security.ClearanceEvaluator;
import com.gdgoc.dashboard.security.UserPrincipal;
import com.gdgoc.dashboard.task.TaskDtos.*;
import com.gdgoc.dashboard.team.TeamMembership;
import com.gdgoc.dashboard.team.TeamMembershipRepository;
import com.gdgoc.dashboard.team.TeamRepository;
import com.gdgoc.dashboard.team.TeamRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskProgressLogRepository progressLogRepository;
    private final MemberRepository memberRepository;
    private final TeamRepository teamRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final CollaborationRepository collaborationRepository;
    private final ClearanceEvaluator clearanceEvaluator;

    public TaskService(
            TaskRepository taskRepository,
            TaskProgressLogRepository progressLogRepository,
            MemberRepository memberRepository,
            TeamRepository teamRepository,
            TeamMembershipRepository teamMembershipRepository,
            CollaborationRepository collaborationRepository,
            ClearanceEvaluator clearanceEvaluator
    ) {
        this.taskRepository = taskRepository;
        this.progressLogRepository = progressLogRepository;
        this.memberRepository = memberRepository;
        this.teamRepository = teamRepository;
        this.teamMembershipRepository = teamMembershipRepository;
        this.collaborationRepository = collaborationRepository;
        this.clearanceEvaluator = clearanceEvaluator;
    }

    @Transactional
    public TaskDTO createTask(CreateTaskRequestDTO req, UserPrincipal actor) {
        if (actor == null || actor.getStatus() != MemberStatus.ACTIVE) {
            throw new ForbiddenException("Active authentication required");
        }

        // F14: Mandatory future deadline validation
        if (req.deadline() == null || !req.deadline().isAfter(Instant.now())) {
            throw new BadRequestException("deadline must be non-null and in the future");
        }
        if (req.title() == null || req.title().isBlank()) {
            throw new BadRequestException("title cannot be blank");
        }

        // Assignee validation
        Member assignee = memberRepository.findById(req.assigneeId())
                .orElseThrow(() -> new BadRequestException("Assignee not found: " + req.assigneeId()));
        if (assignee.getStatus() == MemberStatus.TERMINATED) {
            throw new BadRequestException("Cannot assign task to terminated member");
        }

        // Team validations if provided
        if (req.sourceTeamId() != null && !teamRepository.existsById(req.sourceTeamId())) {
            throw new BadRequestException("Source team not found: " + req.sourceTeamId());
        }
        if (req.targetTeamId() != null && !teamRepository.existsById(req.targetTeamId())) {
            throw new BadRequestException("Target team not found: " + req.targetTeamId());
        }

        // Collaboration validation if provided
        if (req.collaborationId() != null && !collaborationRepository.existsById(req.collaborationId())) {
            throw new BadRequestException("Collaboration not found: " + req.collaborationId());
        }

        TaskStatus initialStatus;

        switch (req.assignmentType()) {
            case GDG_LEAD_TO_TEAM_LEAD -> {
                // F16: Caller MUST be GDG_LEAD
                if (!actor.hasRole(RoleName.GDG_LEAD)) {
                    throw new ForbiddenException("Only GDG Leads can assign tasks of type GDG_LEAD_TO_TEAM_LEAD");
                }
                // Assignee MUST hold TEAM_LEAD role
                boolean isAssigneeTeamLead = assignee.getRoles().contains(RoleName.TEAM_LEAD);
                if (!isAssigneeTeamLead) {
                    List<TeamMembership> memberships = teamMembershipRepository.findByMemberIdAndActiveTrue(assignee.getId());
                    isAssigneeTeamLead = memberships.stream()
                            .anyMatch(m -> m.getTeamRole() == TeamRole.TEAM_LEAD || m.getTeamRole() == TeamRole.SUB_TEAM_LEAD);
                }
                if (!isAssigneeTeamLead) {
                    throw new BadRequestException("Assignee does not hold TEAM_LEAD role");
                }
                initialStatus = TaskStatus.ASSIGNED;
            }

            case TEAM_LEAD_TO_TEAM -> {
                // F17: Caller MUST be GDG_LEAD or TEAM_LEAD of source team
                boolean isAuthorized = actor.hasRole(RoleName.GDG_LEAD)
                        || clearanceEvaluator.isTeamLeadForTeam(actor, req.sourceTeamId());
                if (!isAuthorized) {
                    throw new ForbiddenException("Only Team Leads or GDG Leads can assign tasks of type TEAM_LEAD_TO_TEAM");
                }

                if (actor.hasRole(RoleName.GDG_LEAD)) {
                    initialStatus = TaskStatus.ASSIGNED;
                } else if (req.sourceTeamId() != null && req.targetTeamId() != null
                        && !Objects.equals(req.sourceTeamId(), req.targetTeamId())
                        && !clearanceEvaluator.isTeamLeadForTeam(actor, req.targetTeamId())) {
                    initialStatus = TaskStatus.PENDING_TEAM_LEAD_APPROVAL;
                } else {
                    initialStatus = TaskStatus.ASSIGNED;
                }
            }

            case MEMBER_INTRA_TEAM -> {
                // F18: Intra-team assignment
                boolean isLead = actor.hasRole(RoleName.GDG_LEAD)
                        || clearanceEvaluator.isTeamLeadForTeam(actor, req.sourceTeamId());
                if (!isLead) {
                    boolean hasGrant = clearanceEvaluator.hasActiveGrant(
                            actor.getId(), actor.getRoles(), PermissionCode.ASSIGN_TASK_INTRA_TEAM, req.sourceTeamId(), null
                    ) || clearanceEvaluator.hasActiveGrant(
                            actor.getId(), actor.getRoles(), PermissionCode.DASHBOARD_ACTION_ACCESS, req.sourceTeamId(), null
                    );
                    if (!hasGrant) {
                        throw new ForbiddenException("Member does not have active ASSIGN_TASK_INTRA_TEAM grant");
                    }
                }
                if (req.sourceTeamId() != null && req.targetTeamId() != null
                        && !Objects.equals(req.sourceTeamId(), req.targetTeamId())) {
                    throw new BadRequestException("MEMBER_INTRA_TEAM cannot be used for cross-team task");
                }
                initialStatus = TaskStatus.ASSIGNED;
            }

            case MEMBER_CROSS_TEAM -> {
                // F18: Cross-team assignment
                boolean hasCrossGrant = actor.hasRole(RoleName.GDG_LEAD)
                        || clearanceEvaluator.hasActiveGrant(actor.getId(), actor.getRoles(), PermissionCode.ASSIGN_TASK_CROSS_TEAM, req.sourceTeamId(), null)
                        || clearanceEvaluator.hasActiveGrant(actor.getId(), actor.getRoles(), PermissionCode.ASSIGN_TASK_CROSS_TEAM, req.targetTeamId(), null)
                        || clearanceEvaluator.hasActiveGrant(actor.getId(), actor.getRoles(), PermissionCode.DASHBOARD_ACTION_ACCESS, req.sourceTeamId(), null);
                if (!hasCrossGrant) {
                    throw new ForbiddenException("Member does not have active ASSIGN_TASK_CROSS_TEAM grant");
                }
                initialStatus = TaskStatus.PENDING_TEAM_LEAD_APPROVAL;
            }

            default -> throw new BadRequestException("Unsupported assignment type: " + req.assignmentType());
        }

        Task task = Task.builder()
                .title(req.title().trim())
                .description(req.description())
                .assignmentType(req.assignmentType())
                .assignorId(actor.getId())
                .assigneeId(assignee.getId())
                .sourceTeamId(req.sourceTeamId())
                .targetTeamId(req.targetTeamId())
                .collaborationId(req.collaborationId())
                .priority(req.priority() != null ? req.priority() : TaskPriority.MEDIUM)
                .deadline(req.deadline())
                .status(initialStatus)
                .progressPercentage(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        task = taskRepository.saveAndFlush(task);
        return toDto(task);
    }

    @Transactional
    public TaskDTO updateProgress(Long taskId, UpdateTaskProgressRequestDTO req, UserPrincipal actor) {
        if (actor == null || actor.getStatus() != MemberStatus.ACTIVE) {
            throw new ForbiddenException("Active authentication required");
        }

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));

        // F15: STRICTLY assignee only
        if (!Objects.equals(task.getAssigneeId(), actor.getId())) {
            throw new ForbiddenException("Only the assigned member can update task progress");
        }

        // Approval state checks
        if (task.getStatus() == TaskStatus.PENDING_TEAM_LEAD_APPROVAL) {
            throw new ConflictException("Cannot update progress on task pending Team Lead approval");
        }
        if (task.getStatus() == TaskStatus.REJECTED) {
            throw new ConflictException("Cannot update progress on rejected task");
        }
        if (task.getStatus() == TaskStatus.CANCELLED) {
            throw new ConflictException("Cannot update progress on cancelled task");
        }

        if (req.progressPercentage() == null || req.progressPercentage() < 0 || req.progressPercentage() > 100) {
            throw new BadRequestException("progressPercentage must be between 0 and 100");
        }

        int prevProgress = task.getProgressPercentage() != null ? task.getProgressPercentage() : 0;
        TaskStatus prevStatus = task.getStatus();

        TaskStatus newStatus = prevStatus;
        if (req.progressPercentage() == 100 || req.status() == TaskStatus.COMPLETED) {
            newStatus = TaskStatus.COMPLETED;
            task.setProgressPercentage(100);
            task.setCompletedAt(Instant.now());
        } else {
            task.setProgressPercentage(req.progressPercentage());
            if (req.status() != null) {
                newStatus = req.status();
            } else if (prevStatus == TaskStatus.ASSIGNED && req.progressPercentage() > 0) {
                newStatus = TaskStatus.IN_PROGRESS;
            }
        }
        task.setStatus(newStatus);
        task.setUpdatedAt(Instant.now());

        task = taskRepository.saveAndFlush(task);

        // Record immutable audit log
        TaskProgressLog progressLog = TaskProgressLog.builder()
                .taskId(task.getId())
                .updatedById(actor.getId())
                .previousProgress(prevProgress)
                .newProgress(task.getProgressPercentage())
                .previousStatus(prevStatus)
                .newStatus(newStatus)
                .note(req.note())
                .evidenceUrl(req.evidenceUrl())
                .loggedAt(Instant.now())
                .build();
        progressLogRepository.save(progressLog);

        return toDto(task);
    }

    @Transactional
    public TaskDTO approveCrossTeamTask(Long taskId, ApproveCrossTeamTaskRequestDTO req, UserPrincipal actor) {
        if (actor == null || actor.getStatus() != MemberStatus.ACTIVE) {
            throw new ForbiddenException("Active authentication required");
        }

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));

        // Must be currently PENDING_TEAM_LEAD_APPROVAL
        if (task.getStatus() != TaskStatus.PENDING_TEAM_LEAD_APPROVAL) {
            throw new ConflictException("Task is not pending team lead approval: current status is " + task.getStatus());
        }

        // F19: Source Team Lead cannot self-approve unless also GDG_LEAD or target Team Lead
        if (task.getSourceTeamId() != null
                && clearanceEvaluator.isTeamLeadForTeam(actor, task.getSourceTeamId())
                && !actor.hasRole(RoleName.GDG_LEAD)) {
            if (!clearanceEvaluator.isTeamLeadForTeam(actor, task.getTargetTeamId())) {
                throw new ForbiddenException("Source Team Lead cannot self-approve cross-team task");
            }
        }

        // Must be target team's Team Lead or GDG Lead
        boolean isAuthorized = actor.hasRole(RoleName.GDG_LEAD)
                || clearanceEvaluator.isTeamLeadForTeam(actor, task.getTargetTeamId());
        if (!isAuthorized) {
            throw new ForbiddenException("Only target Team Lead or GDG Lead can approve cross-team tasks");
        }

        task.setCrossTeamApprovedById(actor.getId());
        task.setCrossTeamApprovalComment(req.comment());
        task.setCrossTeamApprovedAt(Instant.now());

        if (Boolean.TRUE.equals(req.approved())) {
            task.setStatus(TaskStatus.ASSIGNED);
        } else {
            task.setStatus(TaskStatus.REJECTED);
        }
        task.setUpdatedAt(Instant.now());

        task = taskRepository.saveAndFlush(task);
        return toDto(task);
    }

    @Transactional(readOnly = true)
    public TaskTrackingDTO getTaskTracking(Long taskId, UserPrincipal actor) {
        if (actor == null || actor.getStatus() != MemberStatus.ACTIVE) {
            throw new ForbiddenException("Active authentication required");
        }

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));

        // Clearance check: GDG_LEAD, assignor, assignee, or Team Lead for source / target team
        boolean isAuthorized = actor.hasRole(RoleName.GDG_LEAD)
                || Objects.equals(task.getAssignorId(), actor.getId())
                || Objects.equals(task.getAssigneeId(), actor.getId())
                || clearanceEvaluator.isTeamLeadForTeam(actor, task.getSourceTeamId())
                || clearanceEvaluator.isTeamLeadForTeam(actor, task.getTargetTeamId());
        if (!isAuthorized) {
            throw new ForbiddenException("Not authorized to view tracking for task " + taskId);
        }

        List<TaskProgressLog> logs = progressLogRepository.findByTaskIdOrderByLoggedAtAsc(taskId);
        List<TaskProgressLogDTO> logDtos = logs.stream().map(this::toLogDto).toList();

        boolean isOverdue = task.getDeadline() != null
                && Instant.now().isAfter(task.getDeadline())
                && task.getStatus() != TaskStatus.COMPLETED
                && task.getStatus() != TaskStatus.REJECTED
                && task.getStatus() != TaskStatus.CANCELLED;

        String assignorName = memberRepository.findById(task.getAssignorId())
                .map(Member::getFullName).orElse("Member " + task.getAssignorId());
        String assigneeName = memberRepository.findById(task.getAssigneeId())
                .map(Member::getFullName).orElse("Member " + task.getAssigneeId());

        return new TaskTrackingDTO(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getAssignmentType(),
                task.getAssignorId(),
                assignorName,
                task.getAssigneeId(),
                assigneeName,
                task.getSourceTeamId(),
                task.getTargetTeamId(),
                task.getCollaborationId(),
                task.getPriority(),
                task.getDeadline(),
                task.getStatus(),
                task.getProgressPercentage(),
                isOverdue,
                logDtos,
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getCompletedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<TaskDTO> listTasks(
            Long assigneeId,
            Long assignorId,
            Long teamId,
            TaskStatus status,
            Boolean overdueOnly,
            UserPrincipal actor
    ) {
        if (actor == null || actor.getStatus() != MemberStatus.ACTIVE) {
            throw new ForbiddenException("Active authentication required");
        }

        List<Task> tasks = taskRepository.findWithFilters(
                assigneeId,
                assignorId,
                teamId,
                status,
                overdueOnly,
                Instant.now()
        );

        return tasks.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public TaskDTO getTask(Long taskId, UserPrincipal actor) {
        if (actor == null || actor.getStatus() != MemberStatus.ACTIVE) {
            throw new ForbiddenException("Active authentication required");
        }
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        return toDto(task);
    }

    public TaskDTO toDto(Task t) {
        String assignorName = memberRepository.findById(t.getAssignorId())
                .map(Member::getFullName).orElse(null);
        String assigneeName = memberRepository.findById(t.getAssigneeId())
                .map(Member::getFullName).orElse(null);

        return new TaskDTO(
                t.getId(),
                t.getTitle(),
                t.getDescription(),
                t.getAssignmentType(),
                t.getAssignorId(),
                assignorName,
                t.getAssigneeId(),
                assigneeName,
                t.getSourceTeamId(),
                t.getTargetTeamId(),
                t.getCollaborationId(),
                t.getPriority(),
                t.getDeadline(),
                t.getStatus(),
                t.getProgressPercentage(),
                t.getCrossTeamApprovedById(),
                t.getCrossTeamApprovalComment(),
                t.getCrossTeamApprovedAt(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                t.getCompletedAt()
        );
    }

    public TaskProgressLogDTO toLogDto(TaskProgressLog l) {
        String updaterName = memberRepository.findById(l.getUpdatedById())
                .map(Member::getFullName).orElse(null);

        return new TaskProgressLogDTO(
                l.getId(),
                l.getTaskId(),
                l.getUpdatedById(),
                updaterName,
                l.getPreviousProgress(),
                l.getNewProgress(),
                l.getNewProgress(),
                l.getPreviousStatus(),
                l.getNewStatus(),
                l.getNewStatus(),
                l.getNote(),
                l.getEvidenceUrl(),
                l.getLoggedAt()
        );
    }
}
