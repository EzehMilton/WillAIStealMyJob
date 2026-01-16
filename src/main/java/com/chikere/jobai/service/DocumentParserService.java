package com.chikere.jobai.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@Service
@Slf4j
public class DocumentParserService {

    /**
     * Extracts text content from uploaded PDF files.
     *
     * @param file the uploaded MultipartFile
     * @return extracted text content
     * @throws IllegalArgumentException if file is null, empty, or not a PDF
     * @throws RuntimeException if text extraction fails
     */
    public String extractText(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty or null");
        }

        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw new IllegalArgumentException("Filename is null");
        }

        String lowerFilename = filename.toLowerCase();
        if (!lowerFilename.endsWith(".pdf")) {
            throw new IllegalArgumentException("Unsupported file format. Please upload a PDF file.");
        }

        log.info("Parsing PDF file: {}, size: {} bytes", filename, file.getSize());

        try {
            String text = extractFromPdf(file);
            log.info("Successfully extracted {} characters from: {}", text.length(), filename);
            return text.trim();

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to extract text from PDF: {}", filename, e);
            throw new RuntimeException("Failed to extract text from PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts text from PDF files using Apache PDFBox.
     */
    private String extractFromPdf(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream();
             PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {

            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);

            if (text == null || text.isBlank()) {
                log.warn("PDF appears to be empty or contains only images: {}", file.getOriginalFilename());
                throw new RuntimeException("Could not extract text from PDF. The file may be image-based or empty.");
            }

            return text;
        }
    }
}
