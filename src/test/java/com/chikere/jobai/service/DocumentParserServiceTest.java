package com.chikere.jobai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentParserServiceTest {

    private DocumentParserService service;

    @BeforeEach
    void setUp() {
        service = new DocumentParserService();
        ReflectionTestUtils.setField(service, "parseTimeoutSeconds", 10L);
    }

    @Test
    void extractsTextFromUploadedDocument() {
        MockMultipartFile file = new MockMultipartFile(
                "cvFile", "cv.docx", "application/octet-stream",
                "Senior Java developer with ten years of experience building trading systems."
                        .getBytes(StandardCharsets.UTF_8));

        String text = service.extractText(file);

        assertTrue(text.contains("Senior Java developer"));
    }

    @Test
    void extractedTextIsCappedSoDecompressionBombsCannotExhaustMemory() {
        String huge = "a".repeat(300_000);
        MockMultipartFile file = new MockMultipartFile(
                "cvFile", "cv.docx", "application/octet-stream", huge.getBytes(StandardCharsets.UTF_8));

        String text = service.extractText(file);

        assertTrue(text.length() <= 100_000, "expected cap at 100k chars but got " + text.length());
        assertTrue(text.length() >= 99_000, "cap should not truncate far below the limit");
    }

    @Test
    @Timeout(10)
    void hangingParseIsTimedOutInsteadOfPinningTheRequestThread() throws IOException {
        ReflectionTestUtils.setField(service, "parseTimeoutSeconds", 1L);
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("cv.pdf");
        when(file.getSize()).thenReturn(100L);
        when(file.getInputStream()).thenReturn(new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
                return -1;
            }
        });

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.extractText(file));

        assertTrue(ex.getMessage().contains("Could not read the document in time"),
                "unexpected message: " + ex.getMessage());
    }

    @Test
    void rejectsUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "cvFile", "cv.exe", "application/octet-stream", "content".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.extractText(file));
        assertTrue(ex.getMessage().contains("Unsupported file format"));
    }

    @Test
    void rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("cvFile", "cv.pdf", "application/pdf", new byte[0]);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.extractText(file));
        assertEquals("File is empty or null", ex.getMessage());
    }
}
