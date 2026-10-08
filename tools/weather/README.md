# Weather for Stadtlärm measurements

`stadtlaerm_weather.py` gives every measured minute and night of the Stadtlärm app its weather.
It downloads the 10-minute values of a MeteoSwiss automatic weather station (open data), joins
them with the app's CSV export (`minuten.csv`, optionally `ereignisse.csv`) and summarises the
result per hour and per night (22:00–06:00). Wind matters because it carries road noise further
downwind and makes wind noise at the microphone; rain raises tyre noise.

One file, Python 3.10 or newer, standard library only. Measurement exports are personal data:
keep them, and everything the tool writes from them, out of this repository.

## Usage

```sh
# 1. weather for the local dates 6 to 9 October 2026 (inclusive), Zürich/Fluntern
python3 -I stadtlaerm_weather.py fetch --station SMA --from 2026-10-06 --to 2026-10-09 \
    --cache ~/meteoswiss-cache --out-dir .
#    -> weather_SMA_2026-10-06_2026-10-09.csv

# 2. join with an app export; the road lies at bearing 265° from the microphone
python3 -I stadtlaerm_weather.py join --weather weather_SMA_2026-10-06_2026-10-09.csv \
    --minutes minuten.csv --events ereignisse.csv --highway-bearing 265 --out joined_minutes.csv
#    -> joined_minutes.csv          every minute with its weather (+ tailwind_ms)
#       joined_minutes_hourly.csv   one row per local hour
#       joined_minutes_nights.csv   one row per night, 22:00-06:00
#       joined_minutes_events.csv   every event with its weather (only with --events)

# 3. print the nights (and with --hourly their hours) as a table
python3 -I stadtlaerm_weather.py nights --joined joined_minutes.csv --hourly
```

Options shared by all commands: `--tz` (default `Europe/Zurich`). `fetch`: `--station` (default
`SMA`), `--cache DIR`, `--out-dir DIR` or `--out FILE`, `--offline` (cache only), `--timeout`.
`join`: `--highway-bearing DEG` (without it there is no tailwind column), `--max-gap-min`
(default 10), `--min-coverage` (default 0.5). Run `python3 stadtlaerm_weather.py <command> -h`
for details.

Errors (network down, file not found, not an app export) are printed as one `error:` line and
exit with status 2. Missing columns, gaps in the station data and minutes without weather only
print a `warning:` and leave the affected cells empty.

### Download and cache

MeteoSwiss splits each station's 10-minute data into three files
(`https://data.geo.admin.ch/ch.meteoschweiz.ogd-smn/<station>/ogd-smn_<station>_t_<part>.csv`,
station in lower case):

| part | period |
|---|---|
| `historical_2020-2029` (one file per decade) | start of measurement until 31 December of last year, updated once a year |
| `recent` | 1 January of this year until yesterday, updated around 01 and 10 UTC |
| `now` | yesterday 12 UTC until now, updated every 10 minutes |

`fetch` picks the files the date range needs, keeps them in `--cache` and re-downloads with a
conditional request (`If-None-Match`/`If-Modified-Since`) only when the cached copy is older than
10 minutes (`now`), 1 hour (`recent`) or 7 days (`historical`). If the server cannot be reached
it falls back to a cached copy with a warning. Where files overlap, the more checked one wins
(historical over recent over now). The list of files of a station is in the STAC catalogue, e.g.
<https://data.geo.admin.ch/api/stac/v1/collections/ch.meteoschweiz.ogd-smn/items/sma>.
Downloaded files are only parsed as numbers and timestamps; nothing in them is executed.

### Matching minutes to weather

MeteoSwiss time stamps are in UTC and mark the **end** of the 10-minute interval
(`07.10.2026 20:10` = 20:00–20:10 UTC = 22:00–22:10 CEST). `join` takes the middle of each app
minute (`start` + `duration_s`/2) and attaches the weather row whose interval centre is nearest,
if it is no further than `--max-gap-min` (10 min); otherwise the weather cells stay empty. Events
are matched by their start time.

### Summaries

Per hour (`hour_local`, e.g. `2026-10-08T02:00+02:00`; both 02:00 hours of the autumn clock
change stay apart) and per night (`night_of` = date of the evening, 22:00–06:00):

| column | meaning |
|---|---|
| `n_minutes`, `measured_min` / `measured_h` | minutes with a level and `coverage` ≥ `--min-coverage`, and their measured time |
| `coverage_pct` (nights) | measured time / length of the night (8 h; 7 or 9 h on clock-change nights) |
| `laeq_db` | energy mean of the minute LAeq, weighted by `valid_s` |
| `l90_median_db` | median of the minute L90 (background level) |
| `l10_l90_median_db` | median of the minute L10 − L90 (how much the level fluctuates) |
| `events`, `events_per_h` | sum of the minutes' `event_count`, and per hour of measured time |
| `events_listed` | events in `ereignisse.csv` in that hour/night (only with `--events`) |
| `wind_mean_ms`, `gust_max_ms` | mean wind speed, highest gust |
| `dir_median_deg` | circular median of the wind direction (0 = from north, 270 = from west) |
| `tailwind_mean_ms` (`tailwind_max_ms`) | mean (maximum) tailwind component, only with `--highway-bearing` |
| `rain_sum_mm`, `temp_mean_c`, `rh_mean_pct` | rain sum, mean temperature, mean humidity |
| `n_weather_rows` | number of 10-minute weather rows behind the weather columns |

Weather statistics use each 10-minute row once, however many minutes point to it (so rain is not
counted ten times). Levels are whatever the app exported: uncalibrated exports are relative.

## Weather columns

| column | MeteoSwiss parameter | unit | meaning |
|---|---|---|---|
| `station` | `station_abbr` | | station abbreviation |
| `interval_start_local` | | ISO 8601 | start of the 10-minute interval, local time with offset |
| `time_local` | `reference_timestamp` | ISO 8601 | end of the interval, local time with offset |
| `time_utc` | `reference_timestamp` | ISO 8601 | end of the interval, UTC |
| `temp_c` | `tre200s0` | °C | air temperature 2 m above ground |
| `rh_pct` | `ure200s0` | % | relative humidity 2 m above ground |
| `dewpoint_c` | `tde200s0` | °C | dew point 2 m above ground |
| `wind_ms` | `fkl010z0` | m/s | wind speed, scalar 10-minute mean |
| `wind_vec_ms` | `fve010z0` | m/s | wind speed, vector 10-minute mean (lower when the direction wanders) |
| `gust_ms` | `fkl010z1` | m/s | gust peak (1 s) within the 10 minutes |
| `wind_dir_deg` | `dkl010z0` | ° | wind direction, 10-minute mean, direction the wind comes **from** |
| `rain_mm` | `rre150z0` | mm | precipitation, 10-minute sum |
| `pressure_hpa` | `prestas0` | hPa | air pressure at station level (QFE) |
| `pressure_qff_hpa` | `pp0qffs0` | hPa | air pressure reduced to sea level (QFF) |
| `sunshine_min` | `sre000z0` | min | sunshine duration, 10-minute sum |
| `global_rad_wm2` | `gre000z0` | W/m² | global radiation, 10-minute mean |

`join` adds `wx_time_utc` and `wx_time_local` (end of the matched interval), these columns and,
with `--highway-bearing`, `tailwind_ms`. Parameter meanings are from
[ogd-smn_meta_parameters.csv](https://data.geo.admin.ch/ch.meteoschweiz.ogd-smn/ogd-smn_meta_parameters.csv).

## Stations in Zürich

From [ogd-smn_meta_stations.csv](https://data.geo.admin.ch/ch.meteoschweiz.ogd-smn/ogd-smn_meta_stations.csv):

| `--station` | name | height | WGS84 lat, lon | LV95 E / N | site |
|---|---|---|---|---|---|
| `SMA` | Zürich / Fluntern | 604 m | 47.381003, 8.567194 | 2685223 / 1248410 | south-facing slope above the city |
| `REH` | Zürich / Affoltern | 444 m | 47.427694, 8.517953 | 2681433 / 1253548 | plain, Affoltern in the north of the city |
| `KLO` | Zürich / Kloten | 426 m | 47.479611, 8.535961 | 2682711 / 1259339 | plain, next to Zürich airport |

Fluntern sits on the Zürichberg and sees the wind above the city; for sites in the Limmat valley
or near Affoltern, compare with `REH`. Wind in a street canyon is weaker and turned, so treat the
station wind as the regional flow, not the wind at the window.

## Tailwind

Sound carries further downwind: wind blowing from the road towards the microphone bends sound
down and raises the level, wind the other way lowers it. `--highway-bearing` is the compass
bearing **from the microphone to the road** (0 = north, 90 = east, 180 = south, 270 = west).
The station's `wind_dir_deg` is meteorological: the direction the wind comes **from**. Wind that
comes from the road's bearing is a full tailwind:

```
tailwind_ms = wind_ms × cos(wind_dir_deg − highway_bearing)

                 N 0°
                  |
   road  <--------M          M = microphone, road at bearing 270° (west)
   W 270°         |          wind from 270° (westerly):  tailwind = +wind_ms  (road -> M)
                  |          wind from  90° (easterly):  tailwind = -wind_ms  (headwind)
                 S 180°      wind from 0° or 180°:       tailwind =  0        (crosswind)
```

Positive values blow road noise towards the microphone, negative values away from it. For a long
road, use the bearing of the nearest point of the road. The bearing is a site parameter you have
to measure (e.g. on a map); a guess of ±20° changes the tailwind by at most about 6 % near
full tail- or headwind but a lot in crosswind.

## Tests

From the repository root:

```sh
python3 -I -m unittest discover -s tools/weather/tests -v
```

The tests use no network (a local HTTP server stands in for MeteoSwiss) and only synthetic data
in `tests/fixtures/`: a MeteoSwiss-format file with made-up values and a few made-up app rows.

## Data source and licence

Weather data: **Source: MeteoSwiss.** MeteoSwiss open data is published under the
[Creative Commons licence CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) (see the
[MeteoSwiss Open Data terms of use](https://opendatadocs.meteoswiss.ch/general/terms-of-use)):
it may be used, changed and passed on freely, provided the source is credited as
"Source: MeteoSwiss" (German "Quelle: MeteoSchweiz", French "Source: MétéoSuisse", Italian
"Fonte: MeteoSvizzera"), without suggesting that MeteoSwiss endorses the use. MeteoSwiss gives no
guarantee of correctness or completeness and asks users not to download the same content at high
frequency, which the cache above avoids. Any table, chart or file you publish with these weather
columns must carry the credit. File formats and update times:
[download documentation](https://opendatadocs.meteoswiss.ch/general/download) and
[automatic weather stations](https://opendatadocs.meteoswiss.ch/a-data-groundbased/a1-automatic-weather-stations).

The tool itself is Apache-2.0 like the rest of the repository.
