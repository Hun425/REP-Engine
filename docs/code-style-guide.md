# REP-Engine Kotlin + Spring Code Style Guide (v1)

## Scope

This document defines the **server-side** coding style for Kotlin + Spring modules in this repository.

Primary references:
1. Kotlin Coding Conventions
2. ktlint rules configured in this repository
3. This document (team-specific readability rules)

## Core Principles

1. Readability first
2. Explicitness over shorthand
3. Predictable structure across modules
4. Enforce by tooling where possible

## Kotlin Baseline

1. Do not use wildcard imports (`*`)
2. Keep imports explicit and sorted
3. Keep line length readable (soft limit: 120)
4. Prefer descriptive names over abbreviations
5. KDoc links use `@see docs/phase N.md` form

## Spring Structure Rules

1. Use constructor injection by default
2. If qualifier is needed on constructor parameter, use `@param:Qualifier("...")`
3. Keep bean wiring in `config` package, business logic in `service` package
4. Controller must not contain business logic or persistence logic
5. Repository access should stay in service/repository layer, not controller

## Controller Rules

1. Keep HTTP concerns in controller (request/response, status, validation errors)
2. Success path should be simple and easy to scan
3. Use early return for invalid/not-found branches
4. Validate request DTO/params at boundary (`@Valid`, constraint annotations)

## Service Rules

1. Service methods should express one business intent
2. Long methods should be split by business step, not by technical micro-helpers
3. Transactions are defined at service boundary
4. Avoid hidden side effects; external calls must be explicit in code flow

## Error Handling and Logging

1. Use a consistent error response policy at API boundary
2. Do not swallow exceptions silently
3. Logger style is consistent: top-level logger declaration (`private val log = KotlinLogging.logger {}`)
4. Log message must include business context keys (example: userId, productId, requestId)
5. Error logs should include exception object (`log.error(e) { "..." }`)

## Persistence and External I/O

1. Do not hardcode infrastructure names (index/topic/table) in business code
2. External resource names come from configuration (`application.yml`/properties)
3. Keep mapping logic (entity/document <-> domain model) explicit and testable

## Testing Rules

1. Unit tests validate business decisions and branching logic
2. Integration tests validate wiring with Spring + storage/message boundaries
3. Test names should describe behavior, not implementation detail
4. Add regression tests when fixing production-facing bugs

## Tooling and Enforcement

1. `.editorconfig` is the base formatting contract
2. `./gradlew ktlintFormat -q` for formatting
3. `./gradlew ktlintCheck -q` for CI style validation
4. `./gradlew test -q` for behavior regression check
5. detekt adoption is recommended as next phase for complexity/design smell checks
