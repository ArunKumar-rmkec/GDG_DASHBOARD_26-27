package com.gdgoc.dashboard.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventAttendanceRepository extends JpaRepository<EventAttendance, Long> {
    List<EventAttendance> findByEventIdOrderByCheckedInAtAsc(Long eventId);
    boolean existsByEventIdAndParticipantEmailIgnoreCase(Long eventId, String participantEmail);
    boolean existsByEventIdAndBarcodeToken(Long eventId, String barcodeToken);
    Optional<EventAttendance> findByEventIdAndParticipantEmailIgnoreCase(Long eventId, String participantEmail);
    Optional<EventAttendance> findByEventIdAndBarcodeToken(Long eventId, String barcodeToken);
    List<EventAttendance> findByParticipantEmailIgnoreCaseOrderByCheckedInAtDesc(String participantEmail);
    List<EventAttendance> findByMemberIdOrderByCheckedInAtDesc(Long memberId);
}
