# energy-service

The energy of the house (epic HAS-324): the grid meter, the SOLAX and SOFAR inverters, the SOLAX
battery and the SolaX cloud - readings, history, the balance of the grid, alarms, and charging the
battery before a planned outage. Scaffolded by HAS-325; the features come with HAS-326 onwards.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The user writes the code in this
repository themselves; Claude's default role here is analysis, code review and security
review - the owner had Claude implement the scaffold (HAS-325) and the grid meter (HAS-327), and
reviews them in IntelliJ.

## Role in the system

- Is called by: the dashboard through `api-gateway-service` (`/home/energy`, `/home/energy/**`,
  every method - the "Energia" panel of HAS-341).
- Consumes: `device.prod.energy` on `/device` (user `device`) - every device event, of which only the grid
  meter is read (HAS-327).
- Calls: the Shelly Pro 3EM over its RPC (`EM.GetStatus`, `EMData.GetStatus`, HAS-327). Planned: the dongle of the SOLAX (HAS-328), the SolaX Developer Platform (HAS-329), the SOFAR (HAS-330),
  `database-service` for the registry (HAS-326), `notification-service` for the alarms.
- Uses libraries: `cholewa-commons` 1.12.0 (the pooled `ConnectionFactory`, the required
  properties, the scheduler pool and `ScheduledPass`, the error contract), `smart-home-sdk` 1.14.0,
  `shelly-client` 2.3.0 **with its beans on** (`ShellyRpcClient`, `ShellyGen1Client`,
  `ShellyMqttDecoder`; pinned by `EnergyServiceApplicationTest`) - 2.3.0 brought `EmStatus`, `EmDataStatus`
  and their RPC reads for HAS-327.
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
- **`GET /status` answers a source not known with `reachable: null`** - the inverters and the cloud
  until their tasks, the meter until its first message - written out as `null`, never left out or
  `false`; `lastReadingAt` is left out while there is none. `EnergySource` holds the order and the
  wire names (`solaxCloud`), the contract of the dashboard.
- **Scheduling is off under `test`** (`SchedulingConfig`, `@Profile("!test")`); every reactive
  `@Scheduled` method is wrapped in `ScheduledPass.bounded`, its limit below its delay and pinned by a
  test that hangs the pass (the reconciliation 1 min, the snapshot 50 s); the scheduler has the pool of
  10 of the library.
- **The broker**: one connection, the auto-configured one, to `/device` (user `device`, `rabbitmq-password`),
  the listener with manual ack, prefetch 3 and observation (`EnergyServiceApplicationTest`). The notifications
  come with the alarms as a second connection that is **not a bean** (`NotificationConnection` of
  `cholewa-commons`): a `ConnectionFactory` bean would switch off the auto-configured one with its listener
  settings.
- **The grid meter** (HAS-327, package `meter`):
  - Known by `energy.meter.device-id` and `energy.meter.address` from the manifest, **not by the registry**:
    the Pro 3EM is there already as the BTHome gateway 79 of the garage gate sensors, and one device is one
    entry. Both have no default (`cholewa.required-properties`).
  - `DeviceEventConsumer` hands every event to `MeterEventRouter`, which picks the meter **by the routing key
    before a body is read** - another device, or a component of the meter that is not `em:0`, `emdata:0` or
    `online`, leaves no log line (about 130 messages a minute). A body that is not its topic is a WARN without
    the body; nothing errors out of the listener (`.contextCapture()` last).
  - `GridMeterTracker` holds the state in one `AtomicReference`: the readings and the counters with the house
    time they arrived at (cut to microseconds), `online` from the last will / a reading. **The later arrival
    wins**, MQTT or RPC - that is the reconciliation after a gap of the broker.
  - `MeterReconciliation` reads both over RPC 5 s after the start and every 5 min (bounded at 1 min, each call
    its own `onErrorResume`); a failed read changes nothing - MQTT owns `online`.
  - `GridSnapshot` writes `grid_reading` (V2) every minute, **only when a value changed** (the last row in
    memory; after a start the first one is written). The counters are Wh on the meter, kWh in the table and
    the API - a decimal shift (`BigDecimal.movePointLeft`), not a division (891057.42 / 1000 has a binary
    tail).
  - `GET /grid` (`GridService`) is the state in memory, never a call to the meter: what was not sent is left
    out, `stale` always written - no readings, older than `energy.meter.stale-after` (PT2M, 30 s - 1 h) or the
    meter off the broker; `direction` by `GridDirection.DEAD_BAND_WATTS` (20 W).
  - **Verified outside the cluster** (2026-10-27): the jar with `home,local` against a throwaway PostgreSQL
    (V2 after the released 0.1.0) and the real meter over RPC - `/grid` matched `EM.GetStatus` within one
    reading, the first snapshot written a minute after the start.
- **Tests use `mockwebserver3`** when a client gets its first test, never the legacy
  `mockwebserver` (JUnit 4).
- **Surefire activates the `test` profile for every class**; the `includes` match only
  `**/*Test.java` and `**/*IT.java`.
