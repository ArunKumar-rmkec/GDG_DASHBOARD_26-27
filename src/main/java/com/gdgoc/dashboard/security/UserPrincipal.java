package com.gdgoc.dashboard.security;

import com.gdgoc.dashboard.member.Member;
import com.gdgoc.dashboard.member.MemberStatus;
import com.gdgoc.dashboard.member.RoleName;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
public class UserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final String fullName;
    private final MemberStatus status;
    private final Set<RoleName> roles;
    private final Instant terminatedAt;
    private final Instant letterWindowExpiresAt;
    private final Collection<? extends GrantedAuthority> authorities;

    public UserPrincipal(Member member) {
        this.id = member.getId();
        this.email = member.getEmail();
        this.passwordHash = member.getPasswordHash();
        this.fullName = member.getFullName();
        this.status = member.getStatus();
        this.terminatedAt = member.getTerminatedAt();
        this.letterWindowExpiresAt = member.getLetterWindowExpiresAt();

        if (member.getStatus() == MemberStatus.TERMINATED || member.getStatus() == MemberStatus.INACTIVE) {
            this.roles = Collections.emptySet();
            this.authorities = Collections.emptyList();
        } else {
            this.roles = member.getRoles() != null ? Set.copyOf(member.getRoles()) : Collections.emptySet();
            this.authorities = this.roles.stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r.name()))
                    .collect(Collectors.toUnmodifiableList());
        }
    }

    public boolean hasRole(RoleName role) {
        return status == MemberStatus.ACTIVE && roles.contains(role);
    }

    public boolean isAnyLead() {
        return status == MemberStatus.ACTIVE && roles.stream().anyMatch(RoleName::isLeadRole);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return status != MemberStatus.INACTIVE;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return status != MemberStatus.INACTIVE;
    }
}
