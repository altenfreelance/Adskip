# Commercial Skip

Android app that watches a TV with the phone camera and microphone, detects
commercial breaks, and sends IR commands (fast-forward or mute) through the
phone's IR blaster.

## Build

Push to `main`. GitHub Actions builds `app-debug.apk` and attaches it to a
release named "Commercial Skip build N". Open the Releases page on the phone, download
the APK and install it (allow "install unknown apps" for your browser when asked).

## Controlling the TV

- **IR blaster:** uses the phone's IR blaster. Pick your TV brand, then tap Find my TV. It tries that brand's mute codes while listening, and stops when the sound drops. You can also pick a code set by hand, or type codes in.
- **Roku over Wi-Fi:** works with Roku players and Roku TVs, no IR codes needed. Tap Find Roku, or type the Roku's IP address.

## First run

1. Allow camera and microphone.
2. Pick how you control the TV, then tap Mute, Play and FF under the test buttons.
   For IR, the built-in codes are best guesses. If a button does nothing, edit the hex code.
3. Prop the phone up so the TV fills the camera view, then tap Start watching.
4. Leave "Send commands automatically" off until the log shows sensible ad starts and ends.

## Known limits

- The screen stays on while watching. Keep the phone plugged in.
- Ads are noticed about 8 seconds after they start, and the show is noticed about 6 seconds after it returns.
- Fast-forward ends when the logo returns or when the "Longest fast-forward" limit (default 45 s) runs out, whichever comes first.
  Because the show is noticed late, fast-forward can overshoot into the first moments of the show.
  Rewind a little if that happens. This is the first thing to tune.
- If the AD/SHOW call is wrong, tap Copy debug data and send it to Claude. It holds the last two minutes of readings.
- Live TV cannot be skipped, so that mode only mutes.

## Pipeline

Every pull request runs the unit tests and a full debug build. Only code that
lands on `main` publishes a release with the APK. To make that a hard rule, turn on
GitHub branch protection for `main` and require the "build" check to pass.

## What it knows about TV

Ad breaks come at fairly regular spacing, last a minute or more, and rarely follow
each other closely. The detector remembers when earlier breaks began, leans slightly
toward calling an ad near the next expected break, and is slightly more skeptical
for four minutes after a break ends.

## Credits

TV remote codes come from the open [Flipper-IRDB](https://github.com/UberGuidoZ/Flipper-IRDB)
database (CC0). The build downloads it and turns it into `tvcodes.json` with `tools/build_tv_codes.py`.
