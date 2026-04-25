package com.auditlog.infrastructure.persistence;

import com.auditlog.application.AuditEventRepository;
import com.auditlog.application.AuditEventSearchCriteria;
import com.auditlog.domain.AuditEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaAuditEventRepository implements AuditEventRepository {

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

        query.select(root)
                .where(predicates.toArray(Predicate[]::new))
                .orderBy(builder.desc(root.get("timestamp")));

        return entityManager.createQuery(query)
                .setFirstResult(criteria.offset())
                .setMaxResults(criteria.limit())
                .getResultList()
                .stream()
                .map(JpaAuditEventEntity::toDomain)
                .toList();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
