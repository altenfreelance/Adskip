# Ad Skip

Android app that watches a TV with the phone camera and microphone, detects
commercial breaks, and sends IR commands (fast-forward or mute) through the
phone's IR blaster.

## Build

Push to `main`. GitHub Actions builds `app-debug.apk` and attaches it to a
release named "Ad Skip build N". Open the Releases page on the phone, download
the APK and install it (allow "install unknown apps" for your browser when asked).

## First run

1. Allow camera and microphone.
2. Pick your TV brand and tap Test Mute, Test Play and Test FF with the phone aimed at the TV.
   The built-in codes are best guesses. If a button does nothing, edit the hex code.
3. Prop the phone up so the TV fills the camera view, then tap Start watching.
4. Leave "Send commands automatically" off until the log shows sensible ad starts and ends.

## Known limits

- The screen stays on while watching. Keep the phone plugged in.
- Ads are noticed about 8 seconds after they start, and the show is noticed about 6 seconds after it returns.
- Fast-forward ends when the logo returns or after 2 minutes, whichever comes first.
  Because the show is noticed late, fast-forward can overshoot into the first moments of the show.
  Rewind a little if that happens. This is the first thing to tune.
- Live TV cannot be skipped, so that mode only mutes.
