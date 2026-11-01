# energy-service

[![CI](https://github.com/smart-home-automation-system/energy-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/energy-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_energy-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_energy-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_energy-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_energy-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/energy-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/energy-service?style=plastic)

---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/energy-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.2-blue?style=plastic)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_energy-service&metric=coverage)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_energy-service)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_energy-service&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_energy-service)

![GitHub issues](https://img.shields.io/github/issues/smart-home-automation-system/energy-service?style=plastic)
![GitHub contributors](https://img.shields.io/github/contributors/smart-home-automation-system/energy-service?style=plastic)
![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/smart-home-automation-system/energy-service?style=plastic)

![GitHub last commit](https://img.shields.io/github/last-commit/smart-home-automation-system/energy-service?style=plastic)
![GitHub commit activity](https://img.shields.io/github/commit-activity/m/smart-home-automation-system/energy-service?style=plastic)

---


# Description

The energy of the house (epic HAS-324): the grid meter (a Shelly Pro 3EM), the two inverters -
SOLAX X3-NEO-8K-LV with its 26.5 kWh battery and SOFAR HYD 5KTL-3PH - and the SolaX cloud. The
service will read them, keep the history and the balance of the grid (net-metering, in kWh),
alarm on a lost source, an overloaded phase or a lost grid, and charge the battery before a
planned outage of Tauron.

**Today it reads the grid meter and the SOLAX and watches the grid** on top of the scaffold of HAS-325:

- the Shelly Pro 3EM at the grid entry over MQTT and its RPC, a snapshot a minute in `grid_reading`,
  `GET /home/energy/grid` (HAS-327);
- the SOLAX inverter and its battery over the local API of its dongle every 15 s - the inverter from the
  device registry (kind `inverter`, HAS-326), a snapshot a minute in `inverter_reading` and
  `battery_reading`, `GET /home/energy/inverters` and `/battery` (HAS-328); the layout of the answer (type 33, in no
  public library) is established on answers compared with SolaxCloud by night and by day;
- **the outages of the grid** as the SOLAX sees them (HAS-334): the loss, the hourly reminders, a low battery and
  the return told on Discord - the loss, the low battery and the return also by SMS to the administrators of the
  household - stored in `grid_outage`, `GET /home/energy/outages` and the grid in `/status`;
- **the sources watched** (HAS-332): the SOLAX dongle and the grid meter silent, a phase of the meter overloaded -
  told on Discord, stored in `source_alert`.

**The outages of the grid** - only the inverter knows the grid: the Pro 3EM goes dark with an empty battery, and the
cluster runs in the house.

- **Lost** after two reads of the SOLAX in a row (15 s apart) with the run mode EPS check / EPS, or no voltage
  (below 100 V) on any phase at the grid connection point; **back** after two reads with all three phases at 100 V or
  more outside EPS - in any run mode: with the battery empty the SOLAX answers a run mode outside its list (21).
  One or two phases down decide nothing. The outage starts and ends at the first of the two reads.
- An alert at the loss with the time, the state of charge and the time left at the power the battery gives
  (down to the 10 % the SOLAX stops the EPS at); a reminder every hour (`energy.outage.reminder-every`); one alert
  more when the battery falls below `energy.outage.low-soc` (20 %); an info at the return with the length. The
  loss, the low battery and the return are marked for an SMS (`publishAlertSms` / `publishInfoSms` of
  `cholewa-commons` 1.13.0), the reminder is Discord only. The alert does not wait for the database (a managed
  one, outside the house): a row it does not take is stored with a later pass. One alert of a low battery an outage,
  none when it was low at the loss already.
- **A restart during an outage keeps its row**: the open outage is read from the database before anything is
  decided, and the reads of the SOLAX close it when the grid is back.
- **The limit**: with the battery empty, or the inverter off, the service is down with the house - an outage is
  told only while the inverter runs. After a start the grid is not known (`null`) until two reads agree, and it is
  not known while the SOLAX is stale (a stored outage stays `lost`); a restart while the database cannot be read
  decides nothing until it can.

**The sources watched** (HAS-332) - the pattern of blinds and light, judged every minute by whole passes:

- **Silent**: the SOLAX dongle when its reader failed and it answered nothing for `energy.availability.solax-silent-after`
  (PT5M); the grid meter when its last read over RPC failed and no message nor read came for
  `energy.availability.meter-silent-after` (PT5M) - the broker alone going down is no silence of the meter, the
  reconciliation still reads it every 5 min. A source never heard since the start counts from the start. One alert a pass
  naming every silent source, a reminder every `energy.availability.reminder-every` (PT1H), an info at its return - only
  once it answers again (HAS-296). An alert that did not get through is tried again with every pass; the return names
  one that never did. The SolaX cloud read is not the dongle; the SOFAR joins with HAS-330.
- **Overload**: a phase current of the grid meter above `energy.grid.phase-current-warn` (22 A; the main fuses are
  3 x 25 A, the board of the inverter 20 A a phase) in every pass for `energy.grid.overload-after` (PT3M) is a
  warning (the level `warn`) naming the phase and the current, once an episode; below for as long, an info ends it.
  Only a reading of the meter not older than `energy.meter.stale-after` counts - a stale one starts the count anew.
- The episodes are kept in `source_alert` (open and closed), so a restart neither repeats an alert nor forgets a
  return; the alert does not wait for the database - a row it does not take is stored with a later pass.

The SOFAR and the SolaX cloud are not known yet. What comes next:

| Task | What |
|---|---|
| HAS-329 | the SolaX Developer Platform |
| HAS-330 | the SOFAR inverter |
| HAS-331 | the balance of the grid and its history |

# Run locally

- Build: `mvn verify` (JDK 21) - no Docker needed until the first test of PostgreSQL-only SQL
  (Testcontainers, the dependencies are in the pom already).
- Ports: local profile `6014` (management `8014`); in the deployed `home` profile the service
  listens on `6200` and Actuator on `8200` like every service in the cluster. The ingress routes
  only 6200, so Actuator is reachable inside the cluster only - where the Kubernetes probes hit
  `/actuator/health/{readiness,liveness}` and Prometheus scrapes `/actuator/prometheus`.
- **The database**: own database `home-automation-energy`, `database.pool.max-size: 2` - this
  service's share of the 47 connections of the managed database (20 allotted with it). The
  connection properties (`database-host`, `database-port`, `database-name`, `database-user`,
  `database-password`) are bound to the `database.*` prefix of `cholewa-commons`; Flyway derives
  its JDBC URL from them (`--flyway-url=...` overrides). **`database-password` is mandatory**: the
  service refuses to start without it (`cholewa.required-properties`). Flyway runs in `home` /
  `local` and is off under `test`. **A local run with the real values is the production database.**
- **The broker**: `/device` as the user `device` (`rabbitmq-password`, mandatory), the queue
  `device.prod.energy` (`device.dev.energy` under `local`, broker on `localhost`). Without a broker the
  service starts all the same and reads the meter over its RPC.
- **The grid meter**: `meter-device-id` (its MQTT id, `shellypro3em-<mac>`) and `meter-address` (where its
  RPC answers) - both mandatory, from the manifest; `energy.meter.stale-after` (PT2M).
- **The inverters**: read from the device registry of `database-service` (`internal.service.database`,
  `http://localhost:6005` under `local`). The SOLAX is asked at the address and with the registration number
  of its dongle from there; `energy.solax.interval` (PT15S, 10 - 60 s), `energy.solax.stale-after` (PT1M),
  `energy.solax.battery-capacity` (26.5 kWh, the usable capacity of the battery).
- **The notifications** (HAS-334): a second connection to the same broker, `/notification` as the user
  `notification` (`notification-rabbitmq-password`, mandatory), the exchange `notification` with `env` `prod`
  (`dev` under `local`). `energy.outage.low-soc` (20 %, 11 - 99) and `energy.outage.reminder-every` (PT1H,
  10 min - 24 h).
- **The sources watched** (HAS-332): `energy.availability.solax-silent-after` and `meter-silent-after` (PT5M, 1 min -
  24 h), `energy.availability.reminder-every` (PT1H, 10 min - 24 h), `energy.grid.phase-current-warn` (22 A, 1 - 63) and
  `energy.grid.overload-after` (PT3M, 1 - 60 min). To try the overload warning locally, lower the threshold below the
  current of the house (`--energy.grid.phase-current-warn=1`).

# API

Under the base path `/home/energy`, routed by the gateway by path, every method.

| Method | Path | Answer |
|---|---|---|
| `GET` | `/home/energy/status` | an object (since HAS-334; a bare list of the sources before): `sources` - every source of energy in the order of the dashboard - `meter`, `solax`, `sofar`, `solaxCloud` - with `reachable`: `true`, `false`, or `null` while the service knows nothing of it (the SOFAR and the cloud until their tasks; the SOLAX is the last read of its dongle), and `lastReadingAt` (house time) once a reading came; the meter is `false` after its last will on the broker. `grid` - `state` `present` or `lost` as the SOLAX sees it, `null` while not known (after a start, before two reads agree), and `since` (house time) once known |
| `GET` | `/home/energy/grid` | the grid meter now: `phases` `a` / `b` / `c` (`voltage` V, `current` A, `activePower` W, `apparentPower` VA, `powerFactor`, `frequency` Hz), `totalActivePower` (W, positive while the house takes from the grid, negative while it returns), `importEnergy` / `exportEnergy` (kWh since the meter was set up), `direction` (`import`, `export`, `idle` within 20 W), `measuredAt` (house time), `stale` (no readings, older than 2 min, or the meter off the broker). What the meter did not send is left out; before the first readings only `{"stale": true}` |
| `GET` | `/home/energy/inverters` | every inverter of the device registry: `id` (`solax`, `sofar`), `registryId`, `name`, `room`, and from its last reading `runMode` (`waiting`, `checking`, `normal`, `fault`, `permanent fault`, `updating`, `eps check`, `eps`, `self test`, `idle`, `standby`), `acPower` (W), `phases` `a` / `b` / `c` (`voltage`, `current`, `activePower`), `pvPower` (W of the panels together), `pv` `pv1` / `pv2` (`voltage`, `current`, `power` of each string; 0 at night), `gridPower` (W the meter of the SOLAX measures at the grid, positive while the house takes from it - the grid itself is `/grid`), `frequency`, `powerFactor`, `temperature` (°C), `dailyYield` / `totalYield` (kWh), `measuredAt`, `stale` (no reading, older than 1 min, or the last read failed). The SOFAR answers its registry entry and `stale` until its task. `503 REGISTRY_UNAVAILABLE` until the registry was read once |
| `GET` | `/home/energy/inverters/{id}` | one inverter by its brand, as above; `404 NOT_FOUND_INVERTER` for another id or a brand not registered |
| `GET` | `/home/energy/battery` | the battery of the SOLAX now: `soc` (%), `health` (SOH %), `power` (W, positive while charging, negative while discharging), `direction` (`charging`, `discharging`, `idle` within 20 W), `voltage`, `current`, `temperature`, `cycles`, `remainingKwh` (the SOC of the usable capacity), `chargedToday` / `dischargedToday` (kWh), `measuredAt`, `stale`. Before the first reading only `{"stale": true}` |
| `GET` | `/home/energy/outages?from=&to=` | the outages of the grid that started in `[from, to)` (house times, ISO `2026-10-01T00:00:00`), the oldest first: `startedAt`, `endedAt` (left out while it lasts), `socAtStart`, `socAtEnd`, `lowestSoc` (%; a value not known left out). `400 INVALID_RANGE` when `from` is not before `to` or they are more than 366 days apart, `400` without either |

# Database

`home-automation-energy`, owned by `doadmin` like every database of the cluster: the user of the
services needs `GRANT USAGE, CREATE ON SCHEMA public` before the first start, or Flyway fails with
`permission denied for schema public`.

- `V1` is an empty baseline - the tables come with the tasks that store something. Its first run
  creates the history table of Flyway, which proves the grant.
- `V2` (HAS-327) `grid_reading`: the grid meter once a minute, a row only when a value changed - the power
  per phase and in total (W, signed), the energies taken and returned (kWh), the voltage per phase. Kept
  until the retention of HAS-331.
- `V3` (HAS-328) `inverter_reading` (an inverter by its registry id: the PV power together and per string, the AC power, the run mode in words, the temperature, the daily yield) and `battery_reading` (SOC,
  power, voltage, current, temperature): once a minute, a row only when a value changed.
- `V4` (HAS-334) `grid_outage`: one row an outage of the grid - its start and end (`ended_at` NULL while it
  lasts, at most one such row: a partial unique index), the state of charge at the start, at the end and the lowest,
  the last notification and the alert of a low battery (so that a restart neither repeats nor forgets them).
- `V5` (HAS-332) `source_alert`: one row an episode of a source - `source` (`solax`, `meter`, `phase a` / `b` / `c`),
  `kind` (`silent` for the first two, `overload` for the phases - checked), since when, the last notification (NULL while
  the alert has not got through) and the end (`closed_at` NULL while it lasts, at most one such row a source: a partial
  unique index).
