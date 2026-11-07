# TESY Balcony Controller

Android app for a TESY CN 052 EI Cloud convector.

## Initial profile
- Device: OnePlus, Android 15+
- Temperature threshold: 10 °C
- Heating cycle: 20 minutes
- Re-check: every 3 hours

## Safety requirements
- Fail closed: never turn the heater on when temperature is missing, stale, or invalid.
- Verify ON/OFF by reading state back from MyTESY; MQTT publish success alone is not device confirmation.
- Persist an active cycle's OFF deadline independently from the periodic check.
- Stopping automation must not cancel a pending OFF action.
- Never commit account credentials, device tokens, or logs containing them.
- Do not claim CN 052 compatibility until tested on this exact user's convector.

## Status
Repository bootstrap only. Android project and cloud adapter must be added and compiled before an APK can be provided.

See docs/IMPLEMENTATION_PLAN.md for the planned implementation and caveats.
