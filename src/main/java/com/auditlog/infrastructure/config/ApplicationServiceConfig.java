package com.auditlog.infrastructure.config;

import com.auditlog.application.AuditEventIngestionService;
import com.auditlog.application.AuditEventQueryService;
import com.auditlog.application.AuditEventRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationServiceConfig {

    @Bean
    AuditEventIngestionService auditEventIngestionService(AuditEventRepository repository, Clock clock) {
        return new AuditEventIngestionService(repository, clock);
    }

    @Bean
    AuditEventQueryService auditEventQueryService(AuditEventRepository repository) {
        return new AuditEventQueryService(repository);
    }
}
