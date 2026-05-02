package com.chikere.jobai.model;

public record JourneyConfig(
        JourneyType journeyType,
        String legacyModeValue,
        String displayName,
        String subjectLabel,
        String subjectPlaceholder,
        String detailsLabel,
        String detailsPlaceholder,
        String detailsHelpText,
        int wordLimit,
        boolean cvUploadAllowed,
        boolean manualInputAllowed,
        String freePreviewTitle,
        String freePreviewCopy,
        String premiumPriceDisplay,
        String promptInstructionResource
) {
}
