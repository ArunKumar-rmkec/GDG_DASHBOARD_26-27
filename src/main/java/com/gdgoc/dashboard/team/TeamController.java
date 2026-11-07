package com.gdgoc.dashboard.team;

import com.gdgoc.dashboard.security.UserPrincipal;
import com.gdgoc.dashboard.team.TeamDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @PostMapping("/consensus-requests")
    @PreAuthorize("@clearance.isGdgLead(authentication)")
    public ResponseEntity<TeamConsensusRequestDTO> createConsensusRequest(
            @Valid @RequestBody CreateConsensusRequestDTO request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(teamService.initiateConsensusRequest(request, principal));
    }

    @PostMapping("/consensus-requests/{id}/vote")
    @PreAuthorize("@clearance.isGdgLead(authentication)")
    public ResponseEntity<TeamConsensusRequestDTO> voteOnConsensusRequest(
            @PathVariable Long id,
            @Valid @RequestBody ConsensusVoteRequestDTO request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(teamService.voteOnConsensusRequest(id, request, principal));
    }

    @GetMapping("/consensus-requests")
    @PreAuthorize("@clearance.isGdgLead(authentication)")
    public ResponseEntity<List<TeamConsensusRequestDTO>> listConsensusRequests(
            @RequestParam(required = false) ConsensusStatus status
    ) {
        return ResponseEntity.ok(teamService.listConsensusRequests(status));
    }

    @GetMapping("/consensus-requests/{id}")
    @PreAuthorize("@clearance.isGdgLead(authentication)")
    public ResponseEntity<TeamConsensusRequestDTO> getConsensusRequest(@PathVariable Long id) {
        return ResponseEntity.ok(teamService.getConsensusRequest(id));
    }

    @GetMapping("/stats")
    @PreAuthorize("@clearance.canViewTeamStats(authentication)")
    public ResponseEntity<List<TeamStatsDTO>> getAllTeamStats(
            @RequestParam(defaultValue = "false") boolean includeDeleted
    ) {
        return ResponseEntity.ok(teamService.getAllTeamStats(includeDeleted));
    }

    @GetMapping("/{id}/stats")
    @PreAuthorize("@clearance.canViewTeamStats(authentication)")
    public ResponseEntity<TeamStatsDTO> getTeamStats(@PathVariable Long id) {
        return ResponseEntity.ok(teamService.getTeamStats(id));
    }

    @GetMapping("/past-members")
    @PreAuthorize("@clearance.canViewPastMembers(authentication)")
    public ResponseEntity<List<TeamMembershipHistoryDTO>> getAllPastMembers(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) String exitReason
    ) {
        return ResponseEntity.ok(teamService.getAllPastMembers(teamId, memberId, exitReason));
    }

    @GetMapping("/{id}/past-members")
    @PreAuthorize("@clearance.canViewPastMembers(authentication)")
    public ResponseEntity<List<TeamMembershipHistoryDTO>> getTeamPastMembers(@PathVariable Long id) {
        return ResponseEntity.ok(teamService.getTeamPastMembers(id));
    }

    @GetMapping
    @PreAuthorize("@clearance.isActive(authentication)")
    public ResponseEntity<List<TeamDTO>> listTeams(
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(required = false) Long parentTeamId
    ) {
        return ResponseEntity.ok(teamService.listTeams(includeDeleted, parentTeamId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@clearance.isActive(authentication)")
    public ResponseEntity<TeamDTO> getTeam(@PathVariable Long id) {
        return ResponseEntity.ok(teamService.getTeam(id));
    }

    @PostMapping("/{teamId}/members")
    @PreAuthorize("@clearance.canManageTeamMembers(authentication, #teamId)")
    public ResponseEntity<TeamMembershipHistoryDTO> assignMember(
            @PathVariable Long teamId,
            @Valid @RequestBody AssignTeamMemberRequestDTO request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(teamService.assignMemberToTeam(teamId, request));
    }

    @DeleteMapping("/{teamId}/members/{memberId}")
    @PreAuthorize("@clearance.canManageTeamMembers(authentication, #teamId)")
    public ResponseEntity<TeamMembershipHistoryDTO> removeMember(
            @PathVariable Long teamId,
            @PathVariable Long memberId
    ) {
        return ResponseEntity.ok(teamService.removeMemberFromTeam(teamId, memberId));
    }
}
