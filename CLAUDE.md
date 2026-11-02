# energy-service

The energy of the house (epic HAS-324): the grid meter, the SOLAX and SOFAR inverters, the SOLAX
battery and the SolaX cloud - readings, history, the balance of the grid, alarms, and charging the
battery before a planned outage. Scaffolded by HAS-325; the features come with HAS-326 onwards.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The user writes the code in this
repository themselves; Claude's default role here is analysis, code review and security
review - the owner had Claude implement the scaffold (HAS-325), the grid meter (HAS-327), the
SOLAX (HAS-328), the outages of the grid (HAS-334), the monitor of the sources (HAS-332), the SolaX cloud (HAS-329),
the SOFAR (HAS-330) and the balance, history and days (HAS-331), and reviews them in IntelliJ.

## Role in the system

- Is called by: the dashboard through `api-gateway-service` (`/home/energy`, `/home/energy/**`,
  every method - the "Energia" panel of HAS-341).
- Consumes: `device.prod.energy` on `/device` (user `device`) - every device event, of which only the grid
  meter is read (HAS-327).
- Calls: the Shelly Pro 3EM over its RPC (`EM.GetStatus`, `EMData.GetStatus`, HAS-327); the dongle of the
  SOLAX over its local API and `database-service` for the inverters of the device registry
  (`GET /home/device/registry?kind=inverter&enabled=true`, HAS-328). The SolaX Developer Platform (HAS-329,
  `openapi-eu.solaxcloud.com`). The Solarman Open API for the SOFAR (HAS-330, `globalapi.solarmanpv.com`).
- Publishes: the alerts, reminders and infos of the outages of the grid to the exchange `notification` of
  `/notification` (user `notification`, `notification-rabbitmq-password`; HAS-334) - the loss, a low battery and
  the return marked for an SMS; the silence of the SOLAX dongle and the grid meter and the overload of a phase
  (HAS-332), Discord only; the alarms of the SOLAX from the SolaX cloud (HAS-329); the faults of the SOFAR (HAS-330).
- Uses libraries: `cholewa-commons` 1.13.0 (the pooled `ConnectionFactory`, the required
  properties, the scheduler pool and `ScheduledPass`, the error contract, `NotificationConnection` and the
  `...Sms` methods of `NotificationPublisher` - HAS-334), `smart-home-sdk` 1.15.0 (the kind
  `inverter` and `InverterBinding`, HAS-326),
  `shelly-client` 2.3.0 **with its beans on** (`ShellyRpcClient`, `ShellyGen1Client`,
  `ShellyMqttDecoder`; pinned by `EnergyServiceApplicationTest`) - 2.3.0 brought `EmStatus`, `EmDataStatus`
  and their RPC reads for HAS-327.
- Database: own database `home-automation-energy`, pool 2 - with it 20 of the 47 connections of the
  managed database are allotted to this project (23 with ristretto).

## Build & run

- Build + tests: `mvn verify` - **needs a Docker** since HAS-331 (`HistoryRepositoryTest`, Testcontainers PostgreSQL 17:
  the SQL of the history, the days and the retention; it fails without a Docker, it is not skipped).
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
- **`GET /status` is an object since HAS-334** (`sources` and `grid`; a bare list of the sources before - Bruno
  was its only reader). **It answers a source not known with `reachable: null`** - every source after a start
  until its first reading or read - written out as `null`, never left out or
  `false`; `lastReadingAt` is left out while there is none. `EnergySource` holds the order and the
  wire names (`solaxCloud`), the contract of the dashboard.
- **Scheduling is off under `test`** (`SchedulingConfig`, `@Profile("!test")`); every reactive
  `@Scheduled` method is wrapped in `ScheduledPass.bounded`, its limit below its delay and pinned by a
  test that hangs the pass (the reconciliation 1 min, the snapshot 50 s); the scheduler has the pool of
  10 of the library.
  The outage watch: every 15 s, bounded at 14 s. The monitor of the sources: every minute, bounded at 50 s.
- **The broker**: one connection, the auto-configured one, to `/device` (user `device`, `rabbitmq-password`),
  the listener with manual ack, prefetch 3 and observation (`EnergyServiceApplicationTest`). The notifications
  go over a second connection that is **not a bean** (`NotificationConnection` of `cholewa-commons`, built by
  `NotificationRabbitConfig` - since HAS-334): a `ConnectionFactory` bean would switch off the auto-configured one
  with its listener settings. `NotificationRabbitConfig` / `NotificationConnectionProperties` are the sixth copy
  (heating, blinds, weather, light, amx) - kept as copies by the owner's choice; a change goes into every copy.
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
  - The API: `GET /inverters` (every inverter of the registry; one without a reading with its registry entry and `stale` -
    the SOFAR since HAS-330 below), `GET /inverters/{solax|sofar}` (`404 NOT_FOUND_INVERTER`), `GET /battery` (`remainingKwh` = SOC of
    `energy.solax.battery-capacity`, 26.5 kWh, the usable capacity SolaxCloud gives; `direction` by
    `BatteryDirection.DEAD_BAND_WATTS`, 20 W), `stale` after `energy.solax.stale-after` (PT1M) or a failed read;
    `GET /status` - `solax.reachable` is the last read. A value the map does not establish is left out.
- **The outages of the grid** (HAS-334, package `outage`) - what is easy to break:
  - **Only the SOLAX knows the grid** (owner, 2026-10-23): the Pro 3EM goes dark with an empty battery or the
    inverter off, the cluster runs in the house; the cloud read has no `deviceStatus`. `GridSignal` per reading:
    **LOST** - run mode 6 / 7, or all three voltages at the grid connection point (Data[167-169]) below 100 V;
    **PRESENT** - not in EPS and all three at 100 V or more, **in any run mode** (the task said "run mode 2": the
    SOLAX answered **run mode 21**, outside its list, with the battery empty while the house took from the grid -
    sample 2026-10-29, so the return would never be seen then); **NOT_KNOWN** - one or two phases down or a voltage
    missing, the state holds.
  - `OutageRules` decides without I/O (`OutageRulesTest`): a reading counts once (its house time), **two in a
    row decide**, the outage starts / ends at the first of them; the lowest SOC kept; below
    `energy.outage.low-soc` (20) one alert more, once an outage; a reminder every `energy.outage.reminder-every`
    (PT1H) from the last notification. The words are `OutageMessages` - English, one GSM SMS with "ERROR: " in
    front (pinned); the time left is the energy above the 10 % EPS floor at the power the battery gives now, left
    out while it does not discharge.
  - `GridOutageWatch` (every 15 s, bounded at 14 s) runs them: **nothing is decided before the open outage is read
    from the database** (a failed read tried again next pass) - a restart during an outage keeps its row and the
    SOLAX closes it; a restart while the database cannot be read decides nothing until it can. Then **memory first**
    (the /code-review of HAS-334: the database is managed, outside the house, and must not hold an alert back): the
    decision is held at once, the notifications sent, then the row stored - one the database does not take stays in
    `GridWatch.unsaved` and is tried with every pass, the newest row of an outage replacing an older one (it holds
    everything), the open outage taking its id once stored. Each notification is cut off after 6 s (two fit in the
    bound) and swallowed with its text at ERROR - stored as reminded all the same, the next reminder an hour later.
    A low battery already at the loss gets no second SMS (the alert of the loss tells the SOC). `/status` answers the
    grid `null` while the SOLAX is stale (an open outage stays `lost`). An answer of the dongle shorter than 170
    words reads as before, without the grid point (`gridPoint` null - not known). Loss, low battery, return:
    `publishAlertSms` / `publishInfoSms`; the reminder `publishReminder` (no SMS).
  - `grid_outage` (V4) holds at most one open row (partial unique index `grid_outage_one_open`, checked on the
    rehearsal); `last_reminded_at` and `low_soc_alerted_at` are there so that a restart neither repeats nor forgets.
  - **The limit**: with the battery empty or the inverter off the service is down with the house - an outage is
    told only while the inverter runs; an outage that began and ended while the cluster was down is not stored.
    After a start the grid is `null` until two reads agree, never read as present.
  - **Rehearsed** (2026-10-31): the released 0.3.0 image applied V1-V3 on a throwaway PostgreSQL with SSL (a user
    with `USAGE, CREATE` on `public` only), the new jar V4; a second open row refused by the index; a restart with
    an open row logged "still open", answered `grid: lost` and the row in `/outages`, the due reminder failing on
    the missing broker without stopping the pass.
- **The monitor of the sources** (HAS-332, package `monitor`) - what is easy to break:
  - **Silence is judged by whole passes and kind of call, never by time alone** (HAS-109): the SOLAX is out only while
    its reader fails (`reachable: false`) and nothing came for `solax-silent-after`; the meter only while its last
    `EM.GetStatus` failed (`GridMeterState.rpcReachable`, set by `MeterReconciliation` - **the readings call, not the
    counters**) and nothing came on MQTT nor RPC for `meter-silent-after`. The broker down alone is no silence: the
    reconciliation reads the meter every 5 min - which also means a silent meter is seen 5 to 10 min late.
  - A source never heard since the start counts from the start (`startedAt` of the monitor); one only not known
    (`reachable` / `rpcReachable` null) is neither out nor back. **Back** is a reading after the episode opened - an
    episode opens at the time the source was last heard (HAS-296: never "back" on a restart only).
  - `SourceRules` decides without I/O (`SourceRulesTest`): one alert a pass naming every silent source, a reminder every
    `reminder-every` from the last notice, returns first; the overload per phase - above in **every** pass for
    `overload-after` (a pass below or a stale reading counts anew), one warning an episode (`publishReminder`, level
    `warn`), below for as long an info; the episode ends at the first pass below. **The opening is held at once, what a
    notice changes (told, closed) only once it was published** - so an alert that failed is tried again with every pass,
    and the return of one that never got through says so (`last_told_at` NULL).
  - `SourceMonitor` runs it **memory first** as `GridOutageWatch`: nothing before the open episodes were read
    (`findByClosedAtIsNull`, tried again with every pass), then the decision held, the notices sent (each cut off after
    6 s; six of them fit the bound), then every unsaved row stored - one the database does not take stays in
    `SourceWatch.unsaved`, one an episode (the newest), and the open one takes its id once stored.
  - `source_alert` (V5): `source` and `kind` checked together (silent: solax, meter; overload: phase a / b / c), at most one
    open row a source (partial unique index `source_alert_one_open`). **A new source is a migration
    of both checks** (the SOFAR, V7 - its faults as `alarm` rows, not a constant of `Source`: it is never `silent`); an open row of a source this version does not know is logged and left.
  - **Rehearsed** (2026-11-01): the released 0.4.0 image applied V1-V4 on a throwaway PostgreSQL with SSL (a user with
    `USAGE, CREATE` on `public` only), the new jar V5; with no meter and no broker (`meter-silent-after` 1 min) the
    meter episode was stored with `last_told_at` NULL and the alert tried every minute; a second open row and
    `phase a` / `silent` refused; a restart logged "still open" and kept the row.
- **The SolaX cloud** (HAS-329, package `solaxcloud`) - what is easy to break:
  - **Every answer is HTTP 200 with a `code`**: 10000 success, 10400 bad credentials, 10402 an invalid token (the
    message quotes the token back), 10500 "no permission" (a battery asked by its own serial). The token answer is
    `{code, result: {access_token, expires_in}}` - judged by the token being there, not by the code. A failure is a
    `SolaxCloudException` with the code alone - **never the body or the message of the cloud**.
  - **Logbook**: `LogbookConfig.maskingBodyFilter` replaces the body filter of Logbook (its bean steps back), so it
    merges `BodyFilters.defaultValue()` (access_token, client_secret) with the mask of the quoted token and, since
    HAS-330, the JSON fields of Solarman (`appSecret`, `email`, `password`, `access_token`, `refresh_token`, `uid` - a
    number, so `replacePrimitiveJsonProperty`, not the string-only filter); `solarmanQueryFilter` merges the default query
    filter with the `appId` of the token request. The Authorization header is masked by the default header filter. A new
    bean of `BodyFilter` or `QueryFilter` must keep the default. Checked on the rehearsal of HAS-330: no secret, account,
    hash or appId in the log of a real token request.
  - **The token** is held in an `AtomicReference`, taken anew a day before it expires and once after 10402 / HTTP 401
    (a second refusal is a failure, not a loop); "bearer" with a lowercase b.
  - **The battery is asked by the serial of the inverter** with `deviceType=2&requestSnType=1`; the answer's `deviceSn`
    is the battery's, checked against `batterySerialNumber` of the registry before it is served.
  - `SolaxCloudReader` (every interval, bounded at 50 s): the inverter, then its battery; a failure keeps the last
    answers, one WARN an hour (`FailureLog`, public since). The services answer the cloud only while the dongle is stale
    and the cloud answer fresh (`stale-after` by `plantLocalTime`); `gridPower` of the cloud has no established sign
    and `deviceStatus` came back null - neither is read. The plant's `dailyYield` is the inverter's `dailyACOutput`, not
    its `dailyYield` (2.5 against 4.0 kWh on 2026-11-01) - for HAS-331.
  - `SolaxAlarmWatch` (every alarm interval, bounded at 1 min): the first page of the ongoing alarms (more is a WARN)
    against the open `alarm` rows; **the table follows the broker** (light-service): a row only once its notice was
    published. `SourceMonitor` reads only `silent` and `overload` rows (`KINDS`). The codes of `ignored-alarm-codes`
    (17 "Bat Power Low", owner 2026-11-01: the battery at its floor, almost daily) are left out before the decision.
  - **The writes are prepared, not sent**: `setSelfUse(JsonNode)` and `requestStatus` - the body is the caller's; the
    documentation of the platform is behind its login, so the charge task verifies the fields with the owner first.
  - **Rehearsed** (2026-11-01): the released 0.5.0 image applied V1-V5 with an open meter row, the new jar V6; two alarms
    open at once, the same one twice refused, an alarm without detail refused; the monitor took up only the meter row.
- **The SOFAR** (HAS-330, package `sofar`) - what is easy to break:
  - **The owner's decision (2026-11-02)**: the Solarman Open API, granted by Solarman; the RS-485 gateway of the task's
    variant B is not needed. The logger runs its MQTT firmware (tcp/8899 closed) - there is no local path.
  - `SolarmanClient`: the token by `POST /account/v1.0/token?appId=&language=en` with `{appSecret, email, password}` - the
    password as its **lowercase SHA-256** (`energy.sofar.cloud.password-sha256`, validated as 64 hex digits); a personal
    token of the account reads the plant (no organisation token). `expires_in` is **a string of seconds** ("5183999", 60
    days). Every answer is HTTP 200 with `success`; `2101019` an invalid token (taken anew once, as the SolaX cloud),
    `2101025` refused credentials, `2101016` a device without records - a `SolarmanException` with the code alone.
  - `SofarReading.of` maps `dataList` by `key` (pinned by `SofarReadingTest` on `src/test/resources/solarman/`, the answer
    of 2026-11-02 10:19 with an invented serial): `PVTP` the panels, `DP1`/`DV1`/`DC1` and `DP2`/... the strings,
    `T_AC_OP` the AC side, `AP1-3`/`AV1-3`/`AC1-3` the phases, `A_Fo1`, `INV_ST1` the state **in words** ("Grid connected"),
    `Sta_1`-`Sta_9` the faults **in words**, empty when none - not bits, so the alert names the words; `T_MDU1` the power
    module °C, `Etdy_ge1` / `Et_ge0` kWh. `collectionTime` (epoch s) is `measuredAt`. The inverter's own grid counters
    (`PG_Pt1`, `t_gc1`, `Et_pu1`) are its output side, not the house - not read. An answer of another `deviceSn` is a failed
    read.
  - `SofarReader` every `energy.sofar.cloud.interval` (PT5M, 1 - 60 min, bounded at 50 s): **reachable only for a fresh
    reading** - collected within `stale-after` (PT15M) - since the cloud keeps answering the last reading of a logger that is
    off; a failed read, a read cut off by the bound (only before its reading - `flatMap` cancels its source after it too,
    hence the flag) and a SOFAR gone from a registry that was read mark it unreachable, the last reading kept.
  - **The faults are decided inside the read** (`SofarFaultWatch.check`, not scheduled - the /code-review of HAS-330: a
    watch of its own could miss a fault of one reading): only a fresh reading decides; rows `sofar` / `alarm` with the words
    as `detail` (cut to 200), the table follows the broker as `SolaxAlarmWatch`. `SolaxAlarmWatch` reads its rows by source
    and kind since (`findByClosedAtIsNullAndSourceAndKind`), so the two never close each other's rows.
  - `V7` adds `sofar` to both checks of `source_alert` (`alarm` only - the SOFAR is never `silent`). **No rollback below
    0.7.0 while a `sofar` row is open**: the `SolaxAlarmWatch` of 0.6.0 reads every open `alarm` row and would close it as
    a SOLAX alarm ("SOLAX alarm over") - close the sofar rows by hand first.
  - `SofarSnapshot` writes `inverter_reading` (inverter 135) a minute when a value changed, at the time of collection;
    `run_mode` NULL - the state of the SOFAR is not one of the run modes of the SOLAX.
  - The API: `state`, `faults` (an empty list without a fault) and `source: solarman-cloud`; no `runMode`, `gridPower`,
    `powerFactor`. `/status`: `sofar.reachable` as above, `lastReadingAt` the time of collection.
  - **Rehearsed** (2026-11-02): the released 0.6.0 image applied V1-V6 on a throwaway PostgreSQL with SSL (a user with
    `USAGE, CREATE` on `public` only), the new jar V7 with the real Solarman cloud and the registry through a port-forward:
    `/inverters/sofar` answered the reading of 10:34 (PV 300 W, grid connected, no fault), `/status` `sofar` reachable; a
    `sofar` / `silent` row refused; an open `sofar` fault row left alone by the SOLAX alarm watch and announced as over by
    the fault watch at the next fresh reading (kept open while the broker was missing); the SOFAR row of `inverter_reading`
    written with `run_mode` NULL.
- **The balance, the history and the days** (HAS-331, packages `flows`, `history`, `days`) - what is easy to break:
  - **The sign convention** is pinned by `MetricTest` and `FlowsServiceTest`: the grid positive while the house takes, the
    battery positive while it charges, house load = PV + grid - battery. The stored rows keep the signs of V2 / V3.
  - **The pool has 2 connections**: every query of a request or a pass runs one after the other (`Flux.concat`,
    `zipWhen` - never `Mono.zip` of two queries; the service-review of HAS-331), the history one range at a time
    (`OneAtATime`, a copy of weather-service, each cut off after 10 s).
  - `StoredValue` is every column read (table, column, inverter); the SQL is built from it only. `StoredValues` reads a
    value of an inverter by its registry id: empty for an inverter not registered, `REGISTRY_UNAVAILABLE` before the
    registry was read. `Metric` is the wire names of `/history` (pinned) - a stored value, or a sum with signs.
  - **Rows are stored only after a change**, so a sum (`SumSeries`) carries each part from its last bucket, or from its
    last reading before the range; a point only once every part has a value; no min / max.
  - `HistoryRange` is **the fourth copy** (heating, water, weather) - the owner's choice of 2026-11-02, no move to
    cholewa-commons; its violation is `INVALID_RANGE` here. `UNKNOWN_METRIC` (pinned) lists the names, never echoes.
  - `DayCloser` (every hour, bounded at 5 min): every day over and not closed, from `CLOSE_AFTER` (15 min) past its
    midnight - the last SOFAR reading lands up to six minutes late (the /code-review of HAS-331) - at most 31 days back,
    from the day of the first grid row on a new database. **The daily counters of the inverters and the battery are the
    highest value after `COUNTER_SETTLES` (00:30)**: the inverter resets them by its own clock, so the last value of the
    day may be 0 and the first minutes may carry the day before. The grid: the counters at both midnights - not known on
    the first day. A day is inserted once (`EnergyDayEntity` is `Persistable`, always new), never corrected.
  - `CloudDays` keeps the last SolaX cloud answer of each day (the inverter's `dailyYield` - the plant's is the AC output,
    see above - and the plant's `dailyImported` / `dailyExported`); `SolaxCloudReader` reads the plant too since
    HAS-331, its failure logged apart and never making the cloud unreachable. In memory: a restart loses the cross-check
    of the day.
  - `/flows` holds the grid counters at midnight for the day only from `HOLD_AFTER` (5 min) past midnight (the
    /code-review of HAS-331); an inverter not registered or the registry not known is an input not known, never an error.
  - `Settlement` - net-metering by whole months, FIFO of the credit (12 months), from the first closed day; the
    house's estimate, not the invoice. `period-start` 9 (owner, 2026-11-02: the installation started in September 2020).
  - `EnergyRetention` (03:20, bounded at 15 min): batches of 5000, never the latest row of a table / an inverter; the
    days kept forever. `RetentionPropertiesTest` pins the unit (days) and the bounds (31 - 3650 days).
  - **Rehearsed** (2026-11-02): the released 0.7.0 image applied V1-V7 on a throwaway PostgreSQL with SSL, a month of
    synthetic readings a minute, the new jar V8: 30 days closed (the first one without the grid - no row before it), a
    month of history 0.05 s (grid-power) to 0.15 s (house-load, eight queries) warm, `/months` 0.01 s.
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
  | 167-169 | voltage per phase at the grid connection point ÷10 V - 0 with the grid lost | `gridPoint` (not served; the outage watch, HAS-334) |

  Information[2] is the inverter serial (checked against the registry). The grid power of the SOLAX is served with the
  inverter only, not stored - the grid history is the Pro 3EM's (`grid_reading`, HAS-327). **Still unknown**: the EPS
  voltages and powers (0 while the grid is there). Not identified though non-zero: 40, 43-46, 48-49,
  88, 90, 106, 121, 126, 128, 130-137, 139-145, 162-166, 170-179, 186-191.
- **Tests use `mockwebserver3`** (the clients of the dongle and of database-service), never the legacy
  `mockwebserver` (JUnit 4).
- **Surefire activates the `test` profile for every class**; the `includes` match only
  `**/*Test.java` and `**/*IT.java`.
