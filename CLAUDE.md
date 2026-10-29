# energy-service

The energy of the house (epic HAS-324): the grid meter, the SOLAX and SOFAR inverters, the SOLAX
battery and the SolaX cloud - readings, history, the balance of the grid, alarms, and charging the
battery before a planned outage. Scaffolded by HAS-325; the features come with HAS-326 onwards.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The user writes the code in this
repository themselves; Claude's default role here is analysis, code review and security
review - the owner had Claude implement the scaffold (HAS-325), the grid meter (HAS-327) and the
SOLAX (HAS-328), and reviews them in IntelliJ.

## Role in the system

- Is called by: the dashboard through `api-gateway-service` (`/home/energy`, `/home/energy/**`,
  every method - the "Energia" panel of HAS-341).
- Consumes: `device.prod.energy` on `/device` (user `device`) - every device event, of which only the grid
  meter is read (HAS-327).
- Calls: the Shelly Pro 3EM over its RPC (`EM.GetStatus`, `EMData.GetStatus`, HAS-327); the dongle of the
  SOLAX over its local API and `database-service` for the inverters of the device registry
  (`GET /home/device/registry?kind=inverter&enabled=true`, HAS-328). Planned: the SolaX Developer Platform
  (HAS-329), the SOFAR (HAS-330), `notification-service` for the alarms.
- Uses libraries: `cholewa-commons` 1.12.0 (the pooled `ConnectionFactory`, the required
  properties, the scheduler pool and `ScheduledPass`, the error contract), `smart-home-sdk` 1.15.0 (the kind
  `inverter` and `InverterBinding`, HAS-326),
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

- **One instance, never scaled**: the readings and the alarms live in memory, so the
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
- **The inverters come from the device registry** (HAS-328, package `registry`): `InverterRegistry` reads
  `?kind=inverter&enabled=true` at the start and every 5 min (bounded at 1 min); a failed read keeps what it had,
  and before the first read succeeds nothing is known - `GET /inverters` answers `503 REGISTRY_UNAVAILABLE`. One
  inverter per brand (`of(InverterBrand)`). The client of database-service has the timeouts and pool limits of
  appliance-service (`DatabaseServiceClientConfig`, `internal.service.database`).
- **The SOLAX** (HAS-328, package `solax`):
  - `SolaxClient` posts `optType=ReadRealTimeData&pwd=<registrationNumber>` form-encoded to `http://<address>/`, a
    **new connection for every read** (`HttpClient.newConnection()` - the dongle is small), 3 s to connect, 5 s to
    answer, nothing retried. The dongle answers **`text/html`**, so the body is parsed by `Type33`, not by the
    codecs. A wrong registration number answers 200 with `0:code,message:"failed"}` (not JSON -
    `RegistrationRefusedException`); a request without `pwd` gets no answer; an empty `pwd` is accepted (firmware
    1.007.02) - the registry number is sent anyway. The registration number travels in the body Logbook logs,
    within the accepted LAN-only access (HAS-326).
  - `SolaxReader` reads every `energy.solax.interval` (PT15S, 10 - 60 s; bounded at 9 s): nothing while the registry
    has no SOLAX with an address and a registration number; an answer for another inverter serial than the
    registry's, or one without that serial, is a failed read; so is a word of `Data` that is not a number. A failed
    read - and a SOLAX gone from a registry that was read - marks the SOLAX unreachable and keeps its last reading.
    Its WARN is **one an hour per kind of failure** (`FailureLog`: the class and the cause before its details;
    another kind at once, an INFO on the return) - a dongle that is off is not a WARN every 15 s. The `@Scheduled`
    of the reader has `timeUnit = SECONDS`, so a bare number of `energy.solax.interval` is seconds there too.
  - Known and accepted: the staleness compares house times (`LocalDateTime`), so in the hour the clocks go back a
    reading from before the change looks up to an hour newer - as in `GridService`.
  - `SolaxTracker` holds the state in one `AtomicReference`; a change of the run mode is an INFO with its raw code.
  - `SolaxSnapshot` writes `inverter_reading` and `battery_reading` (V3) every minute, each only when a value
    changed - the PV of both strings together and each string, 0 at night.
  - The API: `GET /inverters` (every inverter of the registry; the SOFAR with its registry entry and `stale` until
    HAS-330), `GET /inverters/{solax|sofar}` (`404 NOT_FOUND_INVERTER`), `GET /battery` (`remainingKwh` = SOC of
    `energy.solax.battery-capacity`, 26.5 kWh, the usable capacity SolaxCloud gives; `direction` by
    `BatteryDirection.DEAD_BAND_WATTS`, 20 W), `stale` after `energy.solax.stale-after` (PT1M) or a failed read;
    `GET /status` - `solax.reachable` is the last read. A value the map does not establish is left out.
- **The map of type 33** (`Type33`, pinned by `Type33Test` on `src/test/resources/solax/*.json` - answers of
  2026-10-23 19:52, 2026-10-27 20:49, 2026-10-28 10:27 and 2026-10-29 19:09 with invented dongle and inverter serials;
  the last three compared with SolaxCloud (the last one with the Pro 3EM as well), Developer Platform `v2/device/realtime_data` and `v2/plant/realtime_data`, within one
  refresh - by night and by day).
  Words of `Data`, unsigned unless marked; totals are two words, the low one first:

  | Index | Value | Served |
  |---|---|---|
  | 0-2 | AC voltage per phase ÷10 V | `phases.*.voltage` |
  | 3-5 | AC current per phase ÷10 A | `phases.*.current` |
  | 6 | frequency ÷100 Hz | `frequency` |
  | 7 | power factor ÷100 | `powerFactor` |
  | 8-10 | AC power per phase W, signed | `phases.*.activePower` |
  | 11 | AC power W, signed | `acPower` |
  | 12 / 13 | PV1 / PV2 voltage ÷10 V (cloud `MPPT1Voltage`, `MPPT2Voltage`) | `pv.pv1.voltage`, `pv.pv2.voltage` |
  | 14 / 15 | PV1 / PV2 current ÷10 A | `pv.*.current` |
  | 16 / 17 | PV1 / PV2 power W (cloud `MPPT1Power`, `MPPT2Power`; their sum `MPPTTotalInputPower`) | `pv.*.power`, `pvPower` |
  | 18 | run mode (0 waiting, 1 checking, 2 normal, 3 fault, 4 permanent fault, 5 updating, 6 EPS check, 7 EPS, 8 self test, 9 idle, 10 standby) | `runMode` in words |
  | 32-33 | grid power of the inverter's meter W, **32-bit signed, low word first, negative while the house takes** (cloud `gridPower`) - followed the Pro 3EM within 5-100 W over six samples of an import of 1.5-1.8 kW; about 0 by day while the battery balances the house | `gridPower`, the sign turned: positive = taken, as `/grid` |
  | 36 | battery voltage ÷10 V | `battery.voltage` |
  | 37 | battery current ÷10 A, signed | `battery.current` |
  | 38 | battery power W, signed (negative = discharging) | `battery.power` |
  | 42 | inverter temperature °C | `temperature` |
  | 89 | battery SOH % | `battery.health` |
  | 91-92 | total AC output ÷10 kWh (cloud `totalACOutput`) | no |
  | 93 | AC output today ÷10 kWh (cloud `dailyACOutput`, the plant `dailyYield`) | no |
  | 94 / 96 | AC input total / today ÷10 kWh | no |
  | 97-98 / 99-100 | battery discharged / charged total ÷10 kWh | no |
  | 101 / 102 | battery discharged / charged today ÷10 kWh | `dischargedToday`, `chargedToday` |
  | 103-104 | total yield ÷10 kWh | `totalYield` |
  | 105 | yield today ÷10 kWh (cloud `dailyYield` of the inverter) | `dailyYield` |
  | 109-110 / 111-112 | exported / imported total ÷10 kWh (the inverter's own meter) | no |
  | 113 / 115 | exported / imported today ÷10 kWh | no |
  | 122-124 | the battery voltage, current, power again, from the BMS | no |
  | 125 | SOC % | `battery.soc` |
  | 127 | battery temperature °C, signed | `battery.temperature` |
  | 138 | battery cycles | `battery.cycles` |
  | 167-169 | voltage per phase at the grid connection point ÷10 V | no |

  Information[2] is the inverter serial (checked against the registry). The grid power of the SOLAX is served with the
  inverter only, not stored - the grid history is the Pro 3EM's (`grid_reading`, HAS-327). **Still unknown**: the EPS
  voltages and powers (0 while the grid is there). Not identified though non-zero: 40, 43-46, 48-49,
  88, 90, 106, 121, 126, 128, 130-137, 139-145, 162-166, 170-179, 186-191.
- **Tests use `mockwebserver3`** (the clients of the dongle and of database-service), never the legacy
  `mockwebserver` (JUnit 4).
- **Surefire activates the `test` profile for every class**; the `includes` match only
  `**/*Test.java` and `**/*IT.java`.
