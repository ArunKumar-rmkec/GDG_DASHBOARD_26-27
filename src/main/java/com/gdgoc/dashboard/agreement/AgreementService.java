package com.gdgoc.dashboard.agreement;

import com.gdgoc.dashboard.agreement.AgreementDtos.*;
import com.gdgoc.dashboard.common.BadRequestException;
import com.gdgoc.dashboard.common.ConflictException;
import com.gdgoc.dashboard.common.ForbiddenException;
import com.gdgoc.dashboard.common.ResourceNotFoundException;
import com.gdgoc.dashboard.common.StorageService;
import com.gdgoc.dashboard.letter.LetterType;
import com.gdgoc.dashboard.letter.MemberLetter;
import com.gdgoc.dashboard.letter.MemberLetterRepository;
import com.gdgoc.dashboard.letter.PdfRenderService;
import com.gdgoc.dashboard.member.Member;
import com.gdgoc.dashboard.member.MemberRepository;
import com.gdgoc.dashboard.member.RoleName;
import com.gdgoc.dashboard.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class AgreementService {

    private final ProjectContributorAgreementRepository agreementRepository;
    private final AgreementSignatureRepository signatureRepository;
    private final MemberRepository memberRepository;
    private final MemberLetterRepository memberLetterRepository;
    private final PdfRenderService pdfRenderService;
    private final StorageService storageService;

    public AgreementService(
            ProjectContributorAgreementRepository agreementRepository,
            AgreementSignatureRepository signatureRepository,
            MemberRepository memberRepository,
            MemberLetterRepository memberLetterRepository,
            PdfRenderService pdfRenderService,
            StorageService storageService
    ) {
        this.agreementRepository = agreementRepository;
        this.signatureRepository = signatureRepository;
        this.memberRepository = memberRepository;
        this.memberLetterRepository = memberLetterRepository;
        this.pdfRenderService = pdfRenderService;
        this.storageService = storageService;
    }

    @Transactional
    public AgreementDTO createAgreement(CreateAgreementRequestDTO req, UserPrincipal actor) {
        String cleanVersion = req.version().trim();
        if (agreementRepository.existsByVersionIgnoreCase(cleanVersion)) {
            throw new ConflictException("Project Contributor Agreement version '" + cleanVersion + "' already exists");
        }

        ProjectContributorAgreement agreement = ProjectContributorAgreement.builder()
                .version(cleanVersion)
                .title(req.title().trim())
                .contentHtml(req.contentHtml())
                .active(true)
                .createdById(actor.getId())
                .build();
        agreement = agreementRepository.save(agreement);
        return toAgreementDto(agreement);
    }

    @Transactional
    public List<AgreementSignatureDTO> issueAgreement(Long agreementId, IssueAgreementRequestDTO req, UserPrincipal actor) {
        ProjectContributorAgreement agreement = agreementRepository.findById(agreementId)
                .orElseThrow(() -> new ResourceNotFoundException("Project Contributor Agreement not found: " + agreementId));

        if (req.memberIds() == null || req.memberIds().isEmpty()) {
            throw new BadRequestException("memberIds must not be empty");
        }

        List<AgreementSignatureDTO> results = new ArrayList<>();
        for (Long memberId : req.memberIds()) {
            Member member = memberRepository.findById(memberId)
                    .orElseThrow(() -> new ResourceNotFoundException("Member not found: " + memberId));

            Optional<AgreementSignature> existing = signatureRepository.findByAgreementIdAndMemberId(agreementId, memberId);
            AgreementSignature sig;
            if (existing.isPresent()) {
                sig = existing.get();
            } else {
                sig = AgreementSignature.builder()
                        .agreementId(agreementId)
                        .memberId(memberId)
                        .issuedById(actor.getId())
                        .status(SignatureStatus.PENDING)
                        .build();
                sig = signatureRepository.save(sig);
            }
            results.add(toSignatureDto(sig, agreement, member));
        }
        return results;
    }

    @Transactional
    public AgreementSignatureDTO signAgreement(Long signatureId, SignAgreementRequestDTO req, UserPrincipal actor) {
        final AgreementSignature sig = signatureRepository.findById(signatureId)
                .orElseThrow(() -> new ResourceNotFoundException("Agreement signature record not found: " + signatureId));

        if (!sig.getMemberId().equals(actor.getId())) {
            throw new ForbiddenException("Only the assigned member can digitally sign their Project Contributor Agreement");
        }
        if (sig.getStatus() == SignatureStatus.SIGNED) {
            throw new ConflictException("Agreement has already been signed");
        }

        ProjectContributorAgreement agreement = agreementRepository.findById(sig.getAgreementId())
                .orElseThrow(() -> new ResourceNotFoundException("Agreement template not found: " + sig.getAgreementId()));
        Member member = memberRepository.findById(sig.getMemberId())
                .orElseThrow(() -> new ResourceNotFoundException("Member not found: " + sig.getMemberId()));

        Instant now = Instant.now();
        sig.setStatus(SignatureStatus.SIGNED);
        sig.setSignatureText(req.signatureText().trim());
        sig.setSignedAt(now);

        byte[] pdfBytes = pdfRenderService.renderContributorAgreementPdf(agreement, member, sig);
        String storageKey = "agreements/pca-sig-" + sig.getId() + ".pdf";
        storageService.saveBytes(storageKey, pdfBytes);
        sig.setPdfStorageKey(storageKey);
        AgreementSignature savedSig = signatureRepository.save(sig);

        MemberLetter letter = MemberLetter.builder()
                .memberId(member.getId())
                .letterType(LetterType.CONTRIBUTOR_AGREEMENT)
                .title(agreement.getTitle() + " (" + agreement.getVersion() + ")")
                .contentHtml(agreement.getContentHtml())
                .storageKey(storageKey)
                .issuedById(sig.getIssuedById())
                .issuedAt(now)
                .build();
        memberLetterRepository.save(letter);

        return toSignatureDto(savedSig, agreement, member);
    }

    @Transactional(readOnly = true)
    public byte[] downloadSignedAgreementPdf(Long signatureId, UserPrincipal actor) {
        AgreementSignature sig = signatureRepository.findById(signatureId)
                .orElseThrow(() -> new ResourceNotFoundException("Agreement signature record not found: " + signatureId));

        boolean isOwner = sig.getMemberId().equals(actor.getId());
        boolean isAdmin = actor.hasRole(RoleName.GDG_LEAD) || actor.hasRole(RoleName.HR_LEAD);
        if (!isOwner && !isAdmin) {
            throw new ForbiddenException("Not authorized to download this signed agreement");
        }

        if (sig.getStatus() != SignatureStatus.SIGNED || sig.getPdfStorageKey() == null) {
            throw new BadRequestException("Agreement has not been signed yet");
        }

        byte[] bytes = storageService.readBytes(sig.getPdfStorageKey());
        if (bytes == null) {
            ProjectContributorAgreement agreement = agreementRepository.findById(sig.getAgreementId())
                    .orElseThrow(() -> new ResourceNotFoundException("Agreement not found"));
            Member member = memberRepository.findById(sig.getMemberId())
                    .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
            bytes = pdfRenderService.renderContributorAgreementPdf(agreement, member, sig);
            storageService.saveBytes(sig.getPdfStorageKey(), bytes);
        }
        return bytes;
    }

    @Transactional(readOnly = true)
    public List<AgreementDTO> listAgreements() {
        return agreementRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toAgreementDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AgreementSignatureDTO> listAllSignatures() {
        return signatureRepository.findAllByOrderByIssuedAtDesc()
                .stream()
                .map(s -> toSignatureDto(
                        s,
                        agreementRepository.findById(s.getAgreementId()).orElse(null),
                        memberRepository.findById(s.getMemberId()).orElse(null)
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AgreementSignatureDTO> listMySignatures(UserPrincipal actor) {
        return signatureRepository.findByMemberIdOrderByIssuedAtDesc(actor.getId())
                .stream()
                .map(s -> toSignatureDto(
                        s,
                        agreementRepository.findById(s.getAgreementId()).orElse(null),
                        memberRepository.findById(s.getMemberId()).orElse(null)
                ))
                .toList();
    }

    private AgreementDTO toAgreementDto(ProjectContributorAgreement a) {
        return new AgreementDTO(
                a.getId(),
                a.getVersion(),
                a.getTitle(),
                a.getContentHtml(),
                a.isActive(),
                a.getCreatedById(),
                a.getCreatedAt()
        );
    }

    private AgreementSignatureDTO toSignatureDto(AgreementSignature s, ProjectContributorAgreement a, Member m) {
        return new AgreementSignatureDTO(
                s.getId(),
                s.getAgreementId(),
                a != null ? a.getVersion() : null,
                a != null ? a.getTitle() : null,
                s.getMemberId(),
                m != null ? m.getFullName() : null,
                m != null ? m.getEmail() : null,
                s.getIssuedById(),
                s.getStatus(),
                s.getSignatureText(),
                s.getSignedAt(),
                s.getPdfStorageKey(),
                s.getIssuedAt()
        );
    }
}
