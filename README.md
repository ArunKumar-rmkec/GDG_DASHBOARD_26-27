# GDGoC Campus Dashboard Backend Core (`GDG_DASHBOARD_26-27`)

Modular, production-grade **Spring Boot 3.3.x (Java)** RESTful backend implementing the complete GDGoC Campus Dashboard specification for Team Management, 3-GDG-Lead Consensus, HR Bio-Data Dual-Authorization, Scheduled Permission Grants, 10-Day Post-Termination Lifecycle, Task Assigning & Tracking, and Event Management.

## Tech Stack
- **Framework**: Spring Boot `3.3.5` (Web, Security, Data JPA, Validation, Actuator, Mail, Thymeleaf)
- **Database & Migrations**: Flyway (`V1__init_gdg_dashboard_schema.sql`), PostgreSQL 15+ & H2 (PostgreSQL compatibility mode)
- **Security**: Stateless JWT (`jjwt 0.12.6`), BCrypt, Google OAuth2 / OIDC, RBAC + Dynamic Scheduled `PermissionGrant`s, 10-Day Post-Termination Filter
- **PDF Rendering**: Thymeleaf + Flying Saucer / OpenPDF (`%PDF-1.x`) + HMAC-SHA256 Pre-Signed URLs
- **Observability & Docs**: Springdoc OpenAPI 3 (`/v3/api-docs`, `/swagger-ui.html`), Actuator + Micrometer Prometheus (`/actuator/health`, `/actuator/prometheus`)

## Quick Start
```bash
mvn clean test
mvn spring-boot:run
```
