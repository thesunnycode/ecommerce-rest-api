# E-Commerce REST API

A production-oriented e-commerce backend built with **Java 17** and **Spring Boot 3** — stateless
JWT authentication, role-based authorization, a normalized 10-table MySQL schema managed by Flyway
migrations, and real card payments through Stripe Checkout with webhook signature verification.

**24 REST endpoints** across **6 feature modules**.

<p>
  <img alt="Java 17"      src="https://img.shields.io/badge/Java-17-orange">
  <img alt="Spring Boot"  src="https://img.shields.io/badge/Spring%20Boot-3.4-brightgreen">
  <img alt="MySQL"        src="https://img.shields.io/badge/MySQL-8-blue">
  <img alt="Stripe"       src="https://img.shields.io/badge/Stripe-Checkout-635bff">
  <img alt="Maven"        src="https://img.shields.io/badge/Build-Maven-C71A36">
</p>

---

## Contents

- [Features](#features)
- [Tech stack](#tech-stack)
- [Architecture](#architecture)
- [Database schema](#database-schema)
- [API reference](#api-reference)
- [Getting started](#getting-started)
- [Example flow](#example-flow)
- [Design decisions](#design-decisions)
- [Roadmap](#roadmap)
- [Author](#author)

---

## Features

**Authentication & authorization**
- Stateless JWT authentication with short-lived **access tokens** (15 min) and long-lived
  **refresh tokens** (7 days)
- Refresh token delivered in an `HttpOnly`, `Secure` cookie scoped to `/auth/refresh`
- Passwords hashed with **BCrypt**
- Role-based access control (`USER` / `ADMIN`) with deny-by-default authorization
- Custom `JwtAuthenticationFilter` registered in the Spring Security filter chain

**Catalog**
- Product CRUD, restricted to `ADMIN`
- Public product browsing with optional category filtering
- Categories seeded via a Flyway migration

**Cart**
- Anonymous carts — no account required to start shopping
- Unguessable `UUID` cart identifiers
- Add / update quantity / remove item / clear cart
- Totals computed on read, so they can never drift from the items

**Orders & payments**
- Checkout converts a cart into an order, **snapshotting the unit price at purchase time**
- Stripe Checkout session created behind a `PaymentGateway` abstraction
- Asynchronous payment confirmation via webhook, with **HMAC signature verification**
- Order history, with ownership enforced at the service layer

**Engineering**
- Versioned schema migrations with **Flyway** (5 migrations)
- Compile-time DTO mapping with **MapStruct** — no runtime reflection
- Request validation with Jakarta Bean Validation, including a custom `@Lowercase` constraint
- Interactive **OpenAPI / Swagger UI** documentation
- Environment-specific configuration via Spring profiles; **no secrets in the repository**

---

## Tech stack

| Layer | Technology |
|---|---|
| Language | Java 17 (LTS) |
| Framework | Spring Boot 3.4 |
| Web | Spring MVC |
| Security | Spring Security + JJWT 0.12 |
| Persistence | Spring Data JPA / Hibernate |
| Database | MySQL 8 |
| Migrations | Flyway |
| Mapping | MapStruct 1.6 |
| Validation | Jakarta Bean Validation |
| Payments | Stripe Java SDK 29 |
| Docs | springdoc-openapi (Swagger UI) |
| Views | Thymeleaf (landing page only) |
| Build | Maven (wrapper included) |

---

## Architecture

### Layering

```
Controller   →  HTTP boundary only: binding, validation, status codes
Service      →  business logic and transaction boundaries
Repository   →  data access (Spring Data JPA)
```

Controllers never touch repositories directly, and services have no HTTP dependency — which keeps
the business logic unit-testable without a servlet container.

### Package by feature, not by layer

```
me.thesunnycode.store
├── auth/        JWT issuing & parsing, SecurityConfig, login/refresh
├── users/       registration, profile, addresses, roles, custom validator
├── products/    catalog, categories
├── carts/       cart aggregate and its items
├── orders/      orders, order items, payment status
├── payments/    PaymentGateway abstraction + Stripe implementation
├── admin/       admin-only surface
└── common/      global exception handling, shared DTOs, filters
```

Everything about one feature — controller, service, repository, entity, DTOs, mapper, and its
security rules — lives in a single package. A feature is one folder, which makes the codebase
navigable as it grows and lets internal helpers stay package-private.

### Modular security rules

Rather than one large `SecurityConfig` listing every URL rule in the application, each feature
package ships its own `SecurityRules` implementation:

```java
public interface SecurityRules {
    void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>
                   .AuthorizationManagerRequestMatcherRegistry registry);
}
```

`SecurityConfig` injects `List<SecurityRules>` and Spring supplies every implementation
automatically. Adding a module means adding a class — the central config is never modified.

### Access control matrix

| Endpoint | Access |
|---|---|
| `POST /auth/login`, `POST /auth/refresh` | Public |
| `POST /users` (register) | Public |
| `GET /products/**` | Public |
| `POST` / `PUT` / `DELETE /products/**` | **ADMIN** |
| `/carts/**` | Public (capability-based — see [Design decisions](#design-decisions)) |
| `POST /checkout/webhook` | Public (Stripe must reach it; verified by signature) |
| `/admin/**` | **ADMIN** |
| `/swagger-ui/**`, `/v3/api-docs/**` | Public |
| Everything else | Authenticated |

Authorization is **deny-by-default**: the catch-all `anyRequest().authenticated()` is evaluated
last, so a newly added endpoint requires authentication unless it is explicitly opened.

---

## Database schema

10 tables, built by 5 Flyway migrations.

```
users ──┬── 1:1 ── profiles           (shared primary key)
        ├── 1:N ── addresses
        ├── M:N ── wishlist ── products
        └── 1:N ── orders ── 1:N ── order_items ── products

categories ── 1:N ── products

carts ── 1:N ── cart_items ── products
```

| Table | Notes |
|---|---|
| `users` | BCrypt password hash; `role` enum stored as a string |
| `profiles` | One-to-one with `users` via a **shared primary key** |
| `addresses` | One user, many addresses |
| `categories` | `TINYINT` key — a deliberately small type for a small domain |
| `products` | `price` as `DECIMAL(10,2)` |
| `wishlist` | Join table, composite primary key, cascades on product delete |
| `carts` | `BINARY(16)` UUID primary key |
| `cart_items` | `UNIQUE (cart_id, product_id)` — a product cannot appear twice in one cart |
| `orders` | Stores `total_price`; status enum as string |
| `order_items` | Stores `unit_price` and `total_price` — an immutable purchase record |

### Migrations

| Version | Contents |
|---|---|
| `V1` | Initial schema — users, profiles, addresses, categories, products, wishlist |
| `V2` | Cart tables |
| `V3` | `role` column added to `users` (with a default, so existing rows stay valid) |
| `V4` | Order tables |
| `V5` | Seed data — 6 categories, 10 products |

---

## API reference

Interactive documentation is available at `/swagger-ui.html` when the application is running.

### Auth

| Method | Path | Access | Description |
|---|---|---|---|
| `POST` | `/auth/login` | Public | Authenticate; returns an access token and sets the refresh cookie |
| `POST` | `/auth/refresh` | Cookie | Exchange the refresh cookie for a new access token |
| `GET` | `/auth/me` | Authenticated | Current user |

### Users

| Method | Path | Access | Description |
|---|---|---|---|
| `POST` | `/users` | Public | Register (role is assigned server-side) |
| `GET` | `/users` | Authenticated | List users (`?sort=name\|email`) |
| `GET` | `/users/{id}` | Authenticated | Get a user |
| `PUT` | `/users/{id}` | Authenticated | Update a user |
| `DELETE` | `/users/{id}` | Authenticated | Delete a user |
| `POST` | `/users/{id}/change-password` | Authenticated | Change password (verifies the old one) |

### Products

| Method | Path | Access | Description |
|---|---|---|---|
| `GET` | `/products` | Public | List products (`?categoryId=`) |
| `GET` | `/products/{id}` | Public | Get a product |
| `POST` | `/products` | **ADMIN** | Create a product |
| `PUT` | `/products/{id}` | **ADMIN** | Update a product |
| `DELETE` | `/products/{id}` | **ADMIN** | Delete a product |

### Carts

| Method | Path | Access | Description |
|---|---|---|---|
| `POST` | `/carts` | Public | Create an empty cart; returns its UUID |
| `GET` | `/carts/{cartId}` | Public | Get a cart with items and total |
| `POST` | `/carts/{cartId}/items` | Public | Add a product (increments if already present) |
| `PUT` | `/carts/{cartId}/items/{productId}` | Public | Set item quantity (1–1000) |
| `DELETE` | `/carts/{cartId}/items/{productId}` | Public | Remove one item |
| `DELETE` | `/carts/{cartId}/items` | Public | Clear the cart |

### Orders

| Method | Path | Access | Description |
|---|---|---|---|
| `GET` | `/orders` | Authenticated | Current user's orders |
| `GET` | `/orders/{orderId}` | Authenticated | One order (403 if not yours) |

### Checkout

| Method | Path | Access | Description |
|---|---|---|---|
| `POST` | `/checkout` | Authenticated | Create an order and a Stripe Checkout session |
| `POST` | `/checkout/webhook` | Public | Stripe payment confirmation (signature verified) |

### Status codes

`200` OK · `201` Created (with `Location`) · `204` No Content · `400` Bad Request /
validation · `401` Unauthenticated · `403` Forbidden · `404` Not Found · `500` Server error

---

## Getting started

### Prerequisites

- JDK 17 or later
- MySQL 8 running locally
- A free [Stripe](https://stripe.com) account (test mode) and the
  [Stripe CLI](https://docs.stripe.com/stripe-cli) for webhooks

### 1. Clone

```bash
git clone https://github.com/thesunnycode/ecommerce-rest-api.git
cd ecommerce-rest-api
```

### 2. Configure environment variables

Copy `.env.example` to `.env` and fill in the three values. `.env` is gitignored — **no secret is
ever committed**.

```bash
cp .env.example .env
```

**`JWT_SECRET`** — the HMAC signing key. Generate one:

```bash
openssl rand -base64 32
```

If `openssl` isn't available, use [generate-random.org](https://generate-random.org) →
**Strings > API Tokens**.

**`STRIPE_SECRET_KEY`** — from your Stripe dashboard under **Developers → API keys**. Use the
**test mode** secret key.

**`STRIPE_WEBHOOK_SECRET_KEY`** — start the Stripe CLI listener and copy the signing secret it
prints:

```bash
stripe login
stripe listen --forward-to http://localhost:8080/checkout/webhook
```

### 3. Database

No manual setup needed. The dev datasource URL includes `createDatabaseIfNotExist=true`, and
**Flyway builds and seeds the entire schema on first startup**. Adjust the credentials in
`src/main/resources/application-dev.yaml` if your local MySQL differs.

### 4. Run

```bash
./mvnw spring-boot:run       # macOS / Linux
mvnw.cmd spring-boot:run     # Windows
```

The API starts on `http://localhost:8080`.

| Resource | URL |
|---|---|
| API | `http://localhost:8080` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI spec | `http://localhost:8080/v3/api-docs` |

### Profiles

| Profile | Purpose |
|---|---|
| `dev` (default) | Local MySQL, SQL logging enabled |
| `prod` | Datasource from `SPRING_DATASOURCE_URL`, logging off |

```bash
SPRING_PROFILES_ACTIVE=prod ./mvnw spring-boot:run
```

---

## Example flow

**1. Browse the catalog** — 10 products are seeded by migration `V5`.

```http
GET /products
GET /products?categoryId=2
```

**2. Create a cart** — no login required.

```http
POST /carts
→ 201 { "id": "9f8b...", "items": [], "totalPrice": 0 }
```

**3. Add an item**

```http
POST /carts/9f8b.../items
{ "productId": 1 }
```

**4. Register**

```http
POST /users
{ "name": "Sunny Singh", "email": "sunny@example.com", "password": "secret123" }
```

Email must be lowercase (enforced by the custom `@Lowercase` constraint) and the password 6–25
characters.

**5. Log in**

```http
POST /auth/login
{ "email": "sunny@example.com", "password": "secret123" }

→ 200 { "token": "eyJhbGciOi..." }
   Set-Cookie: refreshToken=...; HttpOnly; Secure; Path=/auth/refresh
```

**6. Check out**

```http
POST /checkout
Authorization: Bearer eyJhbGciOi...
{ "cartId": "9f8b..." }

→ 200 { "orderId": 1, "checkoutUrl": "https://checkout.stripe.com/..." }
```

The order is created with status `PENDING` and the cart is cleared. Open the returned URL and pay
with a Stripe test card:

```
Card    4242 4242 4242 4242
Expiry  any future date
CVC     any 3 digits
```

**7. Webhook confirms the payment**

Stripe POSTs `payment_intent.succeeded` to `/checkout/webhook`. The signature is verified against
the raw request body, the order id is read from the payment intent metadata, and the order status
becomes `PAID` (or `FAILED`).

**8. Review the order**

```http
GET /orders
Authorization: Bearer eyJhbGciOi...
```

---

## Design decisions

Notes on the choices that aren't obvious from the code.

### `order_items` snapshots the price

`order_items` stores `unit_price` and `total_price` even though `products.price` exists. This is
deliberate denormalization: if the order read the *current* product price, changing a product's
price would silently rewrite the value of every historical order, and a customer's invoice would
disagree with what they actually paid.

`cart_items` does the opposite — no price column at all, computed live from the product — because a
cart *should* reflect the current price.

> **Normalize current state; snapshot historical records.**

### Cart ids are UUIDs; everything else is auto-increment

Cart ids are exposed to anonymous clients. Sequential integers would let anyone enumerate other
carts, so `carts.id` is a `UUID` stored as `BINARY(16)` — unguessable, and therefore usable as a
capability: holding the id is what grants access. `BINARY(16)` rather than a 36-character string
keeps the clustered index compact.

Orders and products sit behind authorization and keep sequential keys, which are smaller and
index-friendlier.

### Money is `BigDecimal` / `DECIMAL(10,2)`, never `double`

Binary floating point cannot represent most decimal fractions exactly — `0.1 + 0.2` is
`0.30000000000000004`. Across many line items that error accumulates into real money.
`BigDecimal` is exact and forces an explicit `RoundingMode`.

### Payments sit behind an interface

`CheckoutService` depends on a two-method `PaymentGateway` interface, and
`StripePaymentGateway` is the only class in the codebase that imports the Stripe SDK. Swapping
providers means writing one new class; no business logic changes. It also makes `CheckoutService`
unit-testable against a fake gateway, with no network calls.

This is Dependency Inversion — the abstraction is owned by the domain, not by the vendor.

### The webhook is authoritative, not the browser

Order status is only ever changed by the Stripe webhook, never by a client callback. The browser
can't be trusted (anyone can POST "payment succeeded") and can't be relied on (the user may close
the tab after paying). Stripe retries the webhook if the endpoint is down.

Because that endpoint must be publicly reachable, every request's HMAC signature is verified
against the **raw** request body — which is why the controller accepts `@RequestBody String`
rather than a parsed object. Parsing and re-serializing the JSON would change the bytes and break
verification.

### `@EntityGraph` instead of eager mappings

Repository queries declare which associations to fetch per query:

```java
@EntityGraph(attributePaths = "items.product")
@Query("SELECT o FROM Order o WHERE o.customer = :customer")
List<Order> getOrdersByCustomer(@Param("customer") User customer);
```

This turns what would be an N+1 query cascade into a single join, while keeping the fetch strategy
a per-query decision rather than a global one baked into the entity.

### Business rules live on the entities

`Cart.addItem()` owns the rule that adding an already-present product increments its quantity
instead of inserting a duplicate row — which is what keeps the `UNIQUE (cart_id, product_id)`
constraint satisfied. `Order.fromCart()` owns the cart-to-order translation, including the price
snapshot. Services orchestrate; entities protect their own invariants.

### Secrets come from the environment

`application.yaml` holds only `${JWT_SECRET}`-style placeholders. Locally these resolve from a
gitignored `.env` via `spring-dotenv`; in production, from the platform's secret store.
`.env.example` is committed as documentation with empty values.

---

## Roadmap

Known gaps, in the order I'd address them.

- **Automated tests.** Coverage is the weakest part of this project. Priority: unit tests for the
  service layer with mocked repositories (`CheckoutService` first — it has real branching around
  empty carts and payment failure), then `@WebMvcTest` slices for controllers, then a few
  `@SpringBootTest` integration tests with Testcontainers against real MySQL.
- **Pagination.** `GET /products` and `GET /users` return every row. Both should take `Pageable`
  and return `Page`.
- **Ownership checks on user endpoints.** `PUT`/`DELETE /users/{id}` should verify the path id
  belongs to the caller — enforceable declaratively with
  `@PreAuthorize("#id == authentication.principal")`. The orders module already does this
  correctly via `Order.isPlacedBy()`; the pattern should be applied consistently.
- **Token type claim.** Access and refresh tokens are currently structurally identical, so a
  refresh token is accepted where an access token is expected. A `type` claim (with the filter
  rejecting anything that isn't `access`) fixes it, ideally with separate signing keys.
- **Refresh token rotation.** Issue a new refresh token on each use and treat reuse of an old one
  as theft — revoking the family and forcing re-login.
- **`SameSite=Strict` on the refresh cookie.** The path scoping limits exposure, but `SameSite` is
  the correct CSRF defense for a cookie-authenticated endpoint.
- **Structured logging.** Replace `System.out.println` in `LoggingFilter` with SLF4J, so levels,
  timestamps and correlation ids exist and log output is machine-parseable.
- **Idempotent checkout.** `POST /checkout` should accept an `Idempotency-Key` so a retry or a
  double-click can't create two orders. The webhook handler should record processed Stripe event
  ids before any side effect is added to it.
- **Cart ownership.** A nullable `user_id` on `carts` — null while anonymous, claimed on login —
  so a leaked cart id can't be used by someone else.
- **Move the Stripe call out of the transaction.** `CheckoutService.checkout()` holds a pooled
  database connection across an external HTTP call, which can exhaust the pool under load.

---

## Author

**Sunny Kr Singh** — backend developer, Bengaluru

[Website](https://thesunnycode.me) ·
[GitHub](https://github.com/thesunnycode) ·
[LinkedIn](https://linkedin.com/in/thesunnycode) ·
[LeetCode](https://leetcode.com/u/thesunnycode)

Built to work through a complete backend end to end — schema design, stateless authentication,
authorization, third-party payment integration, and asynchronous confirmation — rather than a
feature at a time.
