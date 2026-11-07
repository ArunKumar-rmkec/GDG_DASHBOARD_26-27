package com.gdgoc.dashboard.letter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemberLetterRepository extends JpaRepository<MemberLetter, Long> {

    List<MemberLetter> findByMemberIdOrderByIssuedAtDesc(Long memberId);

    long countByMemberId(Long memberId);
}
