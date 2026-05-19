package com.auditlog.infrastructure.persistence;

import com.auditlog.application.AuditActorSet;
import com.auditlog.application.AuditEventCursor;
import com.auditlog.application.AuditEventPage;
import com.auditlog.application.AuditEventQuery;
import com.auditlog.application.AuditEventRepository;
import com.auditlog.application.AuditEventView;
import com.auditlog.domain.AuditEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaAuditEventRepository implements AuditEventRepository {

  private static final String FIND_PAGE_SQL_WITH_ACTORS =
      """
      SELECT id, event_timestamp, actor, action, resource, outcome, context
        FROM audit_events
       WHERE event_timestamp >= :from
         AND event_timestamp <  :to
         AND actor = ANY(CAST(:actors AS TEXT[]))
         AND (CAST(:resource AS TEXT) IS NULL OR resource = CAST(:resource AS TEXT))
         AND (
               CAST(:cursor_ts AS TIMESTAMPTZ) IS NULL
               OR event_timestamp <  CAST(:cursor_ts AS TIMESTAMPTZ)
               OR (event_timestamp = CAST(:cursor_ts AS TIMESTAMPTZ)
                   AND id < CAST(:cursor_id AS UUID))
             )
       ORDER BY event_timestamp DESC, id DESC
       LIMIT :limit_plus_one
      """;

  private static final String FIND_PAGE_SQL_WITHOUT_ACTORS =
      """
      SELECT id, event_timestamp, actor, action, resource, outcome, context
        FROM audit_events
       WHERE event_timestamp >= :from
         AND event_timestamp <  :to
         AND (CAST(:resource AS TEXT) IS NULL OR resource = CAST(:resource AS TEXT))
         AND (
               CAST(:cursor_ts AS TIMESTAMPTZ) IS NULL
               OR event_timestamp <  CAST(:cursor_ts AS TIMESTAMPTZ)
               OR (event_timestamp = CAST(:cursor_ts AS TIMESTAMPTZ)
                   AND id < CAST(:cursor_id AS UUID))
             )
       ORDER BY event_timestamp DESC, id DESC
       LIMIT :limit_plus_one
      """;

  private final EntityManager entityManager;

  JpaAuditEventRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  @Transactional
  public AuditEvent save(AuditEvent event) {
    var entity = JpaAuditEventEntity.fromDomain(event);
    entityManager.persist(entity);
    return entity.toDomain();
  }

  @Override
  @Transactional(readOnly = true)
  public AuditEventPage findPage(AuditEventQuery query, Instant cursorTs, UUID cursorId) {
    int limit = query.limit();
    OffsetDateTime from = query.from().atOffset(ZoneOffset.UTC);
    OffsetDateTime to = query.to().atOffset(ZoneOffset.UTC);
    AuditActorSet actors = query.actors();
    String resource = query.resource();
    OffsetDateTime cursorAt = cursorTs == null ? null : cursorTs.atOffset(ZoneOffset.UTC);

    Query nativeQuery =
        entityManager
            .createNativeQuery(
                actors == null ? FIND_PAGE_SQL_WITHOUT_ACTORS : FIND_PAGE_SQL_WITH_ACTORS,
                JpaAuditEventEntity.class)
            .setParameter("from", from)
            .setParameter("to", to)
            .setParameter("resource", resource)
            .setParameter("cursor_ts", cursorAt)
            .setParameter("cursor_id", cursorId)
            .setParameter("limit_plus_one", limit + 1);
    if (actors != null) {
      nativeQuery.setParameter("actors", actors.toArray());
    }

    @SuppressWarnings("unchecked")
    List<JpaAuditEventEntity> rows = nativeQuery.getResultList();

    boolean hasMore = rows.size() > limit;
    List<JpaAuditEventEntity> kept = hasMore ? rows.subList(0, limit) : rows;

    List<AuditEventView> items =
        kept.stream().map(JpaAuditEventEntity::toDomain).map(AuditEventView::fromDomain).toList();

    String nextCursor = null;
    if (hasMore) {
      JpaAuditEventEntity last = kept.get(kept.size() - 1);
      nextCursor =
          AuditEventCursor.encode(
              new AuditEventCursor(
                  last.timestamp(),
                  last.id(),
                  actors == null ? null : actors.values(),
                  resource,
                  query.from(),
                  query.to(),
                  AuditEventCursor.V));
    }
    return new AuditEventPage(items, nextCursor, hasMore);
  }
}
