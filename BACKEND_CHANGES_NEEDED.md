# Backend Changes Needed in integration-dashboard-service

The gateway forwards user context as headers to the backend.
The backend can optionally use these headers for additional filtering.

## Headers forwarded by gateway on every request:
- X-User-Email    → logged in user's email
- X-User-Name     → logged in user's full name
- X-User-Role     → ADMIN / MANAGER / VIEWER / GUEST
- X-User-Practice → user's practice name from Integrators table

## Change 1 — Add role column to Integrators entity

In Integrators.java add:
```java
private String role; // ADMIN / MANAGER / VIEWER
```

## Change 2 — Add getIntegratorByEmail endpoint in IntegrationController

```java
@GetMapping("/getIntegratorByEmail/{email}")
public ResponseEntity<?> getIntegratorByEmail(@PathVariable String email) {
    return integrationService.getIntegratorByEmail(email)
        .map(integrator -> ResponseEntity.ok((Object) Map.of(
            "email", integrator.getEmail(),
            "name", integrator.getName(),
            "role", integrator.getRole() != null ? integrator.getRole() : "VIEWER",
            "practice", integrator.getPractice() != null ? integrator.getPractice() : ""
        )))
        .orElse(ResponseEntity.ok(Map.of(
            "email", email,
            "role", "GUEST",
            "practice", ""
        )));
}
```

## Change 3 — Add findByEmail to IntegrationRepository

```java
Optional<Integrators> findByEmail(String email);
```

## Change 4 — Add getIntegratorByEmail to IntegrationService

```java
public Optional<Integrators> getIntegratorByEmail(String email) {
    return integrationRepository.findByEmail(email);
}
```

## Change 5 — Update role values in Integrators Excel/DB

Add a role column to your integrators.xlsx and populate with:
- ADMIN   → Practice leads, managers
- MANAGER → Senior engineers, team leads
- VIEWER  → All other team members
