package com.gdgoc.dashboard.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    List<Task> findByAssigneeIdOrderByCreatedAtDesc(Long assigneeId);

    List<Task> findByAssignorIdOrderByCreatedAtDesc(Long assignorId);

    List<Task> findByTargetTeamIdOrderByCreatedAtDesc(Long targetTeamId);

    @Query("SELECT t FROM Task t WHERE " +
           "(:assigneeId IS NULL OR t.assigneeId = :assigneeId) AND " +
           "(:assignorId IS NULL OR t.assignorId = :assignorId) AND " +
           "(:teamId IS NULL OR t.targetTeamId = :teamId OR t.sourceTeamId = :teamId) AND " +
           "(:status IS NULL OR t.status = :status) AND " +
           "(:overdueOnly IS NULL OR :overdueOnly = false OR (t.deadline < :now AND t.status != com.gdgoc.dashboard.task.TaskStatus.COMPLETED AND t.status != com.gdgoc.dashboard.task.TaskStatus.REJECTED AND t.status != com.gdgoc.dashboard.task.TaskStatus.CANCELLED)) " +
           "ORDER BY t.createdAt DESC")
    List<Task> findWithFilters(
            @Param("assigneeId") Long assigneeId,
            @Param("assignorId") Long assignorId,
            @Param("teamId") Long teamId,
            @Param("status") TaskStatus status,
            @Param("overdueOnly") Boolean overdueOnly,
            @Param("now") Instant now
    );
}
