# TESY Balcony Controller

Android companion app for a TESY CN 052 Wi-Fi convector, intended for OnePlus / Android 15+.

## Default automation profile
- Temperature threshold: 10°C (only start when strictly below this value)
- Heating cycle: request ON, then request OFF after 20 minutes
- Check interval: every 3 hours (Android may defer execution)

The app does not replace the convector thermostat. It sends ON/OFF only and leaves the selected Eco/Comfort/Sleep setpoint unchanged.

## Status
This is an **alpha prototype**. The source is being compiled and tested in GitHub Actions. The community MyTESY cloud protocol has not yet been verified against the user's physical CN 052 convector or account. Do not use unattended until device discovery and a supervised ON/OFF cycle have both been confirmed.

## Safety behaviour
- Missing, invalid or unknown temperature / power state never triggers ON.
- A heater already switched ON manually is left alone; the app does not adopt or later switch off manually started heating.
- An ON/OFF request is not considered successful just because MQTT accepted a QoS 0 publish. The app reads status back from MyTESY.
- The OFF deadline is stored independently from the periodic check, so stopping future checks does not cancel a pending OFF task.
- If OFF cannot be confirmed due to cloud/network failure, the app retries. This cannot guarantee switching off during a long outage; do not treat the app as a safety-rated controller.

## MyTESY protocol
The adapter is based on the community-described MyTESY v4 protocol in https://github.com/Kosztyk/homebridge-tesy-native. It uses HTTPS discovery and MQTT 3.1.1 over secure WebSocket. This is not a public TESY API and can change. It currently requires MyTESY email, password and account user ID.

Credentials are entered only in the app and encrypted with AES-GCM using Android Keystore. Do not put credentials or device tokens in GitHub or this conversation.

## Build
GitHub Actions reconstructs the source archive from the text chunks, validates its XZ checksum, runs unit tests, builds a debug APK and publishes the artifact for 14 days.

Open [Actions](https://github.com/iliutadragos-ai/tesy-balcony-controller/actions), select **Build Android APK**, then **Run workflow** (or use the latest push workflow). Download artifact `tesy-balcony-controller-debug-apk` and install `app-debug.apk`.

## Timing limitations
WorkManager is deferred scheduling: 3 hours is an interval, not an exact alarm. OxygenOS battery management, Doze, force-stop, device shutdown, missing internet or MyTESY outages can delay or prevent commands. Allow unrestricted background activity for the app on OnePlus. Keep the convector's built-in thermostat and protections enabled.

Unofficial independent project; not affiliated with or endorsed by TESY.
