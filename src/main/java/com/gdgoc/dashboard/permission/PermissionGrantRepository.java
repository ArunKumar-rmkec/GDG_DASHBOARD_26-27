package com.gdgoc.dashboard.permission;

import com.gdgoc.dashboard.member.RoleName;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PermissionGrantRepository extends JpaRepository<PermissionGrant, Long> {

    List<PermissionGrant> findByGranteeMemberIdOrderByCreatedAtDesc(Long granteeMemberId);

    List<PermissionGrant> findAllByOrderByCreatedAtDesc();

    List<PermissionGrant> findByGranteeMemberIdAndRevokedFalse(Long granteeMemberId);

    @Query("SELECT g FROM PermissionGrant g WHERE g.revoked = false AND g.status = 'ACTIVE' " +
           "AND (g.granteeMemberId = :memberId OR (g.granteeRole IS NOT NULL AND g.granteeRole IN :roles))")
    List<PermissionGrant> findActiveGrantsForMemberOrRoles(
            @Param("memberId") Long memberId,
            @Param("roles") Collection<RoleName> roles
    );
}
