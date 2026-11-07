package com.gdgoc.dashboard.agreement;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "agreement_signatures")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgreementSignature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agreement_id", nullable = false)
    private Long agreementId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "issued_by_id", nullable = false)
    private Long issuedById;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private SignatureStatus status = SignatureStatus.PENDING;

    @Column(name = "signature_text", length = 500)
    private String signatureText;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "pdf_storage_key", length = 500)
    private String pdfStorageKey;

    @Column(name = "issued_at", nullable = false)
    @Builder.Default
    private Instant issuedAt = Instant.now();

    @PrePersist
    public void prePersist() {
        if (this.issuedAt == null) {
            this.issuedAt = Instant.now();
        }
        if (this.status == null) {
            this.status = SignatureStatus.PENDING;
        }
    }
}
