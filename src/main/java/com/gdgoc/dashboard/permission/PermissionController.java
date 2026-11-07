package com.gdgoc.dashboard.permission;

import com.gdgoc.dashboard.permission.PermissionDtos.*;
import com.gdgoc.dashboard.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/permissions/grants")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @PostMapping
    @PreAuthorize("@clearance.canGrantPermissions(authentication)")
    public ResponseEntity<PermissionGrantDTO> createGrant(
            @Valid @RequestBody CreatePermissionGrantRequestDTO request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(permissionService.createGrant(request, principal));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("@clearance.isAnyLead(authentication)")
    public ResponseEntity<PermissionGrantDTO> approveGrant(
            @PathVariable Long id,
            @Valid @RequestBody ApproveGrantRequestDTO request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(permissionService.approveCrossTeamGrant(id, request, principal));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@clearance.isAnyLead(authentication)")
    public ResponseEntity<PermissionGrantDTO> revokeGrant(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(permissionService.revokeGrant(id, principal));
    }

    @GetMapping
    @PreAuthorize("@clearance.isAnyLead(authentication)")
    public ResponseEntity<List<PermissionGrantDTO>> listGrants(
            @RequestParam(required = false) Long granteeMemberId
    ) {
        return ResponseEntity.ok(permissionService.listGrants(granteeMemberId));
    }

    @GetMapping("/my")
    @PreAuthorize("@clearance.isActive(authentication)")
    public ResponseEntity<List<PermissionGrantDTO>> listMyGrants(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(permissionService.listMyGrants(principal));
    }
}
