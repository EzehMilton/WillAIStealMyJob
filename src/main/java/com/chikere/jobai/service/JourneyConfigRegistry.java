package com.chikere.jobai.service;

import com.chikere.jobai.model.JourneyConfig;
import com.chikere.jobai.model.JourneyType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

@Service
public class JourneyConfigRegistry {

    private final Map<JourneyType, JourneyConfig> configs;

    public JourneyConfigRegistry(
            @Value("${app.form.role-summary-word-limit.profession:800}") int professionWordLimit,
            @Value("${app.form.role-summary-word-limit.course:450}") int courseWordLimit,
            @Value("${app.form.role-summary-word-limit.a-level-undecided:350}") int aLevelUndecidedWordLimit) {
        this.configs = buildConfigs(professionWordLimit, courseWordLimit, aLevelUndecidedWordLimit);
    }

    public JourneyConfig get(JourneyType journeyType) {
        return configs.get(journeyType != null ? journeyType : JourneyType.PROFESSIONAL);
    }

    public JourneyConfig get(String legacyMode) {
        return get(JourneyType.fromMode(legacyMode));
    }

    private Map<JourneyType, JourneyConfig> buildConfigs(int professionWordLimit,
                                                         int courseWordLimit,
                                                         int aLevelUndecidedWordLimit) {
        Map<JourneyType, JourneyConfig> values = new EnumMap<>(JourneyType.class);

        values.put(JourneyType.PROFESSIONAL, new JourneyConfig(
                JourneyType.PROFESSIONAL,
                "profession",
                "My current profession",
                "Profession",
                "e.g. Software Developer, Teacher, Accountant",
                "Role Details",
                "Describe what you do day to day, your main responsibilities, the tools or systems you use, and the kind of problems you solve.",
                "Add enough detail for a more accurate and personalised report.",
                professionWordLimit,
                true,
                true,
                "What You'll Discover (Free Preview):",
                "Your Personal AI Risk Score, a quick summary of your risk level, and a snapshot of how your role may be affected.",
                "£4.99",
                "profession-instructionsV2.txt"
        ));

        values.put(JourneyType.UNIVERSITY_STUDENT, new JourneyConfig(
                JourneyType.UNIVERSITY_STUDENT,
                "course",
                "A course or degree I'm studying/considering",
                "Course/Degree",
                "e.g. HND Business, BSc Psychology, MSc Computer Science",
                "Expected Career Path",
                "Describe the career path you expect, the skills you hope to gain, and the kinds of roles or work you want this course to lead to.",
                "Include your career goals and what kind of future work you expect from this course.",
                courseWordLimit,
                false,
                true,
                "What You'll Discover (Free Preview):",
                "AI risk for this career path, future job market outlook, and the skills that make graduates more valuable.",
                "£2.99",
                "course-instructionsV2.txt"
        ));

        values.put(JourneyType.A_LEVEL_UNDECIDED, new JourneyConfig(
                JourneyType.A_LEVEL_UNDECIDED,
                "a_level",
                "I'm choosing A-levels and not sure yet",
                "Interests and Possible Subjects",
                "e.g. Maths, Science, Business, Computer Science, Psychology, Arts",
                "Tell us about your interests, strengths, and future goals",
                "Tell us what you enjoy learning, what you are good at, any career ideas you have, how you like to work, and whether you are open to using technology.",
                "You do not need to know your future career yet. Share what you enjoy and what kind of future you want.",
                aLevelUndecidedWordLimit,
                false,
                true,
                "What You'll Discover (Free Preview):",
                "A first view of AI-resilient directions based on your interests and possible subject choices.",
                "£0.99",
                "a-level-instructionsV3.txt"
        ));

        return Map.copyOf(values);
    }
}
