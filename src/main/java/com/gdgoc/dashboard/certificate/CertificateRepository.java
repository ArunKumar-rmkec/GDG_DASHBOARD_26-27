package com.gdgoc.dashboard.certificate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CertificateRepository extends JpaRepository<Certificate, Long> {
    List<Certificate> findByEventIdOrderByGeneratedAtAsc(Long eventId);
    List<Certificate> findByEventIdOrderByIdAsc(Long eventId);
    Optional<Certificate> findByEventIdAndAttendanceId(Long eventId, Long attendanceId);
    Optional<Certificate> findByCertificateCode(String certificateCode);
    Optional<Certificate> findByAttendanceId(Long attendanceId);
    List<Certificate> findByRecipientEmailIgnoreCase(String recipientEmail);
    List<Certificate> findByAttendanceIdIn(Collection<Long> attendanceIds);
}
