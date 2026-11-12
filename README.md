# AirSense — Android app for the portable air-quality sensor

Kotlin + Jetpack Compose app for the PMS5003 / AHT20 / MQ-135 sensor board with a classic-Bluetooth SPP module (JDY-31 / SPP-C).

## Features
- **Auto-connect** to the saved sensor (MAC address) on launch, with exponential-backoff reconnection if the link drops.
- **Live view**: indicative AQI gauge (US EPA 2024 breakpoints), PM1.0 / PM2.5 / PM10, temperature, humidity and the MQ-135 gas index, each with a sparkline.
- **Background logging** in a foreground service: one sample every 1 min … 1 h (or a custom interval), and it keeps running with the screen off. Samples are grouped into named sessions.
- **GPS tagging** of logged samples, plus a **map** (OpenStreetMap, no API key) with dots coloured by PM2.5 category or by any other metric.
- **History & analysis**: zoomable time ranges or a single session, a trend chart with WHO and user thresholds (tap or drag to read values), statistics for every metric, time spent in each AQI category, an hour-of-day profile, daily means, and the Pearson correlation of the selected metric with humidity and temperature.
- **Threshold alerts** (PM2.5, PM10, gas, temperature, humidity) with hysteresis and a 15-minute cool-down.
- **CSV export** (the current selection or everything) for Python or Excel.
- **Raw serial console** for debugging the firmware.

## Protocol
The app sends `H`, `T`, `C`, `P` (default line ending CR+LF, which you can change in Settings) and parses replies such as
`humi:35.00`, `temp:29.00`, `CO:1.18`, `PM1.0=7.0 ug/m3`, `PM2.5=19.0 ug/m3`, `PM10=19.0 ug/m3`.
The parser is tolerant: it accepts `:` or `=`, any letter case, units, and alternative names such as `RH`, `Temperature`, `CO2` and `gas`. It also ignores the echoed command letter.

## Build
On every push to `main`, GitHub Actions builds the app and publishes `AirSense-release.apk` under **Releases → latest**.
To build locally, open the project in Android Studio (JDK 17) or run `./gradlew assembleRelease`.

## Notes
- The **MQ-135** is not a CO or CO₂ sensor. It responds to many gases, and without R0 calibration and temperature/humidity compensation its "ppm" is not reliable. The app shows it as a configurable "gas index", so watch its trend rather than the absolute value.
- The **AQI** is defined on 24-hour averages. Computed from a single reading it is only indicative.
- **Optical PM sensors** over-read at high humidity. The analysis screen warns you when PM correlates strongly with RH.
- **Android only**: iOS does not support classic Bluetooth SPP.
