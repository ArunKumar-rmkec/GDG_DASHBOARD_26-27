package com.gdgoc.dashboard.event;

import com.gdgoc.dashboard.certificate.Certificate;
import com.gdgoc.dashboard.certificate.CertificateRepository;
import com.gdgoc.dashboard.common.BadRequestException;
import com.gdgoc.dashboard.common.ConflictException;
import com.gdgoc.dashboard.common.ResourceNotFoundException;
import com.gdgoc.dashboard.event.EventDtos.*;
import com.gdgoc.dashboard.mail.EmailDispatchLog;
import com.gdgoc.dashboard.mail.EmailDispatchLogRepository;
import com.gdgoc.dashboard.member.Member;
import com.gdgoc.dashboard.member.MemberRepository;
import com.gdgoc.dashboard.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final EventLeadAssignmentRepository eventLeadAssignmentRepository;
    private final EventAttendanceRepository attendanceRepository;
    private final CertificateRepository certificateRepository;
    private final EmailDispatchLogRepository dispatchLogRepository;
    private final MemberRepository memberRepository;

    public EventService(
            EventRepository eventRepository,
            EventLeadAssignmentRepository eventLeadAssignmentRepository,
            EventAttendanceRepository attendanceRepository,
            CertificateRepository certificateRepository,
            EmailDispatchLogRepository dispatchLogRepository,
            MemberRepository memberRepository
    ) {
        this.eventRepository = eventRepository;
        this.eventLeadAssignmentRepository = eventLeadAssignmentRepository;
        this.attendanceRepository = attendanceRepository;
        this.certificateRepository = certificateRepository;
        this.dispatchLogRepository = dispatchLogRepository;
        this.memberRepository = memberRepository;
    }

    @Transactional
    public EventResponse createEvent(CreateEventRequest req, UserPrincipal principal) {
        Member creator = memberRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Creator not found with id: " + principal.getId()));

        Event event = Event.builder()
                .name(req.getName().trim())
                .description(req.getDescription())
                .eventDate(req.getEventDate())
                .venue(req.getVenue())
                .status(EventStatus.UPCOMING)
                .createdBy(creator)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        event = eventRepository.save(event);
        return toEventResponse(event);
    }

    @Transactional(readOnly = true)
    public List<EventResponse> getEvents() {
        return eventRepository.findAllByOrderByEventDateDesc().stream()
                .map(this::toEventResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public EventResponse getEvent(Long id) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id: " + id));
        return toEventResponse(event);
    }

    @Transactional
    public EventLeadResponse assignEventLead(Long eventId, AssignEventLeadRequest req, UserPrincipal principal) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id: " + eventId));

        Member targetMember = memberRepository.findById(req.getMemberId())
                .orElseThrow(() -> new BadRequestException("Member not found with id: " + req.getMemberId()));

        Member assigner = memberRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Assigning member not found: " + principal.getId()));

        EventLeadAssignment assignment = eventLeadAssignmentRepository.findByEventIdAndMemberId(eventId, req.getMemberId())
                .orElseGet(() -> EventLeadAssignment.builder()
                        .event(event)
                        .member(targetMember)
                        .assignedBy(assigner)
                        .assignedAt(Instant.now())
                        .build());

        assignment.setEventRole(req.getEventRole());
        assignment = eventLeadAssignmentRepository.save(assignment);

        return toEventLeadResponse(assignment);
    }

    @Transactional
    public AttendanceResponse recordAttendance(Long eventId, RecordAttendanceRequest req, UserPrincipal principal) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id: " + eventId));

        String emailClean = req.getParticipantEmail().trim().toLowerCase(Locale.ROOT);
        if (attendanceRepository.existsByEventIdAndParticipantEmailIgnoreCase(eventId, emailClean)) {
            throw new ConflictException("Participant email already checked in for this event: " + emailClean);
        }

        if (req.getBarcodeToken() != null && !req.getBarcodeToken().isBlank()) {
            String barcodeClean = req.getBarcodeToken().trim();
            if (attendanceRepository.existsByEventIdAndBarcodeToken(eventId, barcodeClean)) {
                throw new ConflictException("Barcode token already checked in for this event: " + barcodeClean);
            }
        }

        Member checkedInBy = memberRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Checked-in by member not found with id: " + principal.getId()));

        Member member = null;
        if (req.getMemberId() != null) {
            member = memberRepository.findById(req.getMemberId()).orElse(null);
        }

        EventAttendance attendance = EventAttendance.builder()
                .event(event)
                .member(member)
                .participantName(req.getParticipantName().trim())
                .participantEmail(emailClean)
                .rollNumber(req.getRollNumber() != null ? req.getRollNumber().trim() : null)
                .barcodeToken(req.getBarcodeToken() != null && !req.getBarcodeToken().isBlank() ? req.getBarcodeToken().trim() : null)
                .entryMethod(req.getEntryMethod())
                .checkedInBy(checkedInBy)
                .checkedInAt(Instant.now())
                .build();

        try {
            attendance = attendanceRepository.saveAndFlush(attendance);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ConflictException("Duplicate attendance check-in detected for this event: " + ex.getMessage());
        }
        return toAttendanceResponse(attendance);
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> getAttendances(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResourceNotFoundException("Event not found with id: " + eventId);
        }
        return attendanceRepository.findByEventIdOrderByCheckedInAtAsc(eventId).stream()
                .map(this::toAttendanceResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ParticipantHistoryResponse> getParticipantHistory(String participantEmail, Long memberId, Long eventId) {
        List<EventAttendance> attendances;
        if (participantEmail != null && !participantEmail.isBlank()) {
            attendances = attendanceRepository.findByParticipantEmailIgnoreCaseOrderByCheckedInAtDesc(participantEmail.trim().toLowerCase(Locale.ROOT));
        } else if (memberId != null) {
            attendances = attendanceRepository.findByMemberIdOrderByCheckedInAtDesc(memberId);
        } else if (eventId != null) {
            attendances = attendanceRepository.findByEventIdOrderByCheckedInAtAsc(eventId);
        } else {
            attendances = attendanceRepository.findAll();
        }

        return attendances.stream().map(att -> {
            Certificate cert = certificateRepository.findByEventIdAndAttendanceId(att.getEvent().getId(), att.getId())
                    .orElse(null);

            EmailDispatchLog dispatchLog = dispatchLogRepository.findFirstByEventIdAndRecipientEmailOrderBySentAtDesc(
                    att.getEvent().getId(), att.getParticipantEmail().trim().toLowerCase(Locale.ROOT)
            ).orElse(null);

            return ParticipantHistoryResponse.builder()
                    .eventId(att.getEvent().getId())
                    .eventName(att.getEvent().getName())
                    .eventDate(att.getEvent().getEventDate())
                    .attendanceId(att.getId())
                    .participantName(att.getParticipantName())
                    .participantEmail(att.getParticipantEmail())
                    .rollNumber(att.getRollNumber())
                    .barcodeToken(att.getBarcodeToken())
                    .entryMethod(att.getEntryMethod())
                    .checkedInAt(att.getCheckedInAt())
                    .certificateId(cert != null ? cert.getId() : null)
                    .certificateGenerated(cert != null)
                    .certificateCode(cert != null ? cert.getCertificateCode() : null)
                    .emailDeliveryStatus(dispatchLog != null && dispatchLog.getDeliveryStatus() != null ? dispatchLog.getDeliveryStatus().name() : null)
                    .bounceReason(dispatchLog != null ? dispatchLog.getBounceReason() : null)
                    .bouncedAt(dispatchLog != null ? dispatchLog.getBouncedAt() : null)
                    .build();
        }).toList();
    }

    private EventResponse toEventResponse(Event e) {
        return EventResponse.builder()
                .id(e.getId())
                .name(e.getName())
                .description(e.getDescription())
                .eventDate(e.getEventDate())
                .venue(e.getVenue())
                .status(e.getStatus() != null ? e.getStatus().name() : "UPCOMING")
                .createdById(e.getCreatedBy() != null ? e.getCreatedBy().getId() : null)
                .createdByName(e.getCreatedBy() != null ? e.getCreatedBy().getFullName() : null)
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private EventLeadResponse toEventLeadResponse(EventLeadAssignment a) {
        return EventLeadResponse.builder()
                .id(a.getId())
                .eventId(a.getEvent().getId())
                .memberId(a.getMember().getId())
                .memberName(a.getMember().getFullName())
                .memberEmail(a.getMember().getEmail())
                .eventRole(a.getEventRole())
                .assignedById(a.getAssignedBy().getId())
                .assignedAt(a.getAssignedAt())
                .build();
    }

    private AttendanceResponse toAttendanceResponse(EventAttendance a) {
        return AttendanceResponse.builder()
                .id(a.getId())
                .eventId(a.getEvent().getId())
                .memberId(a.getMember() != null ? a.getMember().getId() : null)
                .participantName(a.getParticipantName())
                .participantEmail(a.getParticipantEmail())
                .rollNumber(a.getRollNumber())
                .barcodeToken(a.getBarcodeToken())
                .entryMethod(a.getEntryMethod())
                .checkedInById(a.getCheckedInBy().getId())
                .checkedInAt(a.getCheckedInAt())
                .build();
    }
}
