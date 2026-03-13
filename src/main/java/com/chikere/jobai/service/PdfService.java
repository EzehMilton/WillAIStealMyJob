package com.chikere.jobai.service;

import com.chikere.jobai.model.PremiumReport;
import com.lowagie.text.DocumentException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class PdfService {

    private final SpringTemplateEngine templateEngine;

    public byte[] generateReportPdf(PremiumReport report) throws DocumentException, IOException {
        // 1. Render Thymeleaf template to HTML
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("report", report);
        String html = templateEngine.process("premium-report-pdf", context);

        // 2. Convert to XHTML via jsoup so Flying Saucer's XML parser can handle it
        Document doc = Jsoup.parse(html);
        doc.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .charset("UTF-8");
        String xhtml = doc.outerHtml();

        // 3. Render to PDF
        ITextRenderer renderer = new ITextRenderer();
        renderer.setDocumentFromString(xhtml);
        renderer.layout();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        renderer.createPDF(out);
        return out.toByteArray();
    }
}