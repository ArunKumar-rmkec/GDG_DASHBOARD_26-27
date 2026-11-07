package com.gdgoc.dashboard.collaboration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollaborationRepository extends JpaRepository<Collaboration, Long> {

    List<Collaboration> findAllByOrderByCreatedAtDesc();
}
