package com.gdgoc.dashboard.letter;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "member_letters")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemberLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "letter_type", nullable = false, length = 64)
    private LetterType letterType;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "content_html", nullable = false, columnDefinition = "TEXT")
    private String contentHtml;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "issued_by_id")
    private Long issuedById;

    @Column(name = "issued_at", nullable = false)
    @Builder.Default
    private Instant issuedAt = Instant.now();

    @Column(name = "download_count", nullable = false)
    @Builder.Default
    private int downloadCount = 0;

    @Column(name = "last_downloaded_at")
    private Instant lastDownloadedAt;

    @PrePersist
    public void prePersist() {
        if (this.issuedAt == null) {
            this.issuedAt = Instant.now();
        }
    }
}
