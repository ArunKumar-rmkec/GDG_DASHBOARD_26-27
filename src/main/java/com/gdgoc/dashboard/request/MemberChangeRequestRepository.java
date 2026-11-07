package com.gdgoc.dashboard.request;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemberChangeRequestRepository extends JpaRepository<MemberChangeRequest, Long> {

    List<MemberChangeRequest> findAllByOrderByCreatedAtDesc();

    List<MemberChangeRequest> findByInitiatedByIdOrTargetMemberIdOrderByCreatedAtDesc(Long initiatedById, Long targetMemberId);
}
