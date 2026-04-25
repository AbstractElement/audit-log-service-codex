package com.auditlog.api;

import com.auditlog.application.AuditEventIngestionService;
import com.auditlog.application.AuditEventQueryService;
import com.auditlog.application.AuditEventSearchCriteria;
import com.auditlog.application.RecordAuditEventCommand;
import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.AuditOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    private final AuditEventIngestionService ingestionService;
    private final AuditEventQueryService queryService;

    public AuditEventController(AuditEventIngestionService ingestionService, AuditEventQueryService queryService) {
        this.ingestionService = ingestionService;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<Void> create(@Valid @RequestBody CreateAuditEventRequest request) {
        AuditEvent event = ingestionService.record(request.toCommand());
        return ResponseEntity.created(URI.create("/audit-events/" + event.id())).build();
    }

    @GetMapping
    public List<AuditEventResponse> find(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        return queryService.find(new AuditEventSearchCriteria(actor, resource, from, to, limit, offset))
                .stream()
                .map(AuditEventResponse::fromDomain)
                .toList();
    }

    public record CreateAuditEventRequest(
            @NotBlank String actor,
            @NotBlank String action,
            @NotBlank String resource,
            @NotNull AuditOutcome outcome,
            Map<String, Object> context
    ) {

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
            AuditOutcome outcome,
            Map<String, Object> context
    ) {

        static AuditEventResponse fromDomain(AuditEvent event) {
            return new AuditEventResponse(
                    event.id(),
                    event.timestamp(),
                    event.actor(),
                    event.action(),
                    event.resource(),
                    event.outcome(),
                    event.context()
            );
        }
    }
}
