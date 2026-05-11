package com.auditlog.infrastructure.persistence;

import com.auditlog.application.AuditEventCursor;
import com.auditlog.application.AuditEventPage;
import com.auditlog.application.AuditEventQuery;
import com.auditlog.application.AuditEventRepository;
import com.auditlog.application.AuditEventSearchCriteria;
import com.auditlog.application.AuditEventView;
import com.auditlog.domain.AuditEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaAuditEventRepository implements AuditEventRepository {

  private static final String FIND_PAGE_SQL =
      """
      SELECT id, event_timestamp, actor, action, resource, outcome, context
        FROM audit_events
       WHERE event_timestamp >= :from
         AND event_timestamp <  :to
         AND (CAST(:actor AS TEXT)    IS NULL OR actor    = CAST(:actor AS TEXT))
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
  public List<AuditEvent> find(AuditEventSearchCriteria criteria) {
    var builder = entityManager.getCriteriaBuilder();
    var query = builder.createQuery(JpaAuditEventEntity.class);
    var root = query.from(JpaAuditEventEntity.class);
    var predicates = new ArrayList<Predicate>();

    if (hasText(criteria.actor())) {
      predicates.add(builder.equal(root.get("actor"), criteria.actor().trim()));
    }
    if (hasText(criteria.resource())) {
      predicates.add(builder.equal(root.get("resource"), criteria.resource().trim()));
    }
    if (criteria.from() != null) {
      predicates.add(builder.greaterThanOrEqualTo(root.get("timestamp"), criteria.from()));
    }
    if (criteria.to() != null) {
      predicates.add(builder.lessThanOrEqualTo(root.get("timestamp"), criteria.to()));
    }

    query
        .select(root)
        .where(predicates.toArray(Predicate[]::new))
        .orderBy(builder.desc(root.get("timestamp")));

    return entityManager
        .createQuery(query)
        .setFirstResult(criteria.offset())
        .setMaxResults(criteria.limit())
        .getResultList()
        .stream()
        .map(JpaAuditEventEntity::toDomain)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public AuditEventPage findPage(AuditEventQuery query, Instant cursorTs, UUID cursorId) {
    int limit = query.limit();
    OffsetDateTime from = query.from().atOffset(ZoneOffset.UTC);
    OffsetDateTime to = query.to().atOffset(ZoneOffset.UTC);
    String actor = query.actor();
    String resource = query.resource();
    OffsetDateTime cursorAt = cursorTs == null ? null : cursorTs.atOffset(ZoneOffset.UTC);

    @SuppressWarnings("unchecked")
    List<JpaAuditEventEntity> rows =
        entityManager
            .createNativeQuery(FIND_PAGE_SQL, JpaAuditEventEntity.class)
            .setParameter("from", from)
            .setParameter("to", to)
            .setParameter("actor", actor)
            .setParameter("resource", resource)
            .setParameter("cursor_ts", cursorAt)
            .setParameter("cursor_id", cursorId)
            .setParameter("limit_plus_one", limit + 1)
            .getResultList();

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
                  actor,
                  resource,
                  query.from(),
                  query.to(),
                  AuditEventCursor.V));
    }
    return new AuditEventPage(items, nextCursor, hasMore);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
