package com.gdgoc.dashboard.certificate;

import com.gdgoc.dashboard.certificate.CertificateDtos.*;
import com.gdgoc.dashboard.common.BadRequestException;
import com.gdgoc.dashboard.common.ResourceNotFoundException;
import com.gdgoc.dashboard.common.StorageService;
import com.gdgoc.dashboard.event.Event;
import com.gdgoc.dashboard.event.EventAttendance;
import com.gdgoc.dashboard.event.EventAttendanceRepository;
import com.gdgoc.dashboard.event.EventRepository;
import com.gdgoc.dashboard.letter.PdfRenderService;
import com.gdgoc.dashboard.member.Member;
import com.gdgoc.dashboard.member.MemberRepository;
import com.gdgoc.dashboard.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class CertificateService {

    private final CertificateRepository certificateRepository;
    private final EventRepository eventRepository;
    private final EventAttendanceRepository attendanceRepository;
    private final StorageService storageService;
    private final PdfRenderService pdfRenderService;
    private final MemberRepository memberRepository;

    public CertificateService(
            CertificateRepository certificateRepository,
            EventRepository eventRepository,
            EventAttendanceRepository attendanceRepository,
            StorageService storageService,
            PdfRenderService pdfRenderService,
            MemberRepository memberRepository
    ) {
        this.certificateRepository = certificateRepository;
        this.eventRepository = eventRepository;
        this.attendanceRepository = attendanceRepository;
        this.storageService = storageService;
        this.pdfRenderService = pdfRenderService;
        this.memberRepository = memberRepository;
    }

    @Transactional
    public List<CertificateResponse> generateCertificates(Long eventId, GenerateCertificatesRequest req, UserPrincipal principal) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id: " + eventId));

        List<EventAttendance> targets = new ArrayList<>();
        if (req.isGenerateForAllAttendees()) {
            targets = attendanceRepository.findByEventIdOrderByCheckedInAtAsc(eventId);
            if (targets.isEmpty()) {
                throw new BadRequestException("No attendees found for event: " + eventId);
            }
        } else {
            if (req.getAttendanceIds() == null || req.getAttendanceIds().isEmpty()) {
                throw new BadRequestException("attendanceIds must not be empty when generateForAllAttendees is false");
            }
            for (Long attId : req.getAttendanceIds()) {
                EventAttendance att = attendanceRepository.findById(attId)
                        .orElseThrow(() -> new BadRequestException("Attendance record not found: " + attId));
                if (!eventId.equals(att.getEvent().getId())) {
                    throw new BadRequestException("Attendance " + attId + " does not belong to event " + eventId);
                }
                targets.add(att);
            }
        }

        Member generator = memberRepository.findById(principal.getId()).orElse(null);

        String templateTitle = (req.getTemplateTitle() != null && !req.getTemplateTitle().isBlank())
                ? req.getTemplateTitle().trim()
                : "Certificate of Participation";

        String formattedDate = (event.getEventDate() != null)
                ? DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(event.getEventDate())
                : "2026-11-20";

        List<CertificateResponse> responses = new ArrayList<>();

        for (EventAttendance att : targets) {
            Optional<Certificate> existing = certificateRepository.findByEventIdAndAttendanceId(eventId, att.getId());
            if (existing.isPresent()) {
                responses.add(toCertificateResponse(existing.get()));
                continue;
            }

            String certCode = "GDGOC-CERT-" + UUID.randomUUID().toString().toUpperCase();
            byte[] pdfBytes = pdfRenderService.renderCertificatePdf(
                    templateTitle,
                    att.getParticipantName(),
                    event.getName(),
                    formattedDate,
                    certCode
            );

            String storageKey = "certificates/" + eventId + "/" + certCode + ".pdf";
            storageService.saveBytes(storageKey, pdfBytes);

            Certificate certificate = Certificate.builder()
                    .event(event)
                    .attendance(att)
                    .recipientName(att.getParticipantName())
                    .recipientEmail(att.getParticipantEmail())
                    .templateTitle(templateTitle)
                    .certificateCode(certCode)
                    .storageKey(storageKey)
                    .generatedBy(generator)
                    .generatedAt(Instant.now())
                    .build();

            certificate = certificateRepository.save(certificate);
            responses.add(toCertificateResponse(certificate));
        }

        return responses;
    }

    @Transactional(readOnly = true)
    public List<CertificateResponse> getCertificates(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResourceNotFoundException("Event not found with id: " + eventId);
        }
        return certificateRepository.findByEventIdOrderByGeneratedAtAsc(eventId).stream()
                .map(this::toCertificateResponse)
                .toList();
    }

    @Transactional
    public byte[] downloadCertificateBytes(Long certificateId, UserPrincipal principal) {
        Certificate cert = certificateRepository.findById(certificateId)
                .orElseThrow(() -> new ResourceNotFoundException("Certificate not found with id: " + certificateId));

        byte[] pdfBytes = storageService.readBytes(cert.getStorageKey());
        if (pdfBytes == null || pdfBytes.length == 0) {
            String formattedDate = cert.getEvent().getEventDate() != null
                    ? DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(cert.getEvent().getEventDate())
                    : "2026-11-20";

            pdfBytes = pdfRenderService.renderCertificatePdf(
                    cert.getTemplateTitle(),
                    cert.getRecipientName(),
                    cert.getEvent().getName(),
                    formattedDate,
                    cert.getCertificateCode()
            );
            storageService.saveBytes(cert.getStorageKey(), pdfBytes);
        }

        return pdfBytes;
    }

    private CertificateResponse toCertificateResponse(Certificate c) {
        return CertificateResponse.builder()
                .id(c.getId())
                .eventId(c.getEvent().getId())
                .attendanceId(c.getAttendance().getId())
                .recipientName(c.getRecipientName())
                .recipientEmail(c.getRecipientEmail())
                .participantEmail(c.getRecipientEmail())
                .templateTitle(c.getTemplateTitle())
                .certificateCode(c.getCertificateCode())
                .storageKey(c.getStorageKey())
                .generatedById(c.getGeneratedBy() != null ? c.getGeneratedBy().getId() : null)
                .generatedAt(c.getGeneratedAt())
                .build();
    }
}
