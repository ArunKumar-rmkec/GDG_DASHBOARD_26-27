package com.gdgoc.dashboard.mail;

import com.gdgoc.dashboard.mail.MailDtos.*;
import com.gdgoc.dashboard.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Certificate Mailing & Bounce Tracking", description = "Endpoints for certificate mailing, dispatch logs, and bounce reporting")
public class MailController {

    private final MailService mailService;

    public MailController(MailService mailService) {
        this.mailService = mailService;
    }

    @PostMapping("/{id:[0-9]+}/certificates/mail")
    @PreAuthorize("@clearance.canManageEvent(authentication, #id)")
    @Operation(summary = "Mail certificates with CC/BCC", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<EmailDispatchLogDTO>> mailCertificates(
            @PathVariable Long id,
            @RequestBody MailCertificatesRequestDTO request,
            @AuthenticationPrincipal UserPrincipal actor
    ) {
        List<EmailDispatchLogDTO> logs = mailService.mailCertificates(id, request, actor);
        return ResponseEntity.status(HttpStatus.OK).body(logs);
    }

    @GetMapping("/{id:[0-9]+}/mail-logs")
    @PreAuthorize("@clearance.canManageEvent(authentication, #id)")
    @Operation(summary = "Get mail dispatch logs for event", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<EmailDispatchLogDTO>> getEventMailLogs(
            @PathVariable Long id,
            @RequestParam(required = false) EmailDeliveryStatus status
    ) {
        List<EmailDispatchLogDTO> logs = mailService.getEventMailLogs(id, status);
        return ResponseEntity.ok(logs);
    }

    @GetMapping("/mail-bounces")
    @PreAuthorize("@clearance.canManageEvents(authentication)")
    @Operation(summary = "Get mail bounce records across events or filtered by eventId", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<EmailDispatchLogDTO>> getMailBounces(
            @RequestParam(required = false) Long eventId
    ) {
        List<EmailDispatchLogDTO> bounces = mailService.getMailBounces(eventId);
        return ResponseEntity.ok(bounces);
    }
}
