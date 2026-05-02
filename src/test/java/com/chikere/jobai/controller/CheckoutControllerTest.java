package com.chikere.jobai.controller;

import com.chikere.jobai.service.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class CheckoutControllerTest {

    @Test
    void resolvePriceId_keepsProfessionAndCoursePrices() {
        CheckoutController controller = controllerWithPrices("price_professional", "price_course", "price_a_level");

        assertEquals("price_professional", controller.resolvePriceId("profession"));
        assertEquals("price_professional", controller.resolvePriceId("professional"));
        assertEquals("price_course", controller.resolvePriceId("course"));
        assertEquals("price_course", controller.resolvePriceId("student"));
    }

    @Test
    void resolvePriceId_usesALevelPriceWhenConfigured() {
        CheckoutController controller = controllerWithPrices("price_professional", "price_course", "price_a_level");

        assertEquals("price_a_level", controller.resolvePriceId("a_level"));
        assertEquals("price_a_level", controller.resolvePriceId("a-level"));
        assertEquals("price_a_level", controller.resolvePriceId("alevel"));
        assertEquals("price_a_level", controller.resolvePriceId("A_LEVEL_UNDECIDED"));
    }

    @Test
    void resolvePriceId_fallsBackToCoursePriceWhenALevelPriceMissing() {
        CheckoutController controller = controllerWithPrices("price_professional", "price_course", " ");

        assertEquals("price_course", controller.resolvePriceId("a_level"));
    }

    private CheckoutController controllerWithPrices(String professionPriceId,
                                                    String coursePriceId,
                                                    String aLevelUndecidedPriceId) {
        CheckoutController controller = new CheckoutController(mock(ReportService.class));
        ReflectionTestUtils.setField(controller, "professionPriceId", professionPriceId);
        ReflectionTestUtils.setField(controller, "coursePriceId", coursePriceId);
        ReflectionTestUtils.setField(controller, "aLevelUndecidedPriceId", aLevelUndecidedPriceId);
        return controller;
    }
}
