package com.gdgoc.dashboard.member;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    Optional<Member> findByGoogleSub(String googleSub);

    @Query("SELECT m FROM Member m LEFT JOIN FETCH m.roles WHERE m.id = :id")
    Optional<Member> findByIdWithRoles(@Param("id") Long id);
}
