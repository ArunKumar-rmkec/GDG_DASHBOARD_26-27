package com.gdgoc.dashboard.mail;

import com.gdgoc.dashboard.mail.MailDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks/email")
@Tag(name = "Webhooks", description = "Public webhooks for mail bounce and delivery events")
public class BounceWebhookController {

    private final MailService mailService;

    public BounceWebhookController(MailService mailService) {
        this.mailService = mailService;
    }

    @PostMapping("/bounce")
    @Operation(summary = "Public webhook to record email bounce or delivery signal")
    public ResponseEntity<EmailDispatchLogDTO> handleBounceWebhook(
            @RequestBody BounceWebhookRequestDTO request
    ) {
        EmailDispatchLogDTO updated = mailService.handleBounceWebhook(request);
        return ResponseEntity.ok(updated);
    }
}
