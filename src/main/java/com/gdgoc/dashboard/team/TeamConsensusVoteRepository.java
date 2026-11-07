package com.gdgoc.dashboard.team;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamConsensusVoteRepository extends JpaRepository<TeamConsensusVote, Long> {

    boolean existsByRequest_IdAndGdgLeadId(Long requestId, Long gdgLeadId);
}
