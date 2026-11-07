package com.gdgoc.dashboard.letter;

import com.gdgoc.dashboard.common.ForbiddenException;
import com.gdgoc.dashboard.common.ResourceNotFoundException;
import com.gdgoc.dashboard.common.StorageService;
import com.gdgoc.dashboard.letter.LetterDtos.*;
import com.gdgoc.dashboard.member.Member;
import com.gdgoc.dashboard.member.MemberRepository;
import com.gdgoc.dashboard.member.MemberStatus;
import com.gdgoc.dashboard.member.RoleName;
import com.gdgoc.dashboard.security.UserPrincipal;
import com.gdgoc.dashboard.team.TeamDtos.TeamMembershipHistoryDTO;
import com.gdgoc.dashboard.team.TeamMembershipRepository;
import com.gdgoc.dashboard.team.TeamService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class LetterService {

    private final MemberLetterRepository memberLetterRepository;
    private final MemberRepository memberRepository;
    private final TeamMembershipRepository teamMembershipRepository;
    private final TeamService teamService;
    private final PdfRenderService pdfRenderService;
    private final StorageService storageService;
    private final SignedUrlService signedUrlService;

    public LetterService(
            MemberLetterRepository memberLetterRepository,
            MemberRepository memberRepository,
            TeamMembershipRepository teamMembershipRepository,
            TeamService teamService,
            PdfRenderService pdfRenderService,
            StorageService storageService,
            SignedUrlService signedUrlService
    ) {
        this.memberLetterRepository = memberLetterRepository;
        this.memberRepository = memberRepository;
        this.teamMembershipRepository = teamMembershipRepository;
        this.teamService = teamService;
        this.pdfRenderService = pdfRenderService;
        this.storageService = storageService;
        this.signedUrlService = signedUrlService;
    }

    @Transactional(readOnly = true)
    public MemberProfileDTO getMyProfile(UserPrincipal principal) {
        Member member = memberRepository.findByIdWithRoles(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Member not found: " + principal.getId()));

        List<TeamMembershipHistoryDTO> activeTeams = teamMembershipRepository
                .findByMemberIdAndActiveTrue(member.getId())
                .stream()
                .map(teamService::toMembershipHistoryDto)
                .toList();

        List<TeamMembershipHistoryDTO> pastTeams = teamMembershipRepository
                .findByMemberIdAndActiveFalseOrderByLeftAtDesc(member.getId())
                .stream()
                .map(teamService::toMembershipHistoryDto)
                .toList();

        boolean windowActive = true;
        if (member.getStatus() == MemberStatus.TERMINATED) {
            windowActive = member.getLetterWindowExpiresAt() != null
                    && !Instant.now().isAfter(member.getLetterWindowExpiresAt());
        }

        Set<RoleName> effectiveRoles = member.getStatus() == MemberStatus.TERMINATED
                ? Collections.emptySet()
                : member.getRoles();

        long lettersCount = memberLetterRepository.countByMemberId(member.getId());
        List<Long> teamIds = activeTeams.stream()
                .map(TeamMembershipHistoryDTO::teamId)
                .toList();

        return new MemberProfileDTO(
                member.getId(),
                member.getEmail(),
                member.getFullName(),
                member.getRollNumber(),
                member.getDepartment(),
                member.getAcademicYear(),
                member.getPhone(),
                member.getBio(),
                member.getSkills(),
                member.getGithubUrl(),
                member.getLinkedinUrl(),
                member.getStatus(),
                effectiveRoles,
                activeTeams,
                pastTeams,
                teamIds,
                member.getTerminatedAt(),
                member.getTerminationReason(),
                member.getLetterWindowExpiresAt(),
                windowActive,
                lettersCount
        );
    }

    @Transactional
    public MemberLetterDTO issueLetter(IssueLetterRequestDTO req, UserPrincipal actor) {
        Member member = memberRepository.findById(req.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Member not found: " + req.memberId()));

        Instant now = Instant.now();
        byte[] pdfBytes = pdfRenderService.renderLetterPdf(member, req.letterType(), req.title().trim(), req.contentHtml(), now);
        String storageKey = "letters/letter-" + member.getId() + "-" + UUID.randomUUID() + ".pdf";
        storageService.saveBytes(storageKey, pdfBytes);

        MemberLetter letter = MemberLetter.builder()
                .memberId(member.getId())
                .letterType(req.letterType())
                .title(req.title().trim())
                .contentHtml(req.contentHtml())
                .storageKey(storageKey)
                .issuedById(actor.getId())
                .issuedAt(now)
                .downloadCount(0)
                .build();
        letter = memberLetterRepository.save(letter);
        return toDto(letter, member);
    }

    @Transactional
    public MemberLetter issueTerminationLetter(Member member, Long issuerId, String reason, Instant issuedAt) {
        String title = "Official Notice of Termination — " + member.getFullName();
        String contentHtml = "<p>This letter confirms that the tenure of <strong>" + member.getFullName()
                + "</strong> at GDGoC Campus has been terminated effective " + issuedAt
                + ". Reason: " + (reason != null ? reason : "Administrative Termination")
                + ". Your letter download access remains active for 10 days until "
                + member.getLetterWindowExpiresAt() + ".</p>";

        byte[] pdfBytes = pdfRenderService.renderLetterPdf(member, LetterType.TERMINATION, title, contentHtml, issuedAt);
        String storageKey = "letters/termination-" + member.getId() + "-" + UUID.randomUUID() + ".pdf";
        storageService.saveBytes(storageKey, pdfBytes);

        MemberLetter letter = MemberLetter.builder()
                .memberId(member.getId())
                .letterType(LetterType.TERMINATION)
                .title(title)
                .contentHtml(contentHtml)
                .storageKey(storageKey)
                .issuedById(issuerId)
                .issuedAt(issuedAt)
                .downloadCount(0)
                .build();
        return memberLetterRepository.save(letter);
    }

    @Transactional(readOnly = true)
    public List<MemberLetterDTO> listMyLetters(UserPrincipal principal) {
        Member member = memberRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Member not found: " + principal.getId()));
        enforceWindowIfTerminated(member);

        return memberLetterRepository.findByMemberIdOrderByIssuedAtDesc(member.getId())
                .stream()
                .map(l -> toDto(l, member))
                .toList();
    }

    @Transactional
    public byte[] downloadLetter(Long letterId, UserPrincipal principal) {
        MemberLetter letter = memberLetterRepository.findById(letterId)
                .orElseThrow(() -> new ResourceNotFoundException("Letter not found: " + letterId));

        boolean isOwner = letter.getMemberId().equals(principal.getId());
        boolean isAdmin = principal.hasRole(RoleName.GDG_LEAD) || principal.hasRole(RoleName.HR_LEAD);
        if (!isOwner && !isAdmin) {
            throw new ForbiddenException("Not authorized to download this letter");
        }

        Member owner = memberRepository.findById(letter.getMemberId())
                .orElseThrow(() -> new ResourceNotFoundException("Member not found: " + letter.getMemberId()));
        if (isOwner) {
            enforceWindowIfTerminated(owner);
        }

        return loadAndIncrementDownload(letter, owner);
    }

    @Transactional(readOnly = true)
    public SignedUrlResponseDTO generateSignedUrl(Long letterId, UserPrincipal principal) {
        MemberLetter letter = memberLetterRepository.findById(letterId)
                .orElseThrow(() -> new ResourceNotFoundException("Letter not found: " + letterId));

        boolean isOwner = letter.getMemberId().equals(principal.getId());
        boolean isAdmin = principal.hasRole(RoleName.GDG_LEAD) || principal.hasRole(RoleName.HR_LEAD);
        if (!isOwner && !isAdmin) {
            throw new ForbiddenException("Not authorized to generate a signed download URL for this letter");
        }

        Member owner = memberRepository.findById(letter.getMemberId())
                .orElseThrow(() -> new ResourceNotFoundException("Member not found: " + letter.getMemberId()));
        enforceWindowIfTerminated(owner);

        Instant expiresAt = (owner.getStatus() == MemberStatus.TERMINATED && owner.getLetterWindowExpiresAt() != null)
                ? owner.getLetterWindowExpiresAt()
                : Instant.now().plus(10, ChronoUnit.DAYS);

        String token = signedUrlService.generateSignedToken(letter.getId(), owner.getId(), expiresAt);
        String signedUrl = "/api/v1/letters/signed-download/" + token;
        return new SignedUrlResponseDTO(letter.getId(), token, signedUrl, expiresAt);
    }

    @Transactional
    public byte[] downloadBySignedToken(String token) {
        SignedUrlService.VerifiedTokenPayload payload = signedUrlService.verifyAndParseToken(token);
        MemberLetter letter = memberLetterRepository.findById(payload.letterId())
                .orElseThrow(() -> new ResourceNotFoundException("Letter not found: " + payload.letterId()));

        Member owner = memberRepository.findById(payload.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Member not found: " + payload.memberId()));
        enforceWindowIfTerminated(owner);

        return loadAndIncrementDownload(letter, owner);
    }

    private void enforceWindowIfTerminated(Member member) {
        if (member.getStatus() == MemberStatus.TERMINATED) {
            if (member.getLetterWindowExpiresAt() != null && Instant.now().isAfter(member.getLetterWindowExpiresAt())) {
                throw new ForbiddenException("The 10-day post-termination window to view or download letters has expired.");
            }
        }
    }

    private byte[] loadAndIncrementDownload(MemberLetter letter, Member owner) {
        letter.setDownloadCount(letter.getDownloadCount() + 1);
        letter.setLastDownloadedAt(Instant.now());
        memberLetterRepository.save(letter);

        byte[] bytes = storageService.readBytes(letter.getStorageKey());
        if (bytes == null) {
            bytes = pdfRenderService.renderLetterPdf(owner, letter.getLetterType(), letter.getTitle(), letter.getContentHtml(), letter.getIssuedAt());
            storageService.saveBytes(letter.getStorageKey(), bytes);
        }
        return bytes;
    }

    private MemberLetterDTO toDto(MemberLetter l, Member m) {
        return new MemberLetterDTO(
                l.getId(),
                l.getMemberId(),
                m != null ? m.getFullName() : null,
                m != null ? m.getEmail() : null,
                l.getLetterType(),
                l.getTitle(),
                l.getContentHtml(),
                l.getStorageKey(),
                l.getIssuedById(),
                l.getIssuedAt(),
                l.getDownloadCount(),
                l.getLastDownloadedAt()
        );
    }
}
