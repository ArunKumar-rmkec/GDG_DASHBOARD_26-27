package com.gdgoc.dashboard.collaboration;

import com.gdgoc.dashboard.collaboration.CollaborationDtos.*;
import com.gdgoc.dashboard.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/collaborations")
public class CollaborationController {

    private final CollaborationService collaborationService;

    public CollaborationController(CollaborationService collaborationService) {
        this.collaborationService = collaborationService;
    }

    @PostMapping
    @PreAuthorize("@clearance.canCreateCollaboration(authentication)")
    public ResponseEntity<CollaborationDTO> createCollaboration(
            @Valid @RequestBody CreateCollaborationRequestDTO request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(collaborationService.createCollaboration(request, principal));
    }

    @PostMapping("/{id}/approve-team")
    @PreAuthorize("@clearance.isAnyLead(authentication)")
    public ResponseEntity<CollaborationDTO> approveTeam(
            @PathVariable Long id,
            @Valid @RequestBody ApproveCollaborationTeamRequestDTO request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(collaborationService.approveTeamParticipation(id, request, principal));
    }

    @GetMapping
    @PreAuthorize("@clearance.canMonitorCollaborations(authentication)")
    public ResponseEntity<List<CollaborationDTO>> listCollaborations(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) CollaborationType collaborationType,
            @RequestParam(required = false) CollaborationStatus status
    ) {
        return ResponseEntity.ok(collaborationService.listCollaborations(teamId, collaborationType, status));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@clearance.canMonitorCollaborations(authentication)")
    public ResponseEntity<CollaborationDTO> getCollaboration(@PathVariable Long id) {
        return ResponseEntity.ok(collaborationService.getCollaboration(id));
    }
}
