package com.chikere.jobai.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

@Service
@Slf4j
public class DocumentParserService {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".pdf", ".doc", ".docx");

    private final Tika tika = new Tika();

    /**
     * Extracts text content from uploaded PDF and Word files.
     *
     * @param file the uploaded MultipartFile
     * @return extracted text content
     * @throws IllegalArgumentException if file is null, empty, or not a supported document
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
        if (!isSupportedFilename(lowerFilename)) {
            throw new IllegalArgumentException("Unsupported file format. Please upload a PDF, DOC, or DOCX file.");
        }

        log.info("Parsing document file: {}, size: {} bytes", filename, file.getSize());

        try {
            String text = extractFromDocument(file);
            log.info("Successfully extracted {} characters from: {}", text.length(), filename);
            return text.trim();

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to extract text from document: {}", filename, e);
            throw new RuntimeException("Failed to extract text from document: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts text from supported document files using Apache Tika.
     */
    private String extractFromDocument(MultipartFile file) throws IOException, TikaException {
        try (InputStream inputStream = file.getInputStream()) {
            String text = tika.parseToString(inputStream);

            if (text == null || text.isBlank()) {
                log.warn("Document appears to be empty or contains no extractable text: {}", file.getOriginalFilename());
                throw new RuntimeException("Could not extract text from document. The file may be empty, image-based, or unsupported.");
            }

            return text;
        }
    }

    private boolean isSupportedFilename(String lowerFilename) {
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lowerFilename::endsWith);
    }
}
