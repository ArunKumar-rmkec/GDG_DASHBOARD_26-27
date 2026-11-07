package com.gdgoc.dashboard.letter;

import com.gdgoc.dashboard.letter.LetterDtos.*;
import com.gdgoc.dashboard.member.MemberDtos.BioDataUpdateRequestDTO;
import com.gdgoc.dashboard.request.ChangeRequestService;
import com.gdgoc.dashboard.request.RequestDtos.MemberChangeRequestDTO;
import com.gdgoc.dashboard.request.RequestDtos.TeamChangeRequestDTO;
import com.gdgoc.dashboard.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ProfileAndLetterController {

    private final LetterService letterService;
    private final ChangeRequestService changeRequestService;

    public ProfileAndLetterController(LetterService letterService, ChangeRequestService changeRequestService) {
        this.letterService = letterService;
        this.changeRequestService = changeRequestService;
    }

    @GetMapping("/profile/me")
    public ResponseEntity<MemberProfileDTO> getMyProfile(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(letterService.getMyProfile(principal));
    }

    @GetMapping("/profile/me/letters")
    public ResponseEntity<List<MemberLetterDTO>> listMyLetters(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(letterService.listMyLetters(principal));
    }

    @GetMapping("/profile/me/letters/{letterId}/download")
    public ResponseEntity<byte[]> downloadMyLetter(
            @PathVariable Long letterId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        byte[] pdf = letterService.downloadLetter(letterId, principal);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"letter-" + letterId + ".pdf\"")
                .body(pdf);
    }

    @GetMapping("/profile/me/letters/{letterId}/signed-url")
    public ResponseEntity<SignedUrlResponseDTO> generateSignedUrl(
            @PathVariable Long letterId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(letterService.generateSignedUrl(letterId, principal));
    }

    @GetMapping("/letters/signed-download/{token}")
    public ResponseEntity<byte[]> downloadBySignedToken(@PathVariable String token) {
        byte[] pdf = letterService.downloadBySignedToken(token);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"signed-letter.pdf\"")
                .body(pdf);
    }

    @PostMapping("/letters/issue")
    @PreAuthorize("@clearance.canIssueLetters(authentication)")
    public ResponseEntity<MemberLetterDTO> issueLetter(
            @Valid @RequestBody IssueLetterRequestDTO request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(letterService.issueLetter(request, principal));
    }

    @PostMapping("/profile/me/bio-data-requests")
    @PreAuthorize("@clearance.isActive(authentication)")
    public ResponseEntity<MemberChangeRequestDTO> requestBioDataChange(
            @Valid @RequestBody BioDataUpdateRequestDTO request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(changeRequestService.createSelfServiceBioDataRequest(request, principal));
    }

    @PostMapping("/profile/me/team-change-requests")
    @PreAuthorize("@clearance.isActive(authentication)")
    public ResponseEntity<MemberChangeRequestDTO> requestTeamChange(
            @Valid @RequestBody TeamChangeRequestDTO request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(changeRequestService.createSelfServiceTeamChangeRequest(request, principal));
    }
}
