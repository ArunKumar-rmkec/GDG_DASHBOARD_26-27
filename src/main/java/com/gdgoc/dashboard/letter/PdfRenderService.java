package com.gdgoc.dashboard.letter;

import com.gdgoc.dashboard.agreement.AgreementSignature;
import com.gdgoc.dashboard.agreement.ProjectContributorAgreement;
import com.gdgoc.dashboard.member.Member;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
public class PdfRenderService {

    private final TemplateEngine templateEngine;

    public PdfRenderService(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    public byte[] renderLetterPdf(Member member, LetterType letterType, String title, String contentHtml, Instant issuedAt) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", title != null ? title : "GDGoC Official Letter");
        vars.put("memberName", member.getFullName());
        vars.put("memberEmail", member.getEmail());
        vars.put("letterType", letterType != null ? letterType.name() : "OFFICIAL");
        vars.put("issuedAt", (issuedAt != null ? issuedAt : Instant.now()).toString());
        vars.put("contentText", stripHtmlTags(contentHtml));
        return renderTemplateToPdf("letter", vars);
    }

    public byte[] renderContributorAgreementPdf(ProjectContributorAgreement agreement, Member member, AgreementSignature signature) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", agreement.getTitle());
        vars.put("version", agreement.getVersion());
        vars.put("contentText", stripHtmlTags(agreement.getContentHtml()));
        vars.put("memberName", member.getFullName());
        vars.put("memberEmail", member.getEmail());
        vars.put("signatureText", signature.getSignatureText() != null ? signature.getSignatureText() : "Signed");
        vars.put("signedAt", (signature.getSignedAt() != null ? signature.getSignedAt() : Instant.now()).toString());
        return renderTemplateToPdf("contributor-agreement", vars);
    }

    public byte[] renderCertificatePdf(String templateTitle, String recipientName, String eventName, String eventDate, String certificateCode) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("templateTitle", templateTitle != null ? templateTitle : "Certificate of Participation");
        vars.put("recipientName", recipientName);
        vars.put("eventName", eventName);
        vars.put("eventDate", eventDate);
        vars.put("certificateCode", certificateCode);
        return renderTemplateToPdf("certificate", vars);
    }

    public byte[] renderTemplateToPdf(String templateName, Map<String, Object> variables) {
        Context context = new Context();
        context.setVariables(variables);
        String xhtml = templateEngine.process(templateName, context);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(xhtml);
            renderer.layout();
            renderer.createPDF(baos);
            return baos.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to render PDF for template " + templateName, ex);
        }
    }

    private String stripHtmlTags(String html) {
        if (html == null) {
            return "";
        }
        return html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n\n")
                .replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim();
    }
}
