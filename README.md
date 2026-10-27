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

**Today it is the scaffold of HAS-325**: the database, the route of the gateway and one endpoint,
the status of the sources - every one of them not known yet. What comes next:

| Task | What |
|---|---|
| HAS-326 | the kind `inverter` in the device registry (`smart-home-sdk`, `database-service`) |
| HAS-327 | the grid meter: the Shelly Pro 3EM over MQTT and RPC |
| HAS-328 | the SOLAX inverter and its battery over the local API of its dongle |
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

# API

Under the base path `/home/energy`, routed by the gateway by path, every method.

| Method | Path | Answer |
|---|---|---|
| `GET` | `/home/energy/status` | every source of energy in the order of the dashboard - `meter`, `solax`, `sofar`, `solaxCloud` - with `reachable`: `true`, `false`, or `null` while the service knows nothing of it (every source until the reading tasks) |

# Database

`home-automation-energy`, owned by `doadmin` like every database of the cluster: the user of the
services needs `GRANT USAGE, CREATE ON SCHEMA public` before the first start, or Flyway fails with
`permission denied for schema public`.

- `V1` is an empty baseline - the tables come with the tasks that store something. Its first run
  creates the history table of Flyway, which proves the grant.
