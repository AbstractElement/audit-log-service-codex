package com.auditlog.infrastructure.config;

import java.sql.Connection;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DatabaseStartupVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseStartupVerifier.class);

    private final DataSource dataSource;

    public DatabaseStartupVerifier(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            log.info(
                    "Database connection verified: {} {}",
                    connection.getMetaData().getDatabaseProductName(),
                    connection.getMetaData().getDatabaseProductVersion()
            );
        }
    }
}
