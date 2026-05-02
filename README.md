# 🔐 RBAC Gateway

A production-ready **microservices backend** with Role-Based Access Control, JWT authentication, dynamic rate limiting, and an API Gateway built on Spring Boot 4 + Spring Cloud.

---

## 📐 Architecture Overview

```
                    ┌──────────────────────────────────────────┐
                    │              API Gateway                  │
                    │          (WebFlux · Port 8080)           │
                    │                                          │
                    │  ┌─────────────┐  ┌──────────────────┐  │
                    │  │ JWT Filter  │  │  Rate Limit      │  │
                    │  │  (Global)   │  │  Filter (Redis)  │  │
                    │  └─────────────┘  └──────────────────┘  │
                    │                                          │
                    │  ┌─────────────┐  ┌──────────────────┐  │
                    │  │   Dynamic   │  │  Circuit Breaker │  │
                    │  │   Routes    │  │  (Resilience4j)  │  │
                    │  │  (DB-backed)│  └──────────────────┘  │
                    │  └─────────────┘                        │
                    └──────────────┬───────────┬──────────────┘
                                   │           │
                     ┌─────────────┘           └─────────────┐
                     ▼                                        ▼
          ┌──────────────────┐                  ┌──────────────────────┐
          │   Auth Service   │                  │   Resource Service   │
          │   (Port 8081)    │                  │   (Port 8082)        │
          │                  │                  │                      │
          │  · Login/Logout  │                  │  · Protected APIs    │
          │  · Refresh Token │                  │  · RBAC Enforcement  │
          │  · Revoke Token  │                  │  · Permission Check  │
          │  · Google OAuth2 │                  │    (resource:action) │
          └────────┬─────────┘                  └──────────┬───────────┘
                   │                                        │
                   └──────────────┬─────────────────────────┘
                                  ▼
                     ┌────────────────────────┐
                     │       PostgreSQL        │
                     │   (Flyway Migration)   │
                     └────────────────────────┘
                                  ▲
                     ┌────────────┴───────────┐
                     │          Redis          │
                     │  · Rate limit buckets  │
                     │  · Config cache        │
                     └────────────────────────┘
```

---

## 🏗️ Modules

| Module | Port | Description |
|---|---|---|
| `api-gateway` | `8080` | Spring Cloud Gateway (WebFlux) — routing, JWT validation, rate limiting, Circuit Breaker |
| `auth-service` | `8081` | Authentication — login, refresh token, token revocation, Google OAuth2 |
| `resource-service` | `8082` | Protected resources — RBAC enforcement, permission-based access control |
| `migration` | — | Standalone Flyway module — schema versioning & seeding |
| `gateway-ui` | `5173` | React admin console — route management, rate limit management |

---

## 🛠️ Tech Stack

| Category | Technology |
|---|---|
| Framework | Spring Boot `4.0.5` |
| Cloud | Spring Cloud `Oakwood` |
| Gateway | Spring Cloud Gateway (WebFlux / Reactive) |
| Security | Spring Security, JJWT |
| OAuth2 | Google OAuth2 (Redirect flow + SDK flow) |
| Resilience | Resilience4j (Circuit Breaker) |
| Rate Limiting | Redis Token Bucket (Lua script, atomic) |
| Database | PostgreSQL + Spring Data R2DBC |
| Migration | Flyway (separate module) |
| Cache | Redis (Reactive — `spring-boot-starter-data-redis-reactive`) |
| Java | Java 21 |
| Frontend | React 19 + Vite + MUI v7 |
| Code Quality | SonarQube `2025.1` + JaCoCo |

---

## ✅ Implemented Features

### Authentication (`auth-service`)
- [x] Login / Logout
- [x] JWT Access Token + Refresh Token
- [x] Token Revocation (blacklist / DB-backed)
- [x] Google OAuth2 — Redirect flow
- [x] Google OAuth2 — SDK flow (token exchange)

### Authorization
- [x] Role-Based Access Control (RBAC) in `resource-service`
- [x] Advanced RBAC — permissions encoded as `resource:action` (e.g., `user:read`, `order:delete`) embedded in JWT claims
- [x] API Gateway — JWT validation filter (global, order `-100`)

### Rate Limiting (`api-gateway`)
- [x] **Token Bucket** algorithm — implemented as an atomic Redis Lua script (no race conditions)
- [x] **Per-User isolation** — each `sub` claim gets its own bucket (`rate_limit:user:{username}`)
- [x] **Anonymous fallback** — requests without a valid JWT are bucketed by IP (`rate_limit:ip:{ip}`)
- [x] **DB-backed config** — replenish rate & burst capacity stored in `rate_limit_config` table
- [x] **Per-User override** — individual users can have custom limits that override the global default
- [x] **Redis config cache** — configs cached with TTL 5 min, auto-invalidated on admin update
- [x] **Fail-open / fail-closed** — configurable behavior when Redis is unavailable
- [x] **Rate limit headers** — `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Replenish-Rate`
- [x] **429 JSON response** — structured error body with `timestamp`, `status`, `message`, `path`
- [x] **Excluded paths** — public endpoints (login, register, OAuth2) bypass rate limiting

### Infrastructure
- [x] Spring Cloud Gateway — dynamic routes loaded from PostgreSQL (`gateway_routes` table)
- [x] Flyway as a standalone `migration` module
- [x] Circuit Breaker with Resilience4j (`api-gateway`)
- [x] Redis — rate limit state + config cache
- [x] Integration tests with Testcontainers (PostgreSQL + Redis)
- [x] SonarQube `2025.1` — static analysis + code coverage (JaCoCo)

### Admin Console (`gateway-ui`)
- [x] Route management — create, edit, delete, enable/disable, permission assignment
- [x] Rate limit management — view global default, create/edit/delete per-user overrides

---

## 🔑 Permission Model

Permissions follow the `resource:action` convention and are embedded directly in the JWT payload:

```json
{
  "sub": "alice@example.com",
  "roles": ["ROLE_ADMIN", "ROLE_USER"],
  "permissions": [
    "user:read",
    "user:write",
    "order:read",
    "product:delete"
  ],
  "exp": 1712345678
}
```

The `resource-service` validates these permissions using Spring Security method-level annotations or a custom `PermissionEvaluator`.

---

## 🚦 Rate Limiting

### How It Works

Every authenticated request passes through `RateLimitFilter` (order `-99`, runs after JWT validation at `-100`):

```
Request
  │
  ├── JwtAuthenticationFilter (-100) → validates JWT, extracts claims
  │
  └── RateLimitFilter (-99)
        │
        ├── Resolve bucket key
        │     ├── Authenticated → "user:{username}"
        │     └── Anonymous     → "ip:{clientIp}"
        │
        ├── Lookup config (RateLimitConfigService)
        │     ├── Redis cache hit  → return [replenishRate, burstCapacity]
        │     ├── DB: per-user override (username = ?)
        │     ├── DB: global default (username IS NULL)
        │     └── Fallback: application.yml
        │
        ├── Execute Redis Lua script (atomic Token Bucket)
        │     ├── tokens > 0 → decrement, set headers, forward
        │     └── tokens = 0 → 429 Too Many Requests
        │
        └── On Redis failure → fail-open (allow) or fail-closed (block)
```

### Config Priority

```
Per-User Override (rate_limit_config WHERE username = 'alice')
      ↓ not found
Global Default (rate_limit_config WHERE username IS NULL)
      ↓ not found
application.yml fallback (rate-limit.replenish-rate / burst-capacity)
```

### Redis Keys

```
rate_limit:user:alice@example.com.tokens      # token bucket state
rate_limit:user:alice@example.com.timestamp   # last refill timestamp
rate_limit:ip:203.0.113.42.tokens             # anonymous IP bucket
rl_cfg:alice@example.com                      # config cache (TTL 5 min)
rl_cfg:__default__                            # global default cache
```

### Admin API

```
GET    /api/admin/rate-limits              → list all configs
GET    /api/admin/rate-limits/default      → global default
GET    /api/admin/rate-limits/user/{user}  → per-user override
POST   /api/admin/rate-limits              → create override (or update global if username null)
PUT    /api/admin/rate-limits/{id}         → update (partial)
DELETE /api/admin/rate-limits/{id}         → delete per-user override
```

Cache is automatically invalidated after every write operation.

---

## 🚀 Getting Started

### Prerequisites

- Java 21
- Docker & Docker Compose
- Node.js 20+ (for `gateway-ui`)

### Run with Docker Compose

```bash
cp .env.example .env   # fill in your values
docker compose up -d
```

This starts PostgreSQL, Redis, Redis Commander (UI at `http://localhost:8090`), and SonarQube (UI at `http://localhost:9000`).

### Run services individually

```bash
# 1. Start infrastructure
docker compose up -d postgres redis

# 2. Run database migrations
cd migration && ./mvnw spring-boot:run

# 3. Start auth-service
cd auth-service && ./mvnw spring-boot:run

# 4. Start resource-service
cd resource-service && ./mvnw spring-boot:run

# 5. Start api-gateway
cd api-gateway && ./mvnw spring-boot:run

# 6. Start admin UI (optional)
cd gateway-ui && npm install && npm run dev
```

### Run tests

```bash
# Integration tests (requires Docker for Testcontainers)
cd api-gateway && ./mvnw test
```

Testcontainers will spin up PostgreSQL and Redis automatically. No local infra needed.

---

## 📡 API Endpoints

### Auth Service (via Gateway)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/auth/login` | Authenticate and receive tokens |
| `POST` | `/api/auth/register` | Register a new account |
| `POST` | `/api/auth/refresh` | Exchange refresh token for new access token |
| `POST` | `/api/auth/logout` | Revoke current refresh token |
| `POST` | `/api/auth/logout-all` | Revoke all refresh tokens for the user |
| `GET` | `/api/auth/google` | Initiate Google OAuth2 redirect flow |
| `POST` | `/api/auth/google/sdk` | Exchange Google ID token (SDK flow) |

### Resource Service (via Gateway)

> All endpoints require a valid `Authorization: Bearer <accessToken>` header.

| Method | Path | Required Permission |
|---|---|---|
| `GET` | `/api/resources/products` | `products:READ` |
| *(add your own routes)* | | |

### Gateway Admin API

> Requires `ROLE_ADMIN`.

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/admin/routes` | List all routes |
| `POST` | `/api/admin/routes` | Create route |
| `PUT` | `/api/admin/routes/{id}` | Update route |
| `DELETE` | `/api/admin/routes/{id}` | Delete route |
| `PATCH` | `/api/admin/routes/{id}/toggle` | Enable / disable route |
| `GET` | `/api/admin/rate-limits` | List all rate limit configs |
| `GET` | `/api/admin/rate-limits/default` | Get global default |
| `GET` | `/api/admin/rate-limits/user/{username}` | Get per-user override |
| `POST` | `/api/admin/rate-limits` | Create per-user override |
| `PUT` | `/api/admin/rate-limits/{id}` | Update config |
| `DELETE` | `/api/admin/rate-limits/{id}` | Delete per-user override |

---

## ⚙️ Configuration

### `api-gateway/application.yml`

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}

jwt:
  secret: ${JWT_SECRET}

rate-limit:
  replenish-rate: 20        # fallback: tokens/s if DB unavailable
  burst-capacity: 40        # fallback: max tokens in bucket
  allow-on-redis-failure: true   # fail-open when Redis is down
  excluded-paths:
    - /api/auth/login
    - /api/auth/register
    - /api/auth/refresh
    - /api/auth/logout
    - /oauth2/**
    - /actuator/**

resilience4j:
  circuitbreaker:
    instances:
      authServiceCB:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
```

### `auth-service` — JWT & OAuth2

```yaml
jwt:
  secret: ${JWT_SECRET}
  access-token-expiry: 900      # 15 minutes
  refresh-token-expiry: 604800  # 7 days

spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
```

---

## 🗂️ Database Schema

Flyway migrations in `migration/src/main/resources/db/auth/`:

| Version | Description |
|---|---|
| `V1.0` | Init — users, roles, user_roles |
| `V1.2` | RBAC tables — permissions, role_permissions |
| `V1.4` | Gateway routes (`gateway_routes`) |
| `V1.5` | Route permissions (`route_permissions`) |
| `V1.6` | Rate limit config (`rate_limit_config`) |

Run migrations:

```bash
cd migration && ./mvnw flyway:migrate
```

### `rate_limit_config` table

```sql
CREATE TABLE rate_limit_config (
    id              BIGSERIAL    PRIMARY KEY,
    username        VARCHAR(255) NULL UNIQUE,  -- NULL = global default
    replenish_rate  INT          NOT NULL,
    burst_capacity  INT          NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    description     VARCHAR(500) NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

`username = NULL` is the global default row (one per system, cannot be deleted).

---

## 🧱 Project Structure

```
rbac-gateway/
├── api-gateway/
│   └── src/main/java/.../
│       ├── filter/              # JwtAuthenticationFilter, RateLimitFilter
│       ├── ratelimit/           # RateLimitConfigService, entity, repo, admin API
│       ├── config/              # GatewayProperties, RateLimitProperties
│       ├── route/               # DatabaseRouteDefinitionRepository
│       ├── admin/               # AdminRouteController, AdminRouteService
│       └── validator/           # JwtValidator
│   └── src/main/resources/
│       ├── application.yml
│       ├── application-perf.yml
│       └── scripts/
│           └── rate_limit.lua   # Token Bucket Lua script (optimized)
│
├── auth-service/
│   └── src/main/java/.../
│       ├── controller/          # Login, refresh, OAuth2 endpoints
│       ├── service/             # TokenService, OAuth2Service
│       ├── security/            # JWT provider, token revocation
│       └── entity/              # User, Role, RefreshToken
│
├── resource-service/
│   └── src/main/java/.../
│       ├── controller/          # Protected resource endpoints
│       └── security/            # PermissionEvaluator, RBAC config
│
├── migration/
│   └── src/main/resources/db/auth/
│       └── V*.sql
│
├── gateway-ui/                  # React + Vite + MUI admin console
│   └── src/
│       ├── api/                 # routeApi.js, rateLimitApi.js
│       ├── hooks/               # useRoutes.js, useRateLimits.js
│       ├── pages/               # RoutesPage.jsx, RateLimitPage.jsx
│       └── components/
│           ├── routes/          # RouteTable, RouteFormDialog, ...
│           ├── ratelimit/       # RateLimitTable, RateLimitFormDialog, ...
│           └── layout/          # Sidebar
│
├── k6/
│   ├── scenarios/
│   │   ├── auth_flow.js         # Login → token → resource flow
│   │   ├── rate_limit.js        # Throughput + 429 behavior
│   │   └── circuit_breaker.js   # Downstream failure simulation
│   ├── helpers/
│   │   └── auth.js              # Shared login helper
│   └── script.js                # Entry point
│
├── docker/
│   └── redis/redis.conf
├── docker-compose.yml
├── sonar-project.properties
└── .env.example
```

---

## 🔒 Security Flow

```
Client
  │
  │  POST /api/auth/login  { username, password }
  ▼
API Gateway ──► Auth Service
                  │  validates credentials
                  │  returns { accessToken, refreshToken }
  ◄──────────────
  │
  │  GET /api/resources/products   Authorization: Bearer <accessToken>
  ▼
API Gateway
  │  JwtAuthenticationFilter (-100): validate signature + expiry
  │  RateLimitFilter (-99): Token Bucket check via Redis
  │    ├── allowed → forward + add X-RateLimit-* headers
  │    └── blocked → 429 Too Many Requests (JSON)
  ▼
Resource Service
  │  @PreAuthorize / PermissionEvaluator checks JWT claims
  │  e.g. requires "products:READ"
  ▼
  Response 200 OK  (or 403 Forbidden)
```

---

## 🌍 Environment Variables

Copy `.env.example` to `.env` before running.

### Database

| Variable | Description | Default |
|---|---|---|
| `DB_URL` | R2DBC PostgreSQL URL | `r2dbc:postgresql://localhost:5432/postgres` |
| `DB_USERNAME` | Database username | `postgres` |
| `DB_PASSWORD` | Database password | — |

### Redis

| Variable | Description | Default |
|---|---|---|
| `REDIS_HOST` | Redis hostname | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis password (if any) | — |

### JWT

| Variable | Description | Example |
|---|---|---|
| `JWT_SECRET` | HMAC signing secret (Base64, min 256-bit) | *(generate with `openssl rand -base64 32`)* |

### Google OAuth2

| Variable | Description | Where to get |
|---|---|---|
| `GOOGLE_CLIENT_ID` | OAuth2 client ID | [Google Cloud Console](https://console.cloud.google.com/) |
| `GOOGLE_CLIENT_SECRET` | OAuth2 client secret | Google Cloud Console |

### Internal

| Variable | Description |
|---|---|
| `INTERNAL_SECRET` | Shared secret for service-to-service calls |

---

## 🧪 Testing

Integration tests live in `api-gateway/src/test/` and use **Testcontainers** — no manual infra setup needed.

| Test class | Coverage |
|---|---|
| `GatewayIntegrationTest` | Route forwarding, JWT validation, 401/403 flows |
| `CircuitBreakerIntegrationTest` | Open/half-open/closed states, fallback responses |
| `RateLimitIntegrationTest` | Token bucket behavior, per-user isolation, refill, excluded paths, 429 format |

```bash
cd api-gateway
./mvnw test
```

Testcontainers pulls `postgres:16-alpine` and `redis:7-alpine` automatically on first run.

---

## ⚡ Performance Testing

### Stack

| Tool                                 | Role                                                    |
|--------------------------------------|---------------------------------------------------------|
| [JMeter](https://jmeter.apache.org/) | Load generator — scripted scenarios, thresholds, checks |
| Docker Compose                       | Spin up full stack với `perf` profile                   |

### Kịch bản test

[RBAC Gateway test plan](<performance-test/RBAC%20Gateway.jmx>)

---
### Kết quả đo được (khi service đã chạy ổn định)

> Môi trường: Docker Compose trên Windows 11 / WSL2, mỗi service giới hạn **2 CPU + 1 GB RAM**, api gateway giới hạn **4 CPU + 3 GB RAM**.

#### Trực tiếp vs qua Gateway tải cao (500 VUs, 200K Requests)

| Metric      | Direct `:8081, :8082` | Via Gateway `:8080` | Overhead |
|-------------|-----------------------|---------------------|----------|
| Throughput  | **2823 req/s**        | **2790 req/s**      | ~0.1%    |
| p50 latency | 50 ms                 | 177 ms              | +127 ms  |
| p95 latency | 683 ms                | 253 ms              | -430 ms  |
| p99 latency | 1087 ms               | 308 ms              | -779 ms  |
| Error rate  | 0%                    | 0%                  | —        |

#### Trực tiếp vs qua Gateway tải thấp (500 VUs, 1000 Requests)

| Metric      | Direct `:8081, :8082` | Via Gateway `:8080` | Overhead |
|-------------|-----------------------|---------------------|----------|
| Throughput  | **938 req/s**         | **931 req/s**       | ~0.1%    |
| p50 latency | 4 ms                  | 5 ms                | +1 ms    |
| p95 latency | 16 ms                 | 9 ms                | -7 ms    |
| p99 latency | 38 ms                 | 11 ms               | -27 ms   |
| Error rate  | 0%                    | 0%                  | —        |

> Gateway overhead ~1–2 ms/request: JWT validation + Redis rate limit (Lua) + route cache lookup + header mutation + Circuit Breaker state check.

### Các vấn đề đã phát hiện và xử lý

| Vấn đề | Nguyên nhân gốc | Fix |
|---|---|---|
| Redis command timed out (lần 1) | `appendonly yes` → fsync blocking dưới tải | Tắt AOF + RDB snapshot trong `redis.conf` |
| Redis command timed out (lần 1) | Redis `timeout: 1s` quá thấp | Tăng lên `3s` trong `application.yml` |
| Redis command timed out (lần 1) | `KEYS *` blocking trong `invalidateAllConfigCache` | Thay bằng `SCAN` với `count(100)` |
| Redis command timed out (lần 2) | `commons-pool2` thiếu → Lettuce dùng 1 shared connection | Thêm dependency + `pool.enabled: true` |
| Redis command timed out (lần 2) | Lua script gọi 4 Redis commands riêng lẻ | Gộp thành `MGET` + `MSET` → 3 round-trips |

---

## 📊 Code Quality — SonarQube

The project is configured with **SonarQube 2025.1** (Community edition) and **JaCoCo** for code coverage across all three service modules.

### Infrastructure

SonarQube runs as a Docker service alongside the rest of the stack:

```bash
docker compose up -d sonarqube
```

UI available at **`http://localhost:9000`** (default credentials: `admin` / `admin`).

> **First-time setup:** SonarQube stores its data in the `sonarqube` PostgreSQL database. Create it once before starting:
> ```bash
> docker exec -it rbac-postgres psql -U postgres -c "CREATE DATABASE sonarqube;"
> ```
> On Linux hosts, also run: `sudo sysctl -w vm.max_map_count=524288`

### Running Analysis

```bash
# 1. Build + run tests + generate JaCoCo coverage reports
./mvnw clean verify

# 2. Push results to SonarQube (replace with your token)
./mvnw sonar:sonar -Dsonar.token=<YOUR_TOKEN>
```

Or in a single command:

```bash
./mvnw clean verify sonar:sonar -Dsonar.token=<YOUR_TOKEN>
```

### Generating a Token

1. Go to `http://localhost:9000` → **My Account → Security**
2. Under **Generate Tokens**, enter a name and click **Generate**
3. Copy the token — it's shown only once

### What Gets Analyzed

| Module | Sources | Coverage report |
|---|---|---|
| `api-gateway` | `src/main/java` | `target/site/jacoco/jacoco.xml` |
| `auth-service` | `src/main/java` | `target/site/jacoco/jacoco.xml` |
| `resource-service` | `src/main/java` | `target/site/jacoco/jacoco.xml` |

The following are excluded from analysis and coverage metrics: `*Application.java`, `config/`, `entity/`, `dto/`, `exception/`, `migration/`.

### Configuration Files

| File | Purpose |
|---|---|
| `sonar-project.properties` | Multi-module scanner config (sources, tests, coverage paths, exclusions) |
| `pom.xml` (root) | `sonar-maven-plugin` + `jacoco-maven-plugin` in `pluginManagement` |
| Each module `pom.xml` | Activates JaCoCo; `maven-surefire-plugin` uses `@{argLine}` so the JaCoCo agent injects correctly |

---

## 📄 License

MIT
