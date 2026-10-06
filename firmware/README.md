# Stadtlärm sensor firmware (planned)

No code yet. This directory will hold the firmware for a fixed windowsill noise sensor:

- **MCU:** ESP32-S3
- **Microphone:** ICS-43434 digital MEMS microphone on I2S
- **Power:** small solar panel + LiPo cell
- **Connectivity:** WiFi
- **Operation:** duty-cycled to stay within the solar power budget

The sensor will implement the same DSP definitions as the phone app (A-weighting, Fast time
weighting, LAeq / LAFmax / percentiles, event detection; see [`android/dsp/`](../android/dsp/))
and use the same calibration procedure (see the calibration section in
[`android/README.md`](../android/README.md#calibration-step-by-step)), so that phone and sensor
measurements are comparable.

Like the app, the sensor will store and transmit level aggregates only, never audio
(see [PRIVACY.md](../PRIVACY.md)).
