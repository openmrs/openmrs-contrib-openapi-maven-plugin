package org.openmrs.plugin.openapi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The rule that turns a controller class name into the name its generated API class takes.
 * <p>
 * Worth pinning: the equivalent rule for resources was once anchored at the <em>first</em>
 * occurrence of its suffix, and {@code FormResourceResource1_9} silently overwrote
 * {@code Form.json} for as long as that lasted. These cases are the same trap.
 */
class ControllerNamingTest {

    @Test
    void keepsTheControllerSuffix() {
        // DiagnosisControllerApi, not DiagnosisApi: the suffix is what stops a controller from
        // colliding with a resource of the same name.
        assertEquals("DiagnosisController", ControllerDocumenter.apiTagFor("DiagnosisController"));
        assertEquals("ActiveVisitController", ControllerDocumenter.apiTagFor("ActiveVisitController"));
    }

    @Test
    void stripsATrailingRestVersionMarker() {
        assertEquals("SessionController", ControllerDocumenter.apiTagFor("SessionController1_9"));
        assertEquals("VisitConfigurationController",
                ControllerDocumenter.apiTagFor("VisitConfigurationController2_0"));
        assertEquals("HL7MessageController", ControllerDocumenter.apiTagFor("HL7MessageController1_8"));
    }

    @Test
    void keepsDigitsThatAreNotAVersionMarker() {
        // The digits belong to the name — they do not trail the word "Controller".
        assertEquals("Legacy1xRestController",
                ControllerDocumenter.apiTagFor("Legacy1xRestController"));
    }

    @Test
    void anchorsAtTheEndSoAnEarlierOccurrenceSurvives() {
        assertEquals("ControllerRegistryController",
                ControllerDocumenter.apiTagFor("ControllerRegistryController"));
    }

    @Test
    void neverStripsToNothing() {
        assertEquals("Controller", ControllerDocumenter.apiTagFor("Controller"));
        assertEquals("Controller", ControllerDocumenter.apiTagFor("Controller2_0"));
    }

    @Test
    void leavesANameThatDoesNotEndInControllerAlone() {
        assertEquals("ControllerAdvice", ControllerDocumenter.apiTagFor("ControllerAdvice"));
        assertEquals("SomeResource1_9", ControllerDocumenter.apiTagFor("SomeResource1_9"));
    }
}
