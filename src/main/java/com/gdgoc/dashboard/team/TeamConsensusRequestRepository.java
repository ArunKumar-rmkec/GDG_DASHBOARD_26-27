package com.gdgoc.dashboard.team;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeamConsensusRequestRepository extends JpaRepository<TeamConsensusRequest, Long> {

    List<TeamConsensusRequest> findAllByOrderByCreatedAtDesc();

    List<TeamConsensusRequest> findByStatusOrderByCreatedAtDesc(ConsensusStatus status);
}
