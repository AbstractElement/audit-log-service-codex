package com.auditlog.api;

import com.auditlog.application.AuditEventIngestionService;
import com.auditlog.application.AuditEventPage;
import com.auditlog.application.AuditEventQuery;
import com.auditlog.application.AuditEventQueryService;
import com.auditlog.application.AuditEventView;
import com.auditlog.application.RecordAuditEventCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/audit-events")
public class AuditEventController {

  private static final int DEFAULT_LIMIT = 100;

  private final AuditEventIngestionService ingestionService;
  private final AuditEventQueryService queryService;

  public AuditEventController(
      AuditEventIngestionService ingestionService, AuditEventQueryService queryService) {
    this.ingestionService = ingestionService;
    this.queryService = queryService;
  }

  @PostMapping
  public ResponseEntity<Void> create(@Valid @RequestBody CreateAuditEventRequest request) {
    AuditEventView event = ingestionService.record(request.toCommand());
    return ResponseEntity.created(URI.create("/audit-events/" + event.id())).build();
  }

  @GetMapping
  public AuditEventPageResponse query(
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) String resource,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor) {
    int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
    AuditEventQuery query = new AuditEventQuery(actor, resource, from, to, effectiveLimit, cursor);
    AuditEventPage page = queryService.queryPage(query);
    List<AuditEventResponse> items =
        page.items().stream().map(AuditEventResponse::fromView).toList();
    return new AuditEventPageResponse(items, page.nextCursor(), page.hasMore());
  }

  public record CreateAuditEventRequest(
      @NotBlank String actor,
      @NotBlank String action,
      @NotBlank String resource,
      @NotNull @Pattern(regexp = "SUCCESS|DENIED|ERROR") String outcome,
      Map<String, Object> context) {

    RecordAuditEventCommand toCommand() {
      return new RecordAuditEventCommand(actor, action, resource, outcome, context);
    }
  }

  public record AuditEventResponse(
      UUID id,
      Instant timestamp,
      String actor,
      String action,
      String resource,
      String outcome,
      Map<String, Object> context) {

    static AuditEventResponse fromView(AuditEventView event) {
      return new AuditEventResponse(
          event.id(),
          event.timestamp(),
          event.actor(),
          event.action(),
          event.resource(),
          event.outcome(),
          event.context());
    }
  }

  public record AuditEventPageResponse(
      List<AuditEventResponse> items, String nextCursor, boolean hasMore) {}
}
