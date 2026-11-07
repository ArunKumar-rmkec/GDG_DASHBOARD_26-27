package com.gdgoc.dashboard.team;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "team_consensus_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeamConsensusRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 50)
    private ConsensusActionType actionType;

    @Column(name = "target_team_id")
    private Long targetTeamId;

    @Column(name = "proposed_name", length = 200)
    private String proposedName;

    @Column(name = "proposed_description", columnDefinition = "TEXT")
    private String proposedDescription;

    @Column(name = "parent_team_id")
    private Long parentTeamId;

    @Column(name = "initiated_by_id", nullable = false)
    private Long initiatedById;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private ConsensusStatus status = ConsensusStatus.PENDING;

    @Column(name = "approval_count", nullable = false)
    @Builder.Default
    private Integer approvalCount = 1;

    @Column(name = "required_approvals", nullable = false)
    @Builder.Default
    private Integer requiredApprovals = 3;

    @Column(name = "executed_team_id")
    private Long executedTeamId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "executed_at")
    private Instant executedAt;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("votedAt ASC")
    @Builder.Default
    private List<TeamConsensusVote> votes = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.status == null) {
            this.status = ConsensusStatus.PENDING;
        }
        if (this.approvalCount == null) {
            this.approvalCount = 1;
        }
        if (this.requiredApprovals == null) {
            this.requiredApprovals = 3;
        }
    }
}
