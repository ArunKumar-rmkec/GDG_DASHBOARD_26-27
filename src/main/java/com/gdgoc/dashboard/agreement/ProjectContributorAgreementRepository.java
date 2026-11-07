package com.gdgoc.dashboard.agreement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectContributorAgreementRepository extends JpaRepository<ProjectContributorAgreement, Long> {

    boolean existsByVersionIgnoreCase(String version);

    List<ProjectContributorAgreement> findAllByOrderByCreatedAtDesc();
}
