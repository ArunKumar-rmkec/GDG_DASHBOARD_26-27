package com.gdgoc.dashboard.agreement;

import com.gdgoc.dashboard.agreement.AgreementDtos.*;
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
@RequestMapping("/api/v1/agreements")
public class AgreementController {

    private final AgreementService agreementService;

    public AgreementController(AgreementService agreementService) {
        this.agreementService = agreementService;
    }

    @PostMapping
    @PreAuthorize("@clearance.canManageAgreements(authentication)")
    public ResponseEntity<AgreementDTO> createAgreement(
            @Valid @RequestBody CreateAgreementRequestDTO request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(agreementService.createAgreement(request, principal));
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize("@clearance.canManageAgreements(authentication)")
    public ResponseEntity<List<AgreementSignatureDTO>> issueAgreement(
            @PathVariable Long id,
            @Valid @RequestBody IssueAgreementRequestDTO request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(agreementService.issueAgreement(id, request, principal));
    }

    @PostMapping("/signatures/{signatureId}/sign")
    @PreAuthorize("@clearance.isActive(authentication)")
    public ResponseEntity<AgreementSignatureDTO> signAgreement(
            @PathVariable Long signatureId,
            @Valid @RequestBody SignAgreementRequestDTO request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(agreementService.signAgreement(signatureId, request, principal));
    }

    @GetMapping("/signatures/{signatureId}/download")
    @PreAuthorize("@clearance.isActive(authentication)")
    public ResponseEntity<byte[]> downloadSignedAgreement(
            @PathVariable Long signatureId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        byte[] pdf = agreementService.downloadSignedAgreementPdf(signatureId, principal);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"pca-signature-" + signatureId + ".pdf\"")
                .body(pdf);
    }

    @GetMapping
    @PreAuthorize("@clearance.canManageAgreements(authentication)")
    public ResponseEntity<List<AgreementDTO>> listAgreements() {
        return ResponseEntity.ok(agreementService.listAgreements());
    }

    @GetMapping("/signatures")
    @PreAuthorize("@clearance.canManageAgreements(authentication)")
    public ResponseEntity<List<AgreementSignatureDTO>> listAllSignatures() {
        return ResponseEntity.ok(agreementService.listAllSignatures());
    }

    @GetMapping("/signatures/my")
    @PreAuthorize("@clearance.isActive(authentication)")
    public ResponseEntity<List<AgreementSignatureDTO>> listMySignatures(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(agreementService.listMySignatures(principal));
    }
}
