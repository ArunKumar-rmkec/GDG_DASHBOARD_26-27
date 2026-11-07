package com.gdgoc.dashboard.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventLeadAssignmentRepository extends JpaRepository<EventLeadAssignment, Long> {
    Optional<EventLeadAssignment> findByEventIdAndMemberId(Long eventId, Long memberId);
    List<EventLeadAssignment> findByEventId(Long eventId);
    List<EventLeadAssignment> findByMemberId(Long memberId);
    boolean existsByEventIdAndMemberId(Long eventId, Long memberId);
    boolean existsByEventIdAndMemberIdAndEventRole(Long eventId, Long memberId, EventRole eventRole);
    boolean existsByMemberId(Long memberId);
}
