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

**Today it reads the grid meter and the SOLAX** on top of the scaffold of HAS-325:

- the Shelly Pro 3EM at the grid entry over MQTT and its RPC, a snapshot a minute in `grid_reading`,
  `GET /home/energy/grid` (HAS-327);
- the SOLAX inverter and its battery over the local API of its dongle every 15 s - the inverter from the
  device registry (kind `inverter`, HAS-326), a snapshot a minute in `inverter_reading` and
  `battery_reading`, `GET /home/energy/inverters` and `/battery` (HAS-328); the layout of the answer (type 33, in no
  public library) is established on answers compared with SolaxCloud by night and by day.

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

# API

Under the base path `/home/energy`, routed by the gateway by path, every method.

| Method | Path | Answer |
|---|---|---|
| `GET` | `/home/energy/status` | every source of energy in the order of the dashboard - `meter`, `solax`, `sofar`, `solaxCloud` - with `reachable`: `true`, `false`, or `null` while the service knows nothing of it (the SOFAR and the cloud until their tasks; the SOLAX is the last read of its dongle), and `lastReadingAt` (house time) once a reading came. The meter is `false` after its last will on the broker |
| `GET` | `/home/energy/grid` | the grid meter now: `phases` `a` / `b` / `c` (`voltage` V, `current` A, `activePower` W, `apparentPower` VA, `powerFactor`, `frequency` Hz), `totalActivePower` (W, positive while the house takes from the grid, negative while it returns), `importEnergy` / `exportEnergy` (kWh since the meter was set up), `direction` (`import`, `export`, `idle` within 20 W), `measuredAt` (house time), `stale` (no readings, older than 2 min, or the meter off the broker). What the meter did not send is left out; before the first readings only `{"stale": true}` |
| `GET` | `/home/energy/inverters` | every inverter of the device registry: `id` (`solax`, `sofar`), `registryId`, `name`, `room`, and from its last reading `runMode` (`waiting`, `checking`, `normal`, `fault`, `permanent fault`, `updating`, `eps check`, `eps`, `self test`, `idle`, `standby`), `acPower` (W), `phases` `a` / `b` / `c` (`voltage`, `current`, `activePower`), `pvPower` (W of the panels together), `pv` `pv1` / `pv2` (`voltage`, `current`, `power` of each string; 0 at night), `gridPower` (W the meter of the SOLAX measures at the grid, positive while the house takes from it - the grid itself is `/grid`), `frequency`, `powerFactor`, `temperature` (°C), `dailyYield` / `totalYield` (kWh), `measuredAt`, `stale` (no reading, older than 1 min, or the last read failed). The SOFAR answers its registry entry and `stale` until its task. `503 REGISTRY_UNAVAILABLE` until the registry was read once |
| `GET` | `/home/energy/inverters/{id}` | one inverter by its brand, as above; `404 NOT_FOUND_INVERTER` for another id or a brand not registered |
| `GET` | `/home/energy/battery` | the battery of the SOLAX now: `soc` (%), `health` (SOH %), `power` (W, positive while charging, negative while discharging), `direction` (`charging`, `discharging`, `idle` within 20 W), `voltage`, `current`, `temperature`, `cycles`, `remainingKwh` (the SOC of the usable capacity), `chargedToday` / `dischargedToday` (kWh), `measuredAt`, `stale`. Before the first reading only `{"stale": true}` |

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
