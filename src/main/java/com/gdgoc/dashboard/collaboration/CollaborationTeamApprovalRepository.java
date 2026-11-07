package com.gdgoc.dashboard.collaboration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CollaborationTeamApprovalRepository extends JpaRepository<CollaborationTeamApproval, Long> {

    @Query("SELECT COUNT(DISTINCT cta.collaboration.id) FROM CollaborationTeamApproval cta " +
           "WHERE cta.teamId = :teamId AND cta.collaboration.status IN ('PENDING_APPROVAL', 'ACTIVE')")
    long countActiveCollaborationsForTeam(@Param("teamId") Long teamId);
}
