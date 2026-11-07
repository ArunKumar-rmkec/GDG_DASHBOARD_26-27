package com.gdgoc.dashboard.certificate;

import com.gdgoc.dashboard.certificate.CertificateDtos.*;
import com.gdgoc.dashboard.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Certificates", description = "Certificate Generation & Download API (Feature F24)")
public class CertificateController {

    private final CertificateService certificateService;

    public CertificateController(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    @PostMapping("/api/v1/events/{eventId:[0-9]+}/certificates/generate")
    @PreAuthorize("@clearance.canManageEvent(authentication, #eventId)")
    @Operation(summary = "Generate PDF certificates for attendees", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<CertificateResponse>> generateCertificates(
            @PathVariable Long eventId,
            @Valid @RequestBody GenerateCertificatesRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(certificateService.generateCertificates(eventId, request, principal));
    }

    @GetMapping("/api/v1/events/{eventId:[0-9]+}/certificates")
    @PreAuthorize("@clearance.canManageEvent(authentication, #eventId)")
    @Operation(summary = "Get all certificates generated for an event", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<CertificateResponse>> getCertificates(@PathVariable Long eventId) {
        return ResponseEntity.ok(certificateService.getCertificates(eventId));
    }

    @GetMapping("/api/v1/certificates/{certificateId:[0-9]+}/download")
    @Operation(summary = "Download certificate as PDF binary", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<byte[]> downloadCertificate(
            @PathVariable Long certificateId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        byte[] pdfBytes = certificateService.downloadCertificateBytes(certificateId, principal);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"certificate-" + certificateId + ".pdf\"")
                .body(pdfBytes);
    }
}
