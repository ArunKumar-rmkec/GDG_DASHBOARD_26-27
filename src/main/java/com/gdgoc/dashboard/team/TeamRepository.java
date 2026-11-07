package com.gdgoc.dashboard.team;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    List<Team> findByStatusOrderByIdAsc(TeamStatus status);

    List<Team> findByParentTeamIdAndStatusOrderByIdAsc(Long parentTeamId, TeamStatus status);

    long countByParentTeamIdAndStatus(Long parentTeamId, TeamStatus status);

    boolean existsByNameIgnoreCaseAndStatus(String name, TeamStatus status);

    Optional<Team> findByNameIgnoreCaseAndStatus(String name, TeamStatus status);
}
