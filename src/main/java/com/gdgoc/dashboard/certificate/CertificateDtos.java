package com.gdgoc.dashboard.certificate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

public class CertificateDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class GenerateCertificatesRequest {
        private List<Long> attendanceIds;
        private boolean generateForAllAttendees;
        private String templateTitle;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CertificateResponse {
        private Long id;
        private Long eventId;
        private Long attendanceId;
        private String recipientName;
        private String recipientEmail;
        private String participantEmail;
        private String templateTitle;
        private String certificateCode;
        private String storageKey;
        private Long generatedById;
        private Instant generatedAt;
    }
}
