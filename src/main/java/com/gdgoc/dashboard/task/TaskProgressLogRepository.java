package com.gdgoc.dashboard.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskProgressLogRepository extends JpaRepository<TaskProgressLog, Long> {

    List<TaskProgressLog> findByTaskIdOrderByLoggedAtAsc(Long taskId);
}
