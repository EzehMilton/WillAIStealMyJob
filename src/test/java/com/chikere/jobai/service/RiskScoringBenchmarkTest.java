package com.chikere.jobai.service;

import com.chikere.jobai.model.JourneyType;
import com.chikere.jobai.model.RiskScoringResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskScoringBenchmarkTest {

    private final RiskScoringService scoringService = new RiskScoringService(
            new RiskDimensionCalculator(),
            new RiskAdjustmentService(),
            new RiskSanityValidator()
    );

    @Test
    void thresholdsUseExpectedBuckets() {
        RiskSanityValidator validator = new RiskSanityValidator();

        assertEquals("Low", validator.riskLevel(0.0));
        assertEquals("Low", validator.riskLevel(3.4));
        assertEquals("Moderate", validator.riskLevel(3.5));
        assertEquals("Moderate", validator.riskLevel(6.9));
        assertEquals("High", validator.riskLevel(7.0));
        assertEquals("High", validator.riskLevel(10.0));
    }

    @Test
    void highRiskBenchmarkRolesAreHigh() {
        List<RoleCase> cases = List.of(
                role("Data Entry Clerk", "Routine data entry, repetitive forms, invoice processing, and standardised records."),
                role("Call Centre Agent", "Scripted customer support, standard workflows, ticket handling, and repeatable responses."),
                role("Transcriptionist", "Transcribes audio into text using repeatable digital workflows and standard formatting."),
                role("Claims Processor", "Processes insurance claims, forms, documentation, structured checks, and standard decisions."),
                role("Invoice Processing Clerk", "Processes invoices, reconciles records, checks forms, and follows rule-based finance workflows."),
                role("Generic SEO Content Writer", "Produces high-volume generic SEO articles from briefs, keywords, templates, and repeatable research."),
                role("Telemarketer", "Makes scripted outbound sales calls, follows standard objection handling, and logs outcomes."),
                role("Appointment Scheduler", "Schedules appointments, sends reminders, updates calendars, and handles routine admin messages.")
        );

        cases.forEach(roleCase -> assertRiskLevel("High", roleCase));
    }

    @Test
    void moderateRiskBenchmarkRolesAreModerate() {
        List<RoleCase> cases = List.of(
                role("Java Developer", "Builds Java services, writes CRUD features, tests, documentation, debugging, and production fixes."),
                role("Frontend Developer", "Builds user interfaces, implements components, fixes bugs, writes tests, and works with product requirements."),
                role("Graphic Designer", "Creates brand concepts, visual assets, digital layouts, and client revisions."),
                role("Accountant", "Prepares accounts, reconciliations, reports, compliance documents, and client financial analysis."),
                role("Marketing Executive", "Plans campaigns, writes content, reviews performance data, coordinates channels, and works with stakeholders."),
                role("Business Analyst", "Documents requirements, analyses processes, creates reports, workshops stakeholders, and supports delivery."),
                role("Journalist", "Researches stories, interviews sources, writes articles, checks facts, and exercises editorial judgement."),
                role("Secondary School Teacher", "Plans lessons, teaches students, manages behaviour, marks work, and supports pastoral needs."),
                role("Recruiter", "Screens CVs, coordinates candidates, interviews people, manages client relationships, and negotiates offers."),
                role("HR Generalist", "Handles HR admin, employee relations, policy guidance, documentation, and sensitive people issues.")
        );

        cases.forEach(roleCase -> assertRiskLevel("Moderate", roleCase));
    }

    @Test
    void lowRiskBenchmarkRolesAreLow() {
        List<RoleCase> cases = List.of(
                role("Nurse", "Bedside clinical care, patient judgement, safeguarding, empathy, ward coordination, and hands-on treatment."),
                role("Electrician", "Hands-on physical site work, fault finding, safety judgement, and unpredictable environments."),
                role("Plumber", "Physical installation, repairs, site diagnostics, safety judgement, and unpredictable property conditions."),
                role("Firefighter", "Emergency response, physical presence, hazardous environments, teamwork, and real-time judgement."),
                role("Paramedic", "Emergency clinical care, physical presence, patient assessment, emotional intelligence, and unpredictable environments."),
                role("Social Worker", "Safeguarding, complex family situations, empathy, judgement, home visits, and real-world coordination."),
                role("Construction Worker", "Physical site work, manual tasks, safety coordination, tools, materials, and changing real-world conditions."),
                role("Car Mechanic", "Hands-on diagnostics, repairs, tools, physical inspection, and unpredictable vehicle faults."),
                role("Hairdresser", "Physical presence, client trust, taste, manual skill, and real-time service delivery."),
                role("Chef", "Physical kitchen work, timing, taste, live service pressure, team coordination, and sensory judgement."),
                role("Choir Singer", "Live performance, rehearsals, audience connection, real-time coordination, and creative interpretation.")
        );

        cases.forEach(roleCase -> assertRiskLevel("Low", roleCase));
    }

    @Test
    void scoringIsSensitiveToRoleDetails() {
        RiskScoringResult routineDeveloper = score(
                "Java Developer",
                "Mostly CRUD tickets, routine implementation, testing, documentation, debugging, and standard delivery."
        );
        RiskScoringResult seniorDeveloper = score(
                "Java Developer",
                "Architecture ownership, production accountability, stakeholder leadership, design trade-offs, mentoring, and platform strategy."
        );
        RiskScoringResult adminNurse = score(
                "Nurse",
                "Mostly documentation, scheduling, reporting, care-plan administration, forms, and routine record updates."
        );
        RiskScoringResult bedsideNurse = score(
                "Nurse",
                "Bedside clinical care, patient assessment, safeguarding, empathy, physical presence, and real-time ward judgement."
        );
        RiskScoringResult choirSinger = score(
                "Singer",
                "Performs live in a choir with audience connection, real-time coordination, rehearsals, and creative interpretation."
        );

        assertTrue(
                routineDeveloper.score() > seniorDeveloper.score(),
                failure("Java Developer detail sensitivity", routineDeveloper)
                        + " should be higher than " + failure("senior Java Developer", seniorDeveloper)
        );
        assertTrue(
                adminNurse.score() > bedsideNurse.score(),
                failure("Nurse detail sensitivity", adminNurse)
                        + " should be higher than " + failure("bedside Nurse", bedsideNurse)
        );
        assertEquals("Low", choirSinger.riskLevel(), failure("Choir singer", choirSinger));
    }

    private RiskScoringResult score(String role, String details) {
        return scoringService.score(JourneyType.PROFESSIONAL, role, details, "Fake model summary");
    }

    private void assertRiskLevel(String expected, RoleCase roleCase) {
        RiskScoringResult result = score(roleCase.role(), roleCase.details());
        assertEquals(expected, result.riskLevel(), failure(roleCase.role(), roleCase.details(), result));
    }

    private String failure(String role, RiskScoringResult result) {
        return failure(role, "", result);
    }

    private String failure(String role, String details, RiskScoringResult result) {
        return "role=" + role
                + " details=\"" + details + "\""
                + " actualScore=" + result.score()
                + " riskLevel=" + result.riskLevel()
                + " baseScore=" + result.baseScore()
                + " protectiveAdjustment=" + result.protectiveAdjustment()
                + " dimensions=" + result.dimensions()
                + " summary=\"" + result.summary() + "\"";
    }

    private RoleCase role(String role, String details) {
        return new RoleCase(role, details);
    }

    private record RoleCase(String role, String details) {
    }
}
