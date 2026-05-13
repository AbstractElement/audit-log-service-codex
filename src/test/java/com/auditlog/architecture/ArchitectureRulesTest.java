package com.auditlog.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.auditlog.application.AuditEventRepository;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureRulesTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.auditlog");

  @Test
  void apiInteractsOnlyWithApplicationLayer() {
    noClasses()
        .that()
        .resideInAPackage("com.auditlog.api..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.auditlog.domain..", "com.auditlog.infrastructure..")
        .check(CLASSES);
  }

  @Test
  void applicationDoesNotDependOnApiOrInfrastructure() {
    noClasses()
        .that()
        .resideInAPackage("com.auditlog.application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.auditlog.api..", "com.auditlog.infrastructure..")
        .check(CLASSES);
  }

  @Test
  void domainDoesNotDependOnFrameworksOrOuterLayers() {
    noClasses()
        .that()
        .resideInAPackage("com.auditlog.domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "jakarta.persistence..",
            "javax.persistence..",
            "org.hibernate..",
            "com.auditlog.api..",
            "com.auditlog.application..",
            "com.auditlog.infrastructure..")
        .check(CLASSES);
  }

  @Test
  void jpaAndHibernateAreUsedOnlyInInfrastructure() {
    noClasses()
        .that()
        .resideOutsideOfPackage("com.auditlog.infrastructure..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("jakarta.persistence..", "javax.persistence..", "org.hibernate..")
        .check(CLASSES);
  }

  @Test
  void infrastructureDoesNotDependOnApi() {
    noClasses()
        .that()
        .resideInAPackage("com.auditlog.infrastructure..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.auditlog.api..")
        .check(CLASSES);
  }

  @Test
  void persistenceEntitiesAreNotExposedOutsideInfrastructurePersistence() {
    classes()
        .that()
        .resideInAPackage("com.auditlog.infrastructure.persistence..")
        .and()
        .haveSimpleNameEndingWith("Entity")
        .should()
        .bePackagePrivate()
        .check(CLASSES);

    noClasses()
        .that()
        .resideOutsideOfPackage("com.auditlog.infrastructure.persistence..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.auditlog.infrastructure.persistence..")
        .check(CLASSES);
  }

  @Test
  void infrastructurePersistenceRepositoriesImplementApplicationPorts() {
    classes()
        .that()
        .resideInAPackage("com.auditlog.infrastructure.persistence..")
        .and()
        .haveSimpleNameEndingWith("Repository")
        .should()
        .beAssignableTo(AuditEventRepository.class)
        .check(CLASSES);
  }

  @Test
  void apiLayer_doesNotDependOnAuditEventCursor() {
    noClasses()
        .that()
        .resideInAPackage("com.auditlog.api..")
        .should()
        .dependOnClassesThat()
        .haveSimpleName("AuditEventCursor")
        .because("API must treat nextCursor as an opaque String (AC-5.1).")
        .check(CLASSES);
  }

  @Test
  void auditEventCursor_residesInApplicationPackage() {
    classes()
        .that()
        .haveSimpleName("AuditEventCursor")
        .should()
        .resideInAPackage("com.auditlog.application..")
        .because("Cursor encoding belongs to the Application layer (AC-5.1).")
        .check(CLASSES);
  }
}
