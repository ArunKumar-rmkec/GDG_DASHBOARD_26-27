package com.gdgoc.dashboard.event;

import com.gdgoc.dashboard.event.EventDtos.*;
import com.gdgoc.dashboard.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events", description = "Event Management, Leads & Attendance API (Feature F23)")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    @PreAuthorize("@clearance.canManageEvents(authentication)")
    @Operation(summary = "Create a new event", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<EventResponse> createEvent(
            @Valid @RequestBody CreateEventRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(eventService.createEvent(request, principal));
    }

    @GetMapping
    @PreAuthorize("@clearance.canManageEvents(authentication)")
    @Operation(summary = "List all events", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<EventResponse>> getEvents() {
        return ResponseEntity.ok(eventService.getEvents());
    }

    @GetMapping("/participants/history")
    @PreAuthorize("@clearance.canManageEvents(authentication)")
    @Operation(summary = "Get participant history across all events", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<ParticipantHistoryResponse>> getParticipantHistory(
            @RequestParam(required = false) String participantEmail,
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) Long eventId
    ) {
        return ResponseEntity.ok(eventService.getParticipantHistory(participantEmail, memberId, eventId));
    }

    @GetMapping("/{id:[0-9]+}")
    @PreAuthorize("@clearance.canManageEvents(authentication)")
    @Operation(summary = "Get event details by ID", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<EventResponse> getEvent(@PathVariable Long id) {
        return ResponseEntity.ok(eventService.getEvent(id));
    }

    @PostMapping("/{id:[0-9]+}/leads")
    @PreAuthorize("@clearance.canAssignEventLead(authentication, #id)")
    @Operation(summary = "Assign event-specific lead or member role", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<EventLeadResponse> assignLead(
            @PathVariable Long id,
            @Valid @RequestBody AssignEventLeadRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(eventService.assignEventLead(id, request, principal));
    }

    @PostMapping("/{id:[0-9]+}/attendance")
    @PreAuthorize("@clearance.canManageEvent(authentication, #id)")
    @Operation(summary = "Record barcode or manual attendance check-in", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<AttendanceResponse> recordAttendance(
            @PathVariable Long id,
            @Valid @RequestBody RecordAttendanceRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(eventService.recordAttendance(id, request, principal));
    }

    @GetMapping("/{id:[0-9]+}/attendance")
    @PreAuthorize("@clearance.canManageEvent(authentication, #id)")
    @Operation(summary = "Get all attendees for an event", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<AttendanceResponse>> getAttendance(@PathVariable Long id) {
        return ResponseEntity.ok(eventService.getAttendances(id));
    }
}
