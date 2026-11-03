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

**Today it reads the grid meter and both inverters and watches the grid** on top of the scaffold of HAS-325:

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
  told on Discord, stored in `source_alert`;
- **the SolaX cloud** (HAS-329): the backup of the dongle - the SOLAX and its battery from the SolaX Developer Platform
  every 5 min, served while the dongle is stale - and the alarms of the inverter every 15 min, told on Discord;
- **the SOFAR** (HAS-330): from the Solarman Open API every 5 min (the logger has no local path), a snapshot in
  `inverter_reading`, `GET /home/energy/inverters/sofar` and `sofar` in `/status`; its faults told on Discord;
- **the planned outages of Tauron** (HAS-337): the announcements of Tauron Dystrybucja for the address of the house read
  every hour for 30 days ahead from its public API, stored in `planned_outage`, `GET /home/energy/outages/planned`; a
  planned outage of the street of the house told by SMS, reminded by SMS the day before at 18:00 and 2 h before it starts;
- **the charge of the battery from the grid on demand** (HAS-336): `POST /home/energy/battery/charge` sets the Self Use
  work mode of the SOLAX through the SolaX cloud - the grid allowed up to the target in a window - follows the command,
  ends the run at its target or the end of its window and sets the Self Use the owner runs back; stored in
  `battery_charge_run`, told on Discord.

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
  one that never did. The SolaX cloud read is not the dongle; the SOFAR is watched for its faults only (below).
- **Overload**: a phase current of the grid meter above `energy.grid.phase-current-warn` (22 A; the main fuses are
  3 x 25 A, the board of the inverter 20 A a phase) in every pass for `energy.grid.overload-after` (PT3M) is a
  warning (the level `warn`) naming the phase and the current, once an episode; below for as long, an info ends it.
  Only a reading of the meter not older than `energy.meter.stale-after` counts - a stale one starts the count anew.
- The episodes are kept in `source_alert` (open and closed), so a restart neither repeats an alert nor forgets a
  return; the alert does not wait for the database - a row it does not take is stored with a later pass.

**The SolaX cloud** (HAS-329) - the SolaX Developer Platform with the application of the owner:

- **The token**: OAuth2 client credentials, held in memory, taken anew a day before it expires (30 days) and once when a
  call says it is invalid. Every answer is HTTP 200 with a `code`: 10000 is the success; a failure is logged with its
  code alone, never the body - an invalid-token answer quotes the token back, and Logbook masks it (`LogbookConfig`,
  the default filters of the token and the secret kept).
- **The backup of the dongle**: the inverter and its battery read every `energy.solax.cloud.interval` (PT5M) - the
  battery is asked by the serial of the inverter (`requestSnType=1`; by its own serial the cloud answers "no
  permission"). While the dongle is stale, `/inverters/solax` and `/battery` answer the cloud with `source: cloud` and
  the time of the plant as `measuredAt`, when it is of the same inverter and battery and not older than
  `energy.solax.cloud.stale-after` (PT15M); the run mode, the grid power and the energy charged / discharged today are
  left out there (not established in the cloud). Verified against the dongle on 2026-11-01 within one refresh.
- **The alarms of the inverter** every `energy.solax.cloud.alarm-interval` (PT15M): a new ongoing alarm is an alert
  naming its name, error code, type and level, one the cloud no longer lists an info; kept in `source_alert` (kind
  `alarm`) once told, so a restart neither repeats nor forgets one. The error codes of
  `energy.solax.cloud.ignored-alarm-codes` are never told: 17 "Bat Power Low" comes with the battery at its floor,
  almost every day (owner, 2026-11-01).
- **Prepared for the charge task**: the work mode and the result of a command (`setSelfUse`, `requestStatus`) - no
  write is sent by this service yet; the fields of the body are the charge task's to verify.

**The SOFAR** (HAS-330) - the Solarman Open API with the application Solarman granted the owner (the logger runs its
MQTT firmware, tcp/8899 is closed - there is no local path):

- **The token**: `POST /account/v1.0/token?appId=` with the app secret, the e-mail of the account and the lowercase
  SHA-256 of its password; a personal token is enough. Held in memory, taken anew a day before it expires (60 days) and
  once when a call says it is invalid (code 2101019). Every answer is HTTP 200 with `success`; a failure is logged with
  its code alone. Logbook masks the secret, the account, the password, the tokens, the uid and the appId.
- **The reading**: `device/v1.0/currentData` for the serial of the registry every `energy.sofar.cloud.interval` (PT5M;
  the logger uploads every 5 min) - the panels together and per string, the AC side per phase, the frequency, the state
  of the inverter and its faults **in the words of the cloud**, the temperature of the power module, the production
  today and in total; `measuredAt` is the time the logger collected it. Stale once collected longer ago than
  `energy.sofar.cloud.stale-after` (PT15M) or after a failed read. Verified against the cloud on 2026-11-02.
- **The faults**: decided after every fresh reading - a new fault is an alert naming its words, one the reading no
  longer has an info; kept in `source_alert` (source `sofar`, kind `alarm`) once told. The inverter's own grid counters
  are not read - the balance of the house is the Pro 3EM's.

**The balance, the history and the days** (HAS-331):

- **The sign convention of the house** (pinned by `MetricTest`, `FlowsServiceTest`): every power is W; the grid is
  positive while the house takes from it, negative while it returns; the battery is positive while it charges,
  negative while it discharges; the PV powers are never negative. House load = PV of both inverters + grid - battery.
- **Now** (`/flows`): from what `/grid`, `/inverters` and `/battery` answer - an input is known when it has a value and is
  not stale, and what depends on an input not known is left out, never 0. The day so far: the daily counters of the
  inverters and the battery, the counters of the grid meter now minus at midnight (read from `grid_reading`, held for the
  day from five minutes after midnight).
- **The history** (`/history`): the rules of the hot-water and weather history (`HistoryRange`, a copy - the fourth, by
  the owner's choice) - buckets of 5 min, 30 min or 2 h by the length of the range, at most 31 days, avg/min/max per
  bucket. A row is stored only after a change: a bucket without a point is a value that did not change. The sums
  (`pv-power`, `house-load`) carry each part from its last reading and have the average only. A sum is one query of all its parts;
  one query at a time (the pool has 2), a month in about 0.15 s on the rehearsal.
- **The days** (`energy_day`, V8): closed from 00:15 of the next day (a pass every hour, the days a downtime missed after
  it, at most 31 days back) and never changed - the production of each inverter and the battery energies from their daily
  counters (the highest value after 00:30 - the inverter resets them by its own clock), imported / exported from the
  counters of the grid meter at both midnights (not known on the first day of the service), the house load,
  self-consumption, grid share, and the cross-check of the SolaX cloud (the yield of the SOLAX, the energy of its meter)
  when it was read that day - in memory, lost by a restart.
- **The settlement** (`/months`): net-metering, the Polish "system opustów", never money - an exported kWh credited at
  `energy.settlement.ratio` (0.7), the credit of a month usable in it and the eleven after, the imports drawn from the
  oldest credit first, the rest bought; by whole months from the first closed day - the house's estimate, not the
  invoice. The billing year starts in `energy.settlement.period-start` (September).
- **The retention**: every night at 03:20 the readings older than `energy.history.retention` (730 days) are deleted in
  batches of 5000, never the latest row of a table or an inverter; the days are kept forever.

**The inverter that does not produce in the sun** (HAS-333): every minute the sun above the horizon (sunrise to sunset
at the house - the civil dawn and dusk outside), the illuminance of the weather station (`GET /home/weather/current` of
weather-service - the station has no solar radiation, the illuminance is its one metric of the sun) and both inverters as
`/inverters` answers them. The sun shines at `energy.production.illuminance-threshold` (20 000 lx) or above; an inverter
giving less than `energy.production.min-power` (100 W) while it does, for `energy.production.silence` (PT15M), is an
alert naming it, the power and the illuminance - a reminder every hour while still low in the sun, an info when it
produces again; kept in `source_alert` (kind `production`, V9). Never judged: a station not known or stale, a stale
inverter (the availability alert covers it), the SOLAX in EPS (the outage alert covers it).

# Run locally

- Build: `mvn verify` (JDK 21) - **needs a Docker** since HAS-331: `HistoryRepositoryTest` runs the PostgreSQL-only SQL
  of the history, the days and the retention in Testcontainers (it fails without a Docker, it is not skipped).
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
- **The SolaX cloud** (HAS-329): `solax-client-id` and `solax-client-secret` (the application of the owner, the secret
  `energy`) and `solax-plant-id` (the plant, from the manifest) - mandatory; `energy.solax.cloud.base-url`
  (`https://openapi-eu.solaxcloud.com`), `interval` (PT5M), `alarm-interval` (PT15M), `stale-after` (PT15M), each 5 - 60
  min. The battery serial is the registry's (`batterySerialNumber` of the inverter binding).
- **The SOFAR** (HAS-330): `solarman-app-id`, `solarman-app-secret`, `solarman-email` and `solarman-password-sha256` (the
  lowercase SHA-256 of the password of the account; the secret `energy`) - mandatory; `energy.sofar.cloud.base-url`
  (`https://globalapi.solarmanpv.com`), `interval` (PT5M, 1 - 60 min), `stale-after` (PT15M, 5 - 60 min). The serial
  of the inverter is the registry's (`serialNumber` of the inverter binding, brand `sofar`).
- **The history and the days** (HAS-331): `energy.history.retention` (730d; a bare number is days, 31 - 3650 days),
  `energy.settlement.ratio` (0.7, above 0 to 1) and `energy.settlement.period-start` (9, 1 - 12).
- **The production watch** (HAS-333): `location-latitude` / `location-longitude` (the house, from the manifest - mandatory,
  the values of blinds-service and light-service), `internal.service.weather` (`http://localhost:6010` under `local`),
  `energy.production.silence` (PT15M, 5 min - 2 h), `illuminance-threshold` (20 000 lx), `min-power` (100 W),
  `reminder-every` (PT1H).
- **The charge of the battery** (HAS-336): `energy.charge.power-kw` (5, 1 - 10 - the estimate of the window), `margin`
  (PT1H, up to 3 h), `longest` (PT23H, the longest window - the windows of the inverter are times of a day),
  `delivery-timeout` (PT2M), `charging-above-watts` (1000 - a charge counts as carried out once the battery charges and the grid imports at this power: the SolaX cloud refuses the result of a command), `baseline` - the Self Use set back after a run: `min-soc` 10, `charge-from-grid` true,
  `charge-upper-soc` 10, `discharge-start` 00:00, `discharge-end` 23:59 (the settings of the owner, read in SolaxCloud on
  2026-11-03). The commands use the credentials of the SolaX cloud above.
- **The planned outages of Tauron** (HAS-337): `tauron-city`, `tauron-street` and `tauron-house-no` - the address of the
  house as the register of Tauron names it, the street without "ul." (from the manifest - mandatory, a private fact);
  `energy.tauron.base-url` (`https://www.tauron-dystrybucja.pl`), `interval` (PT1H, 15 min - 24 h), `ahead-days` (30,
  1 - 60), `alert-after` (PT24H, 1 h - 7 days), `reminder-at` (18:00), `remind-before` (PT2H, 30 min - 6 h).

# API

Under the base path `/home/energy`, routed by the gateway by path, every method.

| Method | Path | Answer |
|---|---|---|
| `GET` | `/home/energy/status` | an object (since HAS-334; a bare list of the sources before): `sources` - every source of energy in the order of the dashboard - `meter`, `solax`, `sofar`, `solaxCloud` - with `reachable`: `true`, `false`, or `null` while the service knows nothing of it (the SOLAX is the last read of its dongle, `solaxCloud` the last read of the SolaX cloud - HAS-329, `sofar` whether the last read of the Solarman cloud brought a reading collected within 15 min, its `lastReadingAt` the time the logger collected it - HAS-330), and `lastReadingAt` (house time) once a reading came; the meter is `false` after its last will on the broker. `grid` - `state` `present` or `lost` as the SOLAX sees it, `null` while not known (after a start, before two reads agree), and `since` (house time) once known |
| `GET` | `/home/energy/grid` | the grid meter now: `phases` `a` / `b` / `c` (`voltage` V, `current` A, `activePower` W, `apparentPower` VA, `powerFactor`, `frequency` Hz), `totalActivePower` (W, positive while the house takes from the grid, negative while it returns), `importEnergy` / `exportEnergy` (kWh since the meter was set up), `direction` (`import`, `export`, `idle` within 20 W), `measuredAt` (house time), `stale` (no readings, older than 2 min, or the meter off the broker). What the meter did not send is left out; before the first readings only `{"stale": true}` |
| `GET` | `/home/energy/inverters` | every inverter of the device registry: `id` (`solax`, `sofar`), `registryId`, `name`, `room`, and from its last reading `runMode` (the SOLAX: `waiting`, `checking`, `normal`, `fault`, `permanent fault`, `updating`, `eps check`, `eps`, `self test`, `idle`, `standby`), `state` and `faults` (the SOFAR: its state and its faults in the words of the Solarman cloud - `Grid connected`, `[]` without a fault), `acPower` (W), `phases` `a` / `b` / `c` (`voltage`, `current`, `activePower`), `pvPower` (W of the panels together), `pv` `pv1` / `pv2` (`voltage`, `current`, `power` of each string; 0 at night), `gridPower` (W the meter of the SOLAX measures at the grid, positive while the house takes from it - the grid itself is `/grid`), `frequency`, `powerFactor`, `temperature` (°C), `dailyYield` / `totalYield` (kWh), `measuredAt`, `stale` (no reading, older than 1 min, or the last read failed), `source` (`local` - the dongle, or `cloud` - the SolaX cloud while the dongle is stale, without `runMode` and `gridPower`; `solarman-cloud` for the SOFAR, `measuredAt` the time its logger collected the reading, stale after 15 min, without `gridPower` and `powerFactor`; left out without a reading). An inverter without a reading answers its registry entry and `stale`. `503 REGISTRY_UNAVAILABLE` until the registry was read once |
| `GET` | `/home/energy/inverters/{id}` | one inverter by its brand, as above; `404 NOT_FOUND_INVERTER` for another id or a brand not registered |
| `GET` | `/home/energy/battery` | the battery of the SOLAX now: `soc` (%), `health` (SOH %), `power` (W, positive while charging, negative while discharging), `direction` (`charging`, `discharging`, `idle` within 20 W), `voltage`, `current`, `temperature`, `cycles`, `remainingKwh` (the SOC of the usable capacity), `chargedToday` / `dischargedToday` (kWh), `measuredAt`, `stale`, `source` (`local` or `cloud` as for the inverters; the cloud without `chargedToday` / `dischargedToday`). Before the first reading only `{"stale": true}` |
| `GET` | `/home/energy/outages?from=&to=` | the outages of the grid that started in `[from, to)` (house times, ISO `2026-10-01T00:00:00`), the oldest first: `startedAt`, `endedAt` (left out while it lasts), `socAtStart`, `socAtEnd`, `lowestSoc` (%; a value not known left out). `400 INVALID_RANGE` when `from` is not before `to` or they are more than 366 days apart, `400` without either |
| `GET` | `/home/energy/outages/planned` | the outages Tauron announces for the house that have not ended, the soonest first (HAS-337): `startsAt`, `endsAt` (house times), `kind` (`planned`, `unplanned` - a failure), `scope` (`street` - the announcement names the street of the house, `town` - the town alone), `description` (the words of Tauron, which streets), `firstSeenAt`, `notifiedAt` (the last notice, left out until one was published), `skipAutomation`; `[]` without one |
| `GET` | `/home/energy/alerts` | the alarms that last, newest first (HAS-341, the open alarms of the dashboard): `source` (`solax`, `sofar`, `meter`, `phase a` / `b` / `c`), `kind` (`silent`, `overload`, `alarm`, `production`), `openedAt` (house time), `detail` - the name of an alarm as the inverter words it (a SOLAX alarm without its code and start), left out for the other kinds; `[]` without one |
| `POST` | `/home/energy/battery/charge?source=` | a charge of the battery from the grid (HAS-336), body JSON `{"targetSoc": 10-100, "until": "<house time>"}`, both optional (`{}` - to 100 % within the estimate: the SOC to the target at 5 kW plus an hour, at most 23 h); `source` `dashboard` (default) or `automation`. `202` with the run as requested - the command is followed every 10 s. `400 INVALID_CHARGE`, `409 CHARGE_IN_PROGRESS` (a run open or being set back), `409 TARGET_REACHED`, `429 WRITE_TOO_SOON` (at most one write a minute), `503 INVERTER_UNAVAILABLE` (the SOC not known), `503 REGISTRY_UNAVAILABLE`, `404 NOT_FOUND_INVERTER`, `502 DEVICE_FAILED` (the cloud refused the command) |
| `DELETE` | `/home/energy/battery/charge` | the charge going on ended and the Self Use of the owner set back; `202` with the run (`ending` while the cloud refused the baseline - tried again every minute); `404 NOT_FOUND_CHARGE`, `429 WRITE_TOO_SOON` |
| `GET` | `/home/energy/battery/charge` | `active` (always written), `run` - the charge going on or being set back, `soc` and `power` of the battery now, `minutesLeft` to the target; `{"active": false}` without one. A run: `id`, `state` (`requested`, `charging`, `ending`, `ended`, `failed`), `source`, `requestedAt`, `targetSoc`, `windowStart`, `windowEnd`, `deliveryStatus` (1 offline, 2 issuance failed, 3 issued, 4 executing, 5 failed, 6 timeout), `deliveredAt`, `socAtStart`, `socAtEnd`, `endedAt`, `endedBy` (`target`, `window`, `cancel`, `failed`), `failure` (`DEVICE_FAILED`, `INVERTER_UNAVAILABLE`), `restoredAt`; what is not known is left out |
| `GET` | `/home/energy/battery/charge/runs?from=&to=` | the charges requested in `[from, to)` (house times, at most 366 days), the oldest first, as above; `400 INVALID_RANGE` |
| `GET` | `/home/energy/flows` | the balance now (HAS-331): `production`, `solaxPv`, `sofarPv`, `gridPower`, `batteryPower`, `houseLoad` (W, the sign convention above); `today` - `production`, `imported`, `exported`, `charged`, `discharged`, `houseLoad` (kWh), `selfConsumption`, `gridShare` (0..1); `inputs` - `grid`, `solax`, `sofar`, `battery`, each with `measuredAt` and `stale`. What depends on an input not known is left out |
| `GET` | `/home/energy/history?metric=&from=&to=` | one metric in `[from, to)` (house times, at most 31 days) in buckets: `metric`, `from`, `to`, `bucketSeconds` (300, 1800 or 7200 by the length), `points` - `at`, `avg`, `min`, `max` (a sum the average only). Metrics: `grid-power`, `grid-import-energy`, `grid-export-energy` (the counters, kWh - max - min of a bucket is its energy), `pv-solax`, `pv-sofar`, `battery-power`, `battery-soc`, `pv-power`, `house-load`. `400 UNKNOWN_METRIC` naming the metrics there are, `400 INVALID_RANGE`, `503 REGISTRY_UNAVAILABLE` for a metric of an inverter before the registry was read |
| `GET` | `/home/energy/days?from=&to=` | the closed days of `[from, to]` (ISO dates, at most 366 days): `day`, `solaxYield`, `sofarYield`, `production`, `imported`, `exported`, `charged`, `discharged`, `houseLoad` (kWh), `selfConsumption`, `gridShare` (0..1), `cloudYield`, `cloudImported`, `cloudExported` (the SolaX cloud); a value not established left out. `400 INVALID_RANGE` |
| `GET` | `/home/energy/months?year=` | the billing year from `period-start` of `year` (2026: September 2026 - August 2027): `year`, `from`, `to` (`yyyy-MM`), `ratio`, `months` - `month`, `days` (closed), `production`, `imported`, `exported`, `houseLoad` (left out without a day), `credited`, `drawn`, `bought`, `expired`, `stored` (kWh) |

# Database

`home-automation-energy`, owned by `doadmin` like every database of the cluster: the user of the
services needs `GRANT USAGE, CREATE ON SCHEMA public` before the first start, or Flyway fails with
`permission denied for schema public`.

- `V1` is an empty baseline - the tables come with the tasks that store something. Its first run
  creates the history table of Flyway, which proves the grant.
- `V2` (HAS-327) `grid_reading`: the grid meter once a minute, a row only when a value changed - the power
  per phase and in total (W, signed), the energies taken and returned (kWh), the voltage per phase. Kept
  730 days (the retention of HAS-331).
- `V3` (HAS-328) `inverter_reading` (an inverter by its registry id: the PV power together and per string, the AC power, the run mode in words, the temperature, the daily yield) and `battery_reading` (SOC,
  power, voltage, current, temperature): once a minute, a row only when a value changed.
- `V4` (HAS-334) `grid_outage`: one row an outage of the grid - its start and end (`ended_at` NULL while it
  lasts, at most one such row: a partial unique index), the state of charge at the start, at the end and the lowest,
  the last notification and the alert of a low battery (so that a restart neither repeats nor forgets them).
- `V5` (HAS-332) `source_alert`: one row an episode of a source - `source` (`solax`, `meter`, `phase a` / `b` / `c`),
  `kind` (`silent` for the first two, `overload` for the phases - checked), since when, the last notification (NULL while
  the alert has not got through) and the end (`closed_at` NULL while it lasts, at most one such row a source: a partial
  unique index).
- `V6` (HAS-329) `source_alert.detail`: the alarms of the SOLAX in the same table - kind `alarm`, source `solax`, `detail`
  `<error code>|<start time>|<name>` (required for an alarm, null for the other kinds - checked); at most one open row a
  source, kind and detail.
- `V7` (HAS-330) the faults of the SOFAR in the same table - source `sofar`, kind `alarm`, `detail` the words of the
  fault; both checks take the new source (the SOFAR is never `silent`).
- `V8` (HAS-331) `battery_reading.charged_today` / `discharged_today` (the daily counters of the SOLAX battery) and
  `energy_day`: one row a closed day, keyed by the date - the totals of the day in kWh, the self-consumption and the grid
  share, the cross-check of the SolaX cloud and the time it was closed; kept forever. The readings of V2 and V3 are kept
  730 days (`energy.history.retention`).
- `V9` (HAS-333) the inverter that does not produce in the sun in `source_alert` - kind `production`, source `solax` or
  `sofar`, no detail; at most one open row an inverter.
- `V10` (HAS-337) `planned_outage`: one row an outage Tauron announced for the house - its id at Tauron and its start
  (the days of one work share the id), the end, `kind` (`planned`, `unplanned`), `scope` (`street`, `town`), the words,
  when it was first and last seen, the notice still to be told (`new`, `changed`, `moved`, `cancelled`), the start before
  a move, the last notice and the two reminders, `closed_at` / `closed_as` (`ended`, `cancelled`, `moved`) once no longer
  announced and `skip_automation`; at most one open row an id and a start (a partial unique index).
- `V11` (HAS-336) `battery_charge_run`: one row a charge of the battery - when and by whom (`dashboard`, `automation`)
  it was asked, the target SOC and the window, the command and its last status, when the inverter took it, the SOC at the
  start and at the end, the end (`target`, `window`, `cancel`, `failed`) and its failure, the command that set the
  baseline back; at most one run without its end (a partial unique index).
