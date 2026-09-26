# 5G Only Enabler (LSPosed)

LSPosed module that adds missing choices to
**Settings → Mobile Networks → SIM1 / SIM2 → Preferred Network Type**:

1. **5G Only** (NR only, RIL mode `27`)
2. **4G Only** (LTE only, RIL mode `11`)
3. **3G Only** (WCDMA only, RIL mode `2`)
4. **5G/4G Only** (NR/LTE, RIL mode `29`)

Stock entries are kept as-is. Works per-SIM (SIM1/SIM2 screens each get the
extra entries). Tested target: OnePlus 11R (CPH2487), OxygenOS 16 / Android 16,
but the hooks are generic AOSP + OPlus and should work on Android 12–16.

## What it hooks

- Settings UI (`com.android.settings`, `com.oplus.wirelesssettings`):
  every Preferred-Network-Type-looking `ListPreference` is extended with the 4
  entries (re-applied on each `setEntries`/`setEntryValues` and fragment start,
  so stock refreshes can't wipe them).
- Selection of an extra entry is applied straight to that SIM's modem via
  `TelephonyManager.setAllowedNetworkTypesForReason(REASON_USER, mask)`
  (bitmask resolved by reflection, so it's correct on every Android version),
  with a legacy `setPreferredNetworkType` fallback. All other values delegate
  to the stock controller untouched.
- `getAllowedNetworkTypesForReason` is widened so stock filtering keeps its own
  entries; carrier-config `hide_*` network flags are forced off.
- Phone / system (`com.android.phone`, `android`): log-only network-type hooks
  plus the same carrier un-hide, so LSPosed logs show what the modem was asked.

## Scope (LSPosed)

- `com.android.settings`
- `com.oplus.wirelesssettings`
- `com.android.phone`
- `android` (system_server)

## Build

Pushes to `main` build a debug APK via GitHub Actions (artifact
`5g-only-enabler-debug-apk`). Tags `v*` create a GitHub release.

## Test

1. Install the APK, enable the module in LSPosed for Settings +
   WirelessSettings + Phone + System, reboot.
2. Settings → Mobile Networks → SIM1 → Preferred Network Type → pick
   **5G Only** (repeat for SIM2 if needed).
3. Check LSPosed logs for `NetModeEnabler`.

## Warnings

- **5G Only with no 5G coverage = No Service** (no calls/SMS/data) until you
  switch back. That is the modem doing exactly what you asked.
- Some carriers reset the mode on reboot/SIM swap; just re-select it.
- If an entry doesn't stick, grab the `NetModeEnabler` lines from LSPosed logs
  and open an issue.
