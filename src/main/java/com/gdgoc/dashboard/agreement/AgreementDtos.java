package com.gdgoc.dashboard.agreement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.Instant;
import java.util.List;

public class AgreementDtos {

    public record CreateAgreementRequestDTO(
            @NotBlank String version,
            @NotBlank String title,
            @NotBlank String contentHtml
    ) {}

    public record IssueAgreementRequestDTO(
            @NotEmpty List<Long> memberIds
    ) {}

    public record SignAgreementRequestDTO(
            @NotBlank String signatureText
    ) {}

    public record AgreementDTO(
            Long id,
            String version,
            String title,
            String contentHtml,
            boolean active,
            Long createdById,
            Instant createdAt
    ) {}

    public record AgreementSignatureDTO(
            Long id,
            Long agreementId,
            String agreementVersion,
            String agreementTitle,
            Long memberId,
            String memberName,
            String memberEmail,
            Long issuedById,
            SignatureStatus status,
            String signatureText,
            Instant signedAt,
            String pdfStorageKey,
            Instant issuedAt
    ) {}
}
