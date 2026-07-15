// eip-app — composition root / main API app (BackendPlan.md §1). Depends on all modules; the
// only module producing a boot jar in this scaffold. Owns the Flyway migrations
// (DatabasePlan.md §7: db/migration lives here). Business logic arrives in Phase 0 SPRINT-01+.
plugins {
    id("eip.boot-app-conventions")
}
dependencies {
    implementation(project(":eip-core"))
    implementation(project(":eip-tenancy"))
    implementation(project(":eip-connectors"))
    implementation(project(":eip-ingestion"))
    implementation(project(":eip-analytics"))
    implementation(project(":eip-ai"))
    implementation(project(":eip-reports"))
    implementation(libs.spring.boot.starter)

    // Web + OpenAPI: the first /api/v1 read surface (TASK-0010). RFC 7807 problem+json is built into
    // Spring 6 (ProblemDetail); springdoc generates the OpenAPI 3 contract at /v3/api-docs.
    implementation("org.springframework.boot:spring-boot-starter-web")
    // Jakarta Bean Validation for @ConfigurationProperties records and API inputs (BackendPlan §11).
    implementation("org.springframework.boot:spring-boot-starter-validation")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    // Modulith annotations referenced by the library modules' package metadata must resolve on
    // this compile classpath too (annotation-only; the verification itself is a test concern).
    compileOnly(platform(libs.spring.modulith.bom))
    compileOnly(libs.spring.modulith.core)
    implementation(libs.springdoc.openapi.webmvc)

    // OIDC resource server + deny-by-default RBAC (SecurityModel §3/§4, M5 Wave S1a). Versions
    // managed by the Boot BOM (io.spring.dependency-management, boot-app-conventions).
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    testImplementation("org.springframework.security:spring-security-test")

    // Observability (TASK-0012, ObservabilityModel/BackendPlan §12): actuator health/info/prometheus,
    // Micrometer Prometheus registry for RED metrics, and Micrometer Tracing via the OpenTelemetry
    // bridge with OTLP export to the existing Compose OTel Collector. Versions from the Boot BOM.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // Persistence: JDBC + Flyway forward-only migrations + PostgreSQL driver (DatabasePlan §3/§7).
    // Versions are managed by io.spring.dependency-management (boot-app-conventions).
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Transactional outbox → Kafka spine (DEBT-017, BackendPlan §6): the relay publishes and the
    // work-item events consumer listens via spring-kafka; version managed by the Boot BOM.
    implementation("org.springframework.kafka:spring-kafka")

    // Integration test: real PostgreSQL 16 + pgvector via Testcontainers proves the deployable
    // V1 baseline + R__rls_policies apply and enforce RLS end-to-end (DatabasePlan §14).
    // spring-boot-starter-test supplies JUnit 5 + AssertJ (eip-app uses boot-app-conventions, not
    // modulith-conventions, so it needs its own test framework).
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Application-wide Spring Modulith verification + ArchUnit layering rules (BackendPlan §3).
    testImplementation(platform(libs.spring.modulith.bom))
    testImplementation(libs.spring.modulith.starter.core)
    testImplementation(libs.spring.modulith.docs)
    testImplementation(libs.archunit.junit5)
    // WireMock (BackendPlan §14: sync engine tested against WireMock'd connectors);
    // standalone (shaded) jar avoids Jetty/Tomcat classpath clashes.
    testImplementation(libs.wiremock.standalone)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
    // Real Kafka broker (KRaft) for OutboxKafkaIntegrationTest (DEBT-017); version managed by the
    // Boot-BOM-aligned Testcontainers BOM.
    testImplementation(libs.testcontainers.kafka)
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
