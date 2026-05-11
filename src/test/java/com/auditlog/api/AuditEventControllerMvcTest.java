package com.auditlog.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auditlog.application.AuditEventIngestionService;
import com.auditlog.application.AuditEventPage;
import com.auditlog.application.AuditEventQueryService;
import com.auditlog.application.ValidationError;
import com.auditlog.application.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuditEventController.class)
@Import(GlobalExceptionHandler.class)
class AuditEventControllerMvcTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private AuditEventQueryService queryService;
  @MockitoBean private AuditEventIngestionService ingestionService;

  @Test
  void query_happyPath_returnsEnvelope() throws Exception {
    when(queryService.queryPage(any())).thenReturn(new AuditEventPage(List.of(), "next-tok", true));

    mockMvc
        .perform(
            get("/audit-events")
                .param("actor", "svc:billing")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-03T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.nextCursor").value("next-tok"))
        .andExpect(jsonPath("$.hasMore").value(true));
  }

  @Test
  void query_missingFilter_returns400MissingFilter() throws Exception {
    when(queryService.queryPage(any()))
        .thenThrow(new ValidationException(ValidationError.missingFilter()));

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-03T00:00:00Z"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("MISSING_FILTER"));
  }

  @Test
  void query_missingFrom_returns400MissingParameter() throws Exception {
    when(queryService.queryPage(any()))
        .thenThrow(new ValidationException(ValidationError.missingParameter("from")));

    mockMvc
        .perform(get("/audit-events").param("actor", "svc:billing"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("MISSING_PARAMETER"))
        .andExpect(jsonPath("$.field").value("from"));
  }

  @Test
  void query_fromAfterTo_returns400InvalidTimeWindow() throws Exception {
    when(queryService.queryPage(any()))
        .thenThrow(new ValidationException(ValidationError.invalidTimeWindow()));

    mockMvc
        .perform(
            get("/audit-events")
                .param("actor", "svc:billing")
                .param("from", "2026-05-03T00:00:00Z")
                .param("to", "2026-05-01T00:00:00Z"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("INVALID_TIME_WINDOW"));
  }

  @Test
  void query_windowTooLarge_returns400WindowTooLarge() throws Exception {
    when(queryService.queryPage(any()))
        .thenThrow(new ValidationException(ValidationError.windowTooLarge()));

    mockMvc
        .perform(
            get("/audit-events")
                .param("actor", "svc:billing")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-10T00:00:00Z"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("WINDOW_TOO_LARGE"));
  }

  @Test
  void query_limitOutOfRange_returns400LimitOutOfRange() throws Exception {
    when(queryService.queryPage(any()))
        .thenThrow(new ValidationException(ValidationError.limitOutOfRange()));

    mockMvc
        .perform(
            get("/audit-events")
                .param("actor", "svc:billing")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-03T00:00:00Z")
                .param("limit", "501"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("LIMIT_OUT_OF_RANGE"))
        .andExpect(jsonPath("$.field").value("limit"));
  }

  @Test
  void query_cursorWithFilter_returns400ConflictingParameters() throws Exception {
    when(queryService.queryPage(any()))
        .thenThrow(new ValidationException(ValidationError.conflictingParameters()));

    mockMvc
        .perform(get("/audit-events").param("actor", "svc:billing").param("cursor", "tok"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("CONFLICTING_PARAMETERS"));
  }

  @Test
  void query_invalidCursor_returns400InvalidCursor() throws Exception {
    when(queryService.queryPage(any()))
        .thenThrow(
            new ValidationException(
                new ValidationError("INVALID_CURSOR", "Cursor is invalid.", "cursor")));

    mockMvc
        .perform(get("/audit-events").param("cursor", "!!not-base64!!"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("INVALID_CURSOR"))
        .andExpect(jsonPath("$.field").value("cursor"));
  }

  @Test
  void query_invalidTimestamp_returns400InvalidTimestamp() throws Exception {
    mockMvc
        .perform(get("/audit-events").param("actor", "svc:billing").param("from", "not-a-date"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("INVALID_TIMESTAMP"))
        .andExpect(jsonPath("$.field").value("from"));
  }
}
