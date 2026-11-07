package com.gdgoc.dashboard.event;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

public class EventDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateEventRequest {
        @NotBlank(message = "Event name is required")
        private String name;
        private String description;
        @NotNull(message = "Event date is required")
        private Instant eventDate;
        private String venue;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EventResponse {
        private Long id;
        private String name;
        private String description;
        private Instant eventDate;
        private String venue;
        private String status;
        private Long createdById;
        private String createdByName;
        private Instant createdAt;
        private Instant updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AssignEventLeadRequest {
        @NotNull(message = "memberId is required")
        private Long memberId;
        @NotNull(message = "eventRole is required")
        private EventRole eventRole;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EventLeadResponse {
        private Long id;
        private Long eventId;
        private Long memberId;
        private String memberName;
        private String memberEmail;
        private EventRole eventRole;
        private Long assignedById;
        private Instant assignedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RecordAttendanceRequest {
        private Long memberId;
        @NotBlank(message = "participantName is required")
        private String participantName;
        @NotBlank(message = "participantEmail is required")
        @Email(message = "Invalid participantEmail")
        private String participantEmail;
        private String rollNumber;
        private String barcodeToken;
        @NotNull(message = "entryMethod is required")
        private AttendanceEntryMethod entryMethod;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AttendanceResponse {
        private Long id;
        private Long eventId;
        private Long memberId;
        private String participantName;
        private String participantEmail;
        private String rollNumber;
        private String barcodeToken;
        private AttendanceEntryMethod entryMethod;
        private Long checkedInById;
        private Instant checkedInAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ParticipantHistoryResponse {
        private Long eventId;
        private String eventName;
        private Instant eventDate;
        private Long attendanceId;
        private String participantName;
        private String participantEmail;
        private String rollNumber;
        private String barcodeToken;
        private AttendanceEntryMethod entryMethod;
        private Instant checkedInAt;
        private Long certificateId;
        private Boolean certificateGenerated;
        private String certificateCode;
        private String emailDeliveryStatus;
        private String bounceReason;
        private Instant bouncedAt;
    }
}
