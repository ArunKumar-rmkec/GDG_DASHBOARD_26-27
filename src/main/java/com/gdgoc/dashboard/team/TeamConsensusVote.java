package com.gdgoc.dashboard.team;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "team_consensus_votes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeamConsensusVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false)
    private TeamConsensusRequest request;

    @Column(name = "gdg_lead_id", nullable = false)
    private Long gdgLeadId;

    @Column(name = "approved", nullable = false)
    private boolean approved;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "voted_at", nullable = false)
    @Builder.Default
    private Instant votedAt = Instant.now();

    @PrePersist
    public void prePersist() {
        if (this.votedAt == null) {
            this.votedAt = Instant.now();
        }
    }
}
