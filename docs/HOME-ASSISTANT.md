# Connecting TV Home to Home Assistant

This edition adds a **Lights** group and a **Cameras** group to the home screen, status pills next to Settings, a
doorbell pop-up over whatever you're watching, and (optionally) a Live weather sky driven by your own weather
station. Everything is set up in one file, `ha.json`, that you send to the TV from your computer.

Install the app first: [INSTALL.md](../INSTALL.md) (use the `tv-home-ha` APK).

## 1. Make a Home Assistant user and token for the TV

A separate, non-admin user is safest: if the TV were ever compromised, that user can see states and run your
scripts, but can't change Home Assistant itself.

1. In Home Assistant: **Settings → People → Users → Add user**. Call it something like `TV`, give it a password,
   and leave **Administrator** off. (You need Advanced mode turned on in your profile to see the Users tab.)
2. Log in as that user (a private browser window is easiest), open the **profile** (bottom left) **→ Security**,
   and under **Long-lived access tokens** choose **Create token**. Copy it; it's only shown once.

## 2. Write your ha.json

Copy [`ha.example.json`](ha.example.json) to `ha.json` and edit it. Put in your Home Assistant address and the
token, then change the entity IDs to yours (Settings → Entities shows them). Delete any section you don't want:
with no `lights` there's no Lights group, with no `doorbell` there's no pop-up, and so on.

| Section | What it does |
|---|---|
| `url`, `token` | Where Home Assistant is (as the TV reaches it) and the token from step 1. |
| `lights` | Tiles in the Lights group. Each tile has a `name`, an `icon` (an emoji) and `colors` (used for its glow, and for the *Glow* background while it runs). `start` is what pressing it turns on: a script, scene, light, switch, `input_boolean`, button, or automation (which gets triggered). If the tile has a `running` entity (usually an `input_boolean` your script switches on), the tile shows when it's running, and pressing it again stops it: by turning on `stop` if given, otherwise by turning `running` off. A tile with `"action": "lights_back"` stops every running tile, plus anything listed in its `also_stop`. |
| `cameras` | `"action": "doorbell"` shows the doorbell camera pop-up. `"action": "all_cameras"` opens a screen with every camera in Home Assistant: live on the focused one, full screen (the high-resolution stream if your camera has one) with OK. |
| `doorbell` | `ring`: the binary sensor that turns on when someone rings. The pop-up plays `camera`, with a first frame from `snapshot` if you have one. Use the camera's light sub-stream there (Reolink's "Fluent"), so the picture is live within a second or two of the bell. `camera_hd` (optional) plays a full-resolution stream instead: sharper, but it can take several seconds longer to start. `replies` are buttons that speak at the door through `speak.tts` on `speak.speaker`. `vehicle` (optional) shows a quieter preview when a car arrives, at most every `vehicle_cooldown_minutes`, and not while `vehicle_quiet_when` is on. |
| `weather` | A weather station and/or a `weather.` entity. Any sensor can be left out; units (°F/°C, mph/km/h/m/s, in/mm per hour) are read from the sensors. With `solar_radiation` (W/m²) or `solar_lux`, the Live weather sky works out cloud cover from your own sunlight readings, and the sun ducks behind a cloud when the light really drops. Snow, fog and thunder come from the `forecast` entity. Without a `weather` section, TV Home uses Open-Meteo like the plain edition. |
| `pills` | Small status pills next to Settings, showing an entity's state (hidden while it's unavailable). |
| `not_music` | Apps whose playback shouldn't show album art (video apps TV Home doesn't already know). |
| `keep_wireless_debugging_on` | Developers only: keeps Wireless debugging switched on after Wi-Fi drops. Also needs `adb shell pm grant com.cesmotacon.tvhome android.permission.WRITE_SECURE_SETTINGS`. Leave it `false` otherwise. |

## 3. Send it to the TV

With the TV connected over adb (INSTALL.md, step 4), from the folder with `ha.json` and `adb`:

- **Mac / Linux:**

  ```
  adb shell content write --uri content://com.cesmotacon.tvhome.setup/ha.json < ha.json
  ```

- **Windows** (PowerShell can't redirect a file into a program, so this goes through `cmd`):

  ```
  cmd /c "adb shell content write --uri content://com.cesmotacon.tvhome.setup/ha.json < ha.json"
  ```

TV Home saves it, reconnects and redraws the home screen. Check what it thinks:

```
adb shell content read --uri content://com.cesmotacon.tvhome.setup/status
```

You want something like `Configured · Connected · lights 4, cameras 2, doorbell yes, weather station yes`.
To change anything later, edit `ha.json` and send it again. Then delete `ha.json` from your computer or keep it
somewhere private: it contains your token.

## 4. Turn on the helper (for the doorbell pop-up)

The doorbell pop-up appears over any app, so it runs in TV Home's background helper. Turn the helper on with
steps 1 to 3 of "Make the Home button open TV Home" in [INSTALL.md](../INSTALL.md#make-the-home-button-open-tv-home).
You don't have to turn on the Home button setting itself. On TCL TVs also do the "TCL TVs only" step.

## Trying it out

These pop the doorbell up without anyone at the door:

```
adb shell am broadcast -a com.cesmotacon.tvhome.CAM -p com.cesmotacon.tvhome       # the camera, quietly
adb shell am broadcast -a com.cesmotacon.tvhome.VEHICLE -p com.cesmotacon.tvhome   # the vehicle preview
adb shell am broadcast -a com.cesmotacon.tvhome.RING -p com.cesmotacon.tvhome      # as if someone rang
```

Careful with `RING`: its reply buttons really speak through the doorbell speaker.

## Troubleshooting

- **"Home Assistant: not set up yet" / "no token yet".** `ha.json` didn't arrive or has no `url`/`token`; send it
  again and check the status line.
- **"Home Assistant: token rejected".** The token was deleted, or its user was removed. Make a new one.
- **"Home Assistant: offline".** The TV can't reach `url`. Use an address the TV can resolve (an IP address is
  safest) and check the port.
- **A tile does nothing.** The TV's user may not be allowed to run it, or the entity ID is wrong; Home Assistant's
  logbook shows calls from the TV user.
- **No doorbell pop-up.** Check the helper is on (step 4), and that `ring` turns on when you press the doorbell
  (Developer tools → States).
