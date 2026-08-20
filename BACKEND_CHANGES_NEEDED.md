# Backend Changes Needed in integration-dashboard-service

The gateway forwards user context as headers to the backend.
The backend can optionally use these headers for additional filtering.

## Headers forwarded by gateway on every request:
- X-User-Email    → logged in user's email
- X-User-Name     → logged in user's full name
- X-User-Role     → USER / TEAM_LEAD / FUNCTION_LEAD / PRACTICE_HEAD / ADMIN
                     (highest privilege first: ADMIN > PRACTICE_HEAD >
                     FUNCTION_LEAD > TEAM_LEAD > USER — see UserRole.java)
- X-User-Practice → user's practice name from Integrators table

These headers are only trustworthy coming from the gateway itself —
JwtAuthenticationFilter strips any client-supplied X-User-* headers before
setting its own (trusted) values, on every request that reaches the
backend route.

The gateway only gates *which endpoints* a role may call (see
`role-access` in application.yml). *Which rows* of data a Function
Lead/Team Lead/etc. actually sees within an allowed endpoint (e.g. a
Function Lead only seeing their own function's data, a Practice Head
seeing everything in their practice) needs to be enforced here in the
backend, using X-User-Role together with X-User-Email / X-User-Practice.

## Change 1 — Add role column to Integrators entity ✅ done

`Integrators.java` already has a `role` field.

## Change 2 — Add getIntegratorByEmail endpoint in IntegrationController ✅ done

`IntegrationController.getIntegratorInfo` (GET `/api/integration/getIntegratorByEmail/{email}`)
already exists and now defaults to `"User"` (the new lowest-privilege role)
instead of the old `"VIEWER"`/`"GUEST"` values.

## Change 3 — Add findByEmail to IntegrationRepository ✅ done

`IntegrationRepository.findByEmail(String email)` already exists.

## Change 4 — Add getIntegratorByEmail to IntegrationService ✅ done

`IntegrationService.getIntegratorByEmail(String email)` already exists.

## Change 5 — Update role values in Integrators Excel/DB ⚠️ still needed

Populate the `role` column in the Integrators table/Excel with one of the
5 new role values (case-insensitive, spaces/underscores both work —
see `UserRole.parse` in the gateway):

- `Admin`          → superuser, full access across all practices
- `Practice_Head`  → oversees an entire practice
- `Function_Lead`  → leads a function within a practice
- `Team_Lead`      → leads a team under a function
- `User`           → default / everyone else

Any row with a blank, missing, or unrecognized role value is treated as
`User` by the gateway — it will never be rejected outright, just given the
lowest privilege.
