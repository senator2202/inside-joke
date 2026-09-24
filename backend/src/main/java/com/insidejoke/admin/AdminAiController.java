package com.insidejoke.admin;

import com.insidejoke.admin.dto.AiCallDto;
import com.insidejoke.admin.dto.AiStatusDto;
import com.insidejoke.admin.dto.PageDto;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin AI tab. Everything under /api/admin requires the ADMIN role (SecurityConfig). */
@RestController
public class AdminAiController {

    private final AiReportService reports;

    public AdminAiController(AiReportService reports) {
        this.reports = reports;
    }

    /** Is the key set, which model, how the last call went, and the live rate limits from Anthropic's latest response. */
    @GetMapping("/api/admin/ai/status")
    public AiStatusDto status() {
        return reports.status();
    }

    /** Every AI call, newest first; outcome FAILURES means ERROR, TIMEOUT or INVALID_JSON. */
    @GetMapping("/api/admin/ai/calls")
    public PageDto<AiCallDto> calls(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String outcome,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return reports.calls(from, to, purpose, outcome, page, size);
    }
}
