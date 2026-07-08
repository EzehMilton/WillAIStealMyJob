package com.chikere.jobai.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class DocumentParserService {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".pdf", ".doc", ".docx");

    /** We only need ~800 words of CV text; this also caps zip-bomb decompression output. */
    private static final int MAX_TEXT_CHARS = 100_000;

    @Value("${app.cv.parse-timeout-seconds:10}")
    private long parseTimeoutSeconds;

    private final Tika tika = createTika();

    /**
     * Parsing runs off the request thread so a crafted file that hangs Tika can be timed out.
     * The small bounded pool doubles as a cap on concurrent (and runaway) parses.
     */
    private final ExecutorService parseExecutor = createParseExecutor();

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
     * Extracts text from supported document files using Apache Tika, with a hard output cap
     * and a wall-clock timeout. On timeout the try-with-resources closes the upload stream,
     * which also unblocks a parser thread stuck reading it.
     */
    private String extractFromDocument(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            Future<String> parse;
            try {
                parse = parseExecutor.submit(() -> tika.parseToString(inputStream));
            } catch (RejectedExecutionException e) {
                log.warn("Tika parse rejected — parser pool saturated (file: {})", file.getOriginalFilename());
                throw new RuntimeException("Too many documents are being processed right now. Please try again shortly.");
            }

            String text;
            try {
                text = parse.get(parseTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                parse.cancel(true);
                log.warn("Tika parse timed out after {}s: {}", parseTimeoutSeconds, file.getOriginalFilename());
                throw new RuntimeException("Could not read the document in time. Please try a different file or enter details manually.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                parse.cancel(true);
                throw new RuntimeException("Document processing was interrupted.");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new RuntimeException(cause.getMessage(), cause);
            }

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

    private static Tika createTika() {
        Tika tika = new Tika();
        tika.setMaxStringLength(MAX_TEXT_CHARS);
        return tika;
    }

    private static ExecutorService createParseExecutor() {
        AtomicInteger threadNumber = new AtomicInteger(1);
        return new ThreadPoolExecutor(
                2, 2, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(4),
                runnable -> {
                    Thread thread = new Thread(runnable, "tika-parse-" + threadNumber.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @PreDestroy
    void shutdown() {
        parseExecutor.shutdownNow();
    }
}
