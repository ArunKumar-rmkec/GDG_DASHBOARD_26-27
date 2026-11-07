package com.gdgoc.dashboard.agreement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgreementSignatureRepository extends JpaRepository<AgreementSignature, Long> {

    Optional<AgreementSignature> findByAgreementIdAndMemberId(Long agreementId, Long memberId);

    List<AgreementSignature> findByMemberIdOrderByIssuedAtDesc(Long memberId);

    List<AgreementSignature> findAllByOrderByIssuedAtDesc();

    long countByMemberIdAndStatus(Long memberId, SignatureStatus status);

    long countByMemberId(Long memberId);
}
