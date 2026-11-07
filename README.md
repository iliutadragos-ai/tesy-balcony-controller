# TESY Balcony Controller

Android companion app for a TESY CN 052 Wi-Fi convector.

- OnePlus / Android 15+
- Default automation: turn on only below 10°C; request OFF after 20 minutes
- Check temperature every 3 hours (Android may defer scheduled work)
- MyTESY cloud integration is community-protocol based and **not yet validated on the physical CN 052**.

The complete Kotlin/Gradle source project is stored in [android-source.tar.xz](./android-source.tar.xz). The GitHub Actions workflow extracts it, runs unit tests, builds a debug APK, and uploads the APK as a 14-day artifact.

## Build the APK
Open **Actions → Build Android APK → Run workflow**. Download the artifact named `tesy-balcony-controller-debug-apk` and install `app-debug.apk`.

## Important safety notes
- Unknown temperature or power status never triggers ON.
- A heater already switched on manually is not taken over by the automation.
- ON/OFF commands are verified by reading state back from MyTESY; QoS 0 message publish is not treated as device confirmation.
- The separate OFF work request survives stopping future temperature checks and retries until OFF is confirmed.
- WorkManager is not exact timing. OxygenOS battery controls, force-stop, loss of internet, phone shutdown, or a MyTESY outage can delay/prevent commands.
- Test the device discovery and a supervised ON/OFF cycle before unattended operation. Keep built-in thermostat and protection functions enabled.

Credentials must be entered only in the Android app. Do not add MyTESY credentials or device tokens to this repository.

Unofficial independent project; not affiliated with or endorsed by TESY.
