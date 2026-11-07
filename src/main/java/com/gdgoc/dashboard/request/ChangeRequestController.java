package com.gdgoc.dashboard.request;

import com.gdgoc.dashboard.request.RequestDtos.*;
import com.gdgoc.dashboard.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/change-requests")
public class ChangeRequestController {

    private final ChangeRequestService changeRequestService;

    public ChangeRequestController(ChangeRequestService changeRequestService) {
        this.changeRequestService = changeRequestService;
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("@clearance.canApproveChangeRequest(authentication)")
    public ResponseEntity<MemberChangeRequestDTO> approveChangeRequest(
            @PathVariable Long id,
            @Valid @RequestBody ApproveChangeRequestDTO request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(changeRequestService.approveOrRejectChangeRequest(id, request, principal));
    }

    @GetMapping
    @PreAuthorize("@clearance.isActive(authentication)")
    public ResponseEntity<List<MemberChangeRequestDTO>> listChangeRequests(
            @RequestParam(required = false) ChangeRequestStatus status,
            @RequestParam(required = false) ChangeRequestType requestType,
            @RequestParam(required = false) Long targetMemberId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(changeRequestService.listChangeRequests(status, requestType, targetMemberId, principal));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@clearance.isActive(authentication)")
    public ResponseEntity<MemberChangeRequestDTO> getChangeRequest(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(changeRequestService.getChangeRequest(id, principal));
    }
}
