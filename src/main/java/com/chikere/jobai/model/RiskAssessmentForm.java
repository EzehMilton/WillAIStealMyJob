package com.chikere.jobai.model;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
public class RiskAssessmentForm {

    @NotBlank(message = "Profession is required")
    @Size(max = 100, message = "Profession must be less than 100 characters")
    private String profession;

    @NotBlank(message = "Role summary is required")
    @Size(max = 1000, message = "Role summary must be less than 1000 characters")
    private String roleSummary;
}