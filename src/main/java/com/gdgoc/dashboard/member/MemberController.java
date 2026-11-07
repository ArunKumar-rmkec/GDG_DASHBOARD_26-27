package com.gdgoc.dashboard.member;

import com.gdgoc.dashboard.member.MemberDtos.*;
import com.gdgoc.dashboard.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping
    @PreAuthorize("@clearance.canViewMemberDetails(authentication)")
    public ResponseEntity<List<MemberDetailDTO>> listMembers(
            @RequestParam(required = false) MemberStatus status,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) RoleName role,
            @RequestParam(required = false) String search
    ) {
        return ResponseEntity.ok(memberService.listMembers(status, teamId, role, search));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@clearance.canViewMemberDetails(authentication)")
    public ResponseEntity<MemberDetailDTO> getMember(@PathVariable Long id) {
        return ResponseEntity.ok(memberService.getMemberDetail(id));
    }

    @PostMapping
    @PreAuthorize("@clearance.canInitiateBioDataOrMemberChange(authentication)")
    public ResponseEntity<MemberMutationResponseDTO> addMember(
            @Valid @RequestBody HrAddMemberRequestDTO request,
            Authentication authentication,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        MemberMutationResponseDTO res = memberService.addMemberOrStage(request, authentication, principal);
        HttpStatus status = "EXECUTED".equals(res.executionStatus()) ? HttpStatus.CREATED : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(res);
    }

    @PutMapping("/{id}/bio-data")
    @PreAuthorize("@clearance.canInitiateBioDataOrMemberChange(authentication)")
    public ResponseEntity<MemberMutationResponseDTO> updateBioData(
            @PathVariable Long id,
            @Valid @RequestBody BioDataUpdateRequestDTO request,
            Authentication authentication,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        MemberMutationResponseDTO res = memberService.updateBioDataOrStage(id, request, authentication, principal);
        HttpStatus status = "EXECUTED".equals(res.executionStatus()) ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(res);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@clearance.canInitiateBioDataOrMemberChange(authentication)")
    public ResponseEntity<MemberMutationResponseDTO> removeMember(
            @PathVariable Long id,
            @RequestParam(required = false) String reason,
            Authentication authentication,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        MemberMutationResponseDTO res = memberService.removeMemberOrStage(id, reason, authentication, principal);
        HttpStatus status = "EXECUTED".equals(res.executionStatus()) ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(res);
    }

    @PostMapping("/{id}/terminate")
    @PreAuthorize("@clearance.canTerminateMember(authentication)")
    public ResponseEntity<MemberDetailDTO> terminateMember(
            @PathVariable Long id,
            @RequestBody(required = false) TerminateMemberRequestDTO request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(memberService.terminateMember(id, request, principal));
    }
}
