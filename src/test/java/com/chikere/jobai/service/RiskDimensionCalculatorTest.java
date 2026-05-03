package com.chikere.jobai.service;

import com.chikere.jobai.model.JourneyType;
import com.chikere.jobai.model.RiskDimensions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskDimensionCalculatorTest {

    private final RiskDimensionCalculator calculator = new RiskDimensionCalculator();

    @Test
    void taskRepeatabilityIsHigherForDataEntryThanChoirSinger() {
        RiskDimensions dataEntry = calculator.calculate(
                JourneyType.PROFESSIONAL,
                "Data entry clerk",
                "Routine forms, repetitive processing, invoices, and standardised records"
        );
        RiskDimensions singer = calculator.calculate(
                JourneyType.PROFESSIONAL,
                "Choir singer",
                "Live performance, rehearsals, audience response, and real-time coordination"
        );

        assertTrue(dataEntry.taskRepeatability() >= 8.5);
        assertTrue(singer.taskRepeatability() <= 3.0);
    }

    @Test
    void digitalExecutionIsHigherForSoftwareDeveloperThanElectrician() {
        RiskDimensions developer = calculator.calculate(
                JourneyType.PROFESSIONAL,
                "Software developer",
                "Builds Java services, writes code, tests, debugs, and documents releases"
        );
        RiskDimensions electrician = calculator.calculate(
                JourneyType.PROFESSIONAL,
                "Electrician",
                "Hands-on site work, physical installation, fault finding, and unpredictable environments"
        );

        assertTrue(developer.digitalExecution() >= 8.0);
        assertTrue(electrician.digitalExecution() <= 2.5);
    }

    @Test
    void humanInteractionRiskIsLowerForNurseThanBackOfficeProcessing() {
        RiskDimensions nurse = calculator.calculate(
                JourneyType.PROFESSIONAL,
                "Nurse",
                "Patient care, safeguarding, empathy, real-time judgement, and ward coordination"
        );
        RiskDimensions processing = calculator.calculate(
                JourneyType.PROFESSIONAL,
                "Back office processing clerk",
                "Processes forms, reports, invoices, and records with limited human interaction"
        );

        assertTrue(nurse.humanInteraction() <= 3.0);
        assertTrue(processing.humanInteraction() >= 7.0);
    }

    @Test
    void creativityExecutionRiskIsLowerForCeoThanRoutineImplementation() {
        RiskDimensions ceo = calculator.calculate(
                JourneyType.PROFESSIONAL,
                "CEO",
                "Leadership, strategy, negotiation, judgement, and stakeholder accountability"
        );
        RiskDimensions implementation = calculator.calculate(
                JourneyType.PROFESSIONAL,
                "Implementation analyst",
                "Routine implementation, documentation, testing, reporting, and standardised delivery"
        );

        assertTrue(ceo.creativityExecution() <= 3.0);
        assertTrue(implementation.creativityExecution() >= 6.0);
    }

    @Test
    void environmentComplexityRiskIsHigherForStructuredCallCenterThanLivePerformance() {
        RiskDimensions callCenter = calculator.calculate(
                JourneyType.PROFESSIONAL,
                "Call center agent",
                "Scripted customer support in structured workflows with standard responses"
        );
        RiskDimensions singer = calculator.calculate(
                JourneyType.PROFESSIONAL,
                "Choir singer",
                "Live performance, audience interaction, real-time coordination, and rehearsal"
        );

        assertTrue(callCenter.environmentComplexity() >= 8.0);
        assertTrue(singer.environmentComplexity() <= 3.0);
    }
}
