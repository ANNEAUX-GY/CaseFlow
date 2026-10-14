package com.caseflow.service;

import com.caseflow.entity.CaseDeadlineState;
import com.caseflow.entity.CaseInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class CaseDeadlineServiceTest {
    private final LocalDateTime base = LocalDateTime.of(2026, 1, 1, 9, 0);

    private CaseInfo measure(String type) {
        CaseInfo c = new CaseInfo();
        c.setCaseMeasure(type);
        c.setMeasureDate(base);
        return c;
    }

    private CaseDeadlineService.Item item(CaseDeadlineService.View v, String key) {
        return v.getItems().stream().filter(i -> key.equals(i.getKey())).findFirst().orElseThrow(AssertionError::new);
    }

    @Test
    void detentionThreeSevenAndThirtyDaySchedules() {
        CaseInfo c = measure("DETENTION");
        CaseDeadlineState s = new CaseDeadlineState();
        CaseDeadlineService.View first = CaseDeadlineService.calculate(c, s, base.plusDays(2));
        assertEquals(base.plusDays(3).toLocalDate().atTime(23, 59, 59), item(first, "CHANGE_TERM").getDueAt());
        s.setDetentionTerm("7");
        CaseDeadlineService.View seven = CaseDeadlineService.calculate(c, s, base.plusDays(4));
        assertTrue(item(seven, "HANDOFF").isReminding());
        assertEquals(base.plusDays(5).toLocalDate().atTime(23, 59, 59), item(seven, "PRETRIAL").getDueAt());
        assertEquals(base.plusDays(6).toLocalDate().atTime(23, 59, 59), item(seven, "LEGAL").getDueAt());
        s.setLegalReceivedAt(base.plusDays(4));
        assertTrue(CaseDeadlineService.calculate(c, s, base.plusDays(4)).isCanSubmit());
        assertFalse(item(CaseDeadlineService.calculate(c, s, base.plusDays(4)), "HANDOFF").isReminding());
        s.setDetentionTerm("30");
        CaseDeadlineService.View thirty = CaseDeadlineService.calculate(c, s, base.plusDays(19));
        assertEquals(base.plusDays(12).toLocalDate().atStartOfDay(), item(thirty, "HANDOFF").getRemindAt());
        assertEquals(base.plusDays(15).toLocalDate().atTime(23, 59, 59), item(thirty, "PRETRIAL").getDueAt());
        assertTrue(item(thirty, "PROGRESS").isReminding());
        assertEquals(base.plusDays(20).toLocalDate().atTime(23, 59, 59), item(thirty, "LEGAL").getDueAt());
    }

    @Test
    void pauseAndResumeShiftMilestones() {
        CaseInfo c = measure("DETENTION");
        CaseDeadlineState s = new CaseDeadlineState();
        s.setDetentionTerm("7");
        s.setPausedAt(base.plusDays(2));
        CaseDeadlineService.View paused = CaseDeadlineService.calculate(c, s, base.plusDays(5));
        assertTrue(paused.isPaused());
        assertFalse(item(paused, "PRETRIAL").isReminding());
        s.setPausedAt(null);
        s.setPausedSeconds(3L * 24 * 3600);
        assertEquals(base.plusDays(8).toLocalDate().atTime(23, 59, 59),
                item(CaseDeadlineService.calculate(c, s, base.plusDays(6)), "PRETRIAL").getDueAt());
    }

    @Test
    void bailAndResidenceSummonsAndMaximumTerms() {
        CaseDeadlineState s = new CaseDeadlineState();
        CaseDeadlineService.View bail = CaseDeadlineService.calculate(measure("BAIL"), s, base);
        assertEquals(base.plusMonths(3).toLocalDate().atTime(23, 59, 59), item(bail, "PRETRIAL").getDueAt());
        assertEquals(base.plusMonths(6).toLocalDate().atTime(23, 59, 59), item(bail, "LEGAL").getDueAt());
        assertEquals(base.plusYears(1).toLocalDate().atTime(23, 59, 59), item(bail, "BAIL_END").getDueAt());
        s.setLastSummonsAt(base.plusMonths(1));
        assertEquals(base.plusMonths(4).toLocalDate().atTime(23, 59, 59),
                item(CaseDeadlineService.calculate(measure("BAIL"), s, base.plusMonths(1)), "SUMMONS").getDueAt());
        CaseDeadlineService.View residence = CaseDeadlineService.calculate(measure("RESIDENCE"), s, base.plusMonths(2));
        assertEquals(base.plusMonths(2).toLocalDate().atTime(23, 59, 59), item(residence, "SUMMONS_MONTH").getDueAt());
        assertEquals(base.plusMonths(6).toLocalDate().atTime(23, 59, 59), item(residence, "RESIDENCE_END").getDueAt());
    }

    @Test
    void administrativeOversightAndExtensionDocuments() {
        CaseInfo c = new CaseInfo();
        c.setCaseType("ADMINISTRATIVE");
        c.setAcceptTime(base);
        CaseDeadlineService.View v = CaseDeadlineService.calculate(c, new CaseDeadlineState(), base);
        assertEquals(base.plusDays(3).toLocalDate().atTime(23, 59, 59), item(v, "ADMIN_CHECK_3").getDueAt());
        assertEquals(base.plusDays(10).toLocalDate().atTime(23, 59, 59), item(v, "ADMIN_CHECK_10").getDueAt());
        assertEquals(base.plusDays(20).toLocalDate().atTime(23, 59, 59), item(v, "ADMIN_CHECK_20").getDueAt());
        assertEquals(base.plusDays(29).toLocalDate().atTime(23, 59, 59), item(v, "ADMIN_30").getDueAt());
        assertEquals(base.plusDays(59).toLocalDate().atTime(23, 59, 59), item(v, "ADMIN_60").getDueAt());
    }
}
