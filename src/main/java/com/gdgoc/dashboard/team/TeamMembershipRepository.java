package com.gdgoc.dashboard.team;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamMembershipRepository extends JpaRepository<TeamMembership, Long> {

    List<TeamMembership> findByTeamIdAndActiveTrueOrderByJoinedAtAsc(Long teamId);

    List<TeamMembership> findByTeamIdAndActiveTrue(Long teamId);

    List<TeamMembership> findByMemberIdAndActiveTrue(Long memberId);

    List<TeamMembership> findByMemberIdAndActiveFalseOrderByLeftAtDesc(Long memberId);

    List<TeamMembership> findByTeamIdAndActiveFalseOrderByLeftAtDesc(Long teamId);

    List<TeamMembership> findByActiveFalseOrderByLeftAtDesc();

    Optional<TeamMembership> findFirstByTeamIdAndMemberIdAndActiveTrue(Long teamId, Long memberId);

    long countByTeamIdAndActiveTrue(Long teamId);

    long countByTeamIdAndActiveFalse(Long teamId);

    boolean existsByTeamIdAndMemberIdAndTeamRoleAndActiveTrue(Long teamId, Long memberId, TeamRole teamRole);

    boolean existsByTeamIdAndMemberIdAndActiveTrue(Long teamId, Long memberId);
}
