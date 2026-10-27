# energy-service

The energy of the house (epic HAS-324): the grid meter, the SOLAX and SOFAR inverters, the SOLAX
battery and the SolaX cloud - readings, history, the balance of the grid, alarms, and charging the
battery before a planned outage. Scaffolded by HAS-325; the features come with HAS-326 onwards.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The user writes the code in this
repository themselves; Claude's default role here is analysis, code review and security
review - the owner had Claude implement the scaffold (HAS-325) and reviews it in IntelliJ.

## Role in the system

- Is called by: the dashboard through `api-gateway-service` (`/home/energy`, `/home/energy/**`,
  every method - the "Energia" panel of HAS-341).
- Calls: nothing yet. Planned: the Shelly Pro 3EM (RPC, and its MQTT topics on `/device` - HAS-327),
  the dongle of the SOLAX (HAS-328), the SolaX Developer Platform (HAS-329), the SOFAR (HAS-330),
  `database-service` for the registry (HAS-326), `notification-service` for the alarms.
- Uses libraries: `cholewa-commons` 1.12.0 (the pooled `ConnectionFactory`, the required
  properties, the scheduler pool and `ScheduledPass`, the error contract), `smart-home-sdk` 1.14.0,
  `shelly-client` 2.2.0 **with its beans on** (`ShellyRpcClient`, `ShellyGen1Client`,
  `ShellyMqttDecoder` - for the meter of HAS-327; pinned by `EnergyServiceApplicationTest`).
- Database: own database `home-automation-energy`, pool 2 - with it 20 of the 47 connections of the
  managed database are allotted to this project (23 with ristretto).

## Build & run

- Build + tests: `mvn verify` - no Docker needed until the first test of PostgreSQL-only SQL.
- Local run: `home,local` Spring profiles, port `6014` (Actuator `8014`); in-cluster port `6200`,
  Actuator `8200`. Needs `database-password` and the other `database-*` values. **The real values
  are the production database** - a migration is rehearsed on a throwaway PostgreSQL, never by a
  local run (the recipe of water-service: PostgreSQL with `-c ssl=on` and the snakeoil
  certificate, `MSYS_NO_PATHCONV=1` in Git Bash; V1 was run that way, as a user granted
  `USAGE, CREATE` on `public` only).
- Spring Boot 4.1.2, logbook 4.2.0; own libraries on the latest releases, as the org rule asks of
  every service task.

## Specifics

- **One instance, never scaled**: the readings and the alarms will live in memory, so the
  Deployment is `Recreate`.
- **`database-password` has no default**, unlike the reference: `cholewa.required-properties`
  refuses the start before the first bean, so neither Flyway nor the pool tries a placeholder as
  the password. The `test` document gives it a value; the other `database-*` values keep their
  placeholders (a wrong host fails at the first query and at Flyway).
- **`V1` is an empty baseline** (only a comment): Flyway applies it and writes its history table,
  so the start has no `No migrations found` WARN and proves the grant.
- **`GET /status` answers every source with `reachable: null`** until the reading tasks - written
  out as `null`, never left out or `false`; `EnergySource` holds the order and the wire names
  (`solaxCloud`), the contract of the dashboard.
- **Scheduling is off under `test`** (`SchedulingConfig`, `@Profile("!test")`): no task yet;
  every reactive `@Scheduled` method that comes is wrapped in `ScheduledPass.bounded`, its limit
  below its delay; the scheduler has the pool of 10 of the library.
- **No broker yet**: the connection to `/device` comes with the meter (HAS-327), the notifications
  with the alarms - each with its password in `cholewa.required-properties`.
- **Tests use `mockwebserver3`** when a client gets its first test, never the legacy
  `mockwebserver` (JUnit 4).
- **Surefire activates the `test` profile for every class**; the `includes` match only
  `**/*Test.java` and `**/*IT.java`.
