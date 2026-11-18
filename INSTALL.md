# Installing TV Home

This takes about 20 minutes the first time. You'll turn on a hidden developer setting on the TV, connect to it
from a computer on the same Wi-Fi, and install the app. Nothing on the TV gets deleted or switched off, and you
can undo all of it (see [Removing TV Home](#removing-tv-home)).

Works on Google TV and Android TV with Android 11 or newer (TVs from TCL, Sony, Hisense, Philips and others, the
Chromecast with Google TV, the Google TV Streamer, the NVIDIA Shield). Tested on a TCL Google TV running Android 14.

**You need:**

- The TV and a computer (Windows, Mac or Linux) on the same Wi-Fi network.
- The TV remote.

---

## 1. Get the tools on your computer

1. Download Google's **SDK Platform-Tools** for your computer: <https://developer.android.com/tools/releases/platform-tools>.
   Unzip it somewhere easy, like your Desktop. You get a folder called `platform-tools`.
2. Download the latest **TV Home for Home Assistant APK** (`tv-home-ha-<version>.apk`) from this project's
   [Releases page](https://github.com/CESMOtacon/tv-home-ha/releases) and put it in that same `platform-tools`
   folder. (Don't have Home Assistant? Use [plain TV Home](https://github.com/CESMOtacon/tv-home) instead.)
3. Open a terminal in the `platform-tools` folder:
   - **Windows:** open the folder, click the address bar, type `powershell` and press Enter.
   - **Mac:** open Terminal, type `cd ` (with a space), drag the folder onto the window and press Return.
   - **Linux:** open a terminal in the folder.

In the commands below, type `adb` on Mac and Linux, and `.\adb` on Windows PowerShell. On Mac you may need
`./adb` instead of `adb`.

## 2. Turn on developer options on the TV

1. On the TV, open **Settings → System → About**.
2. Scroll down to **Android TV OS build** and press **OK** on it **seven times**. The TV says "You are now a
   developer".
3. Go back to **Settings → System**. There's a new **Developer options** menu.

(On some older Android TV models: Settings → Device Preferences → About → Build.)

## 3. Turn on wireless debugging

1. Open **Settings → System → Developer options**.
2. Turn on **Wireless debugging**. If the TV asks whether to allow it on this network, choose **Allow**.
3. Open **Wireless debugging** (press OK on its name). You'll see the TV's **IP address & port**, for example
   `192.168.1.50:37215`. Keep this screen open.

> **No "Wireless debugging" on your TV?** Older Android TV models have **USB debugging** or **Network debugging**
> instead. Turn that on, skip step 4, and connect with `adb connect <the TV's IP address>:5555`. The IP address is
> in Settings → Network & Internet → (your Wi-Fi).

## 4. Pair your computer with the TV (only once)

1. On the TV's Wireless debugging screen, choose **Pair device with pairing code**. The TV shows a six-digit code
   and an address with its own port, for example `192.168.1.50:41721`.
2. On the computer, type the following, using **the address from the pairing box**:

   ```
   adb pair 192.168.1.50:41721
   ```

   Type the six-digit code when it asks. You should see `Successfully paired`.
3. Close the pairing box. Now connect using **the address on the main Wireless debugging screen**. It has a
   different port from the pairing one:

   ```
   adb connect 192.168.1.50:37215
   ```

   You should see `connected to ...`. If the TV asks "Allow debugging?", tick **Always allow from this
   computer** and choose **Allow**.
4. Check it worked:

   ```
   adb devices
   ```

   The TV should be listed as `device`. If it says `unauthorized`, accept the prompt on the TV and try again.

## 5. Install TV Home

```
adb install tv-home-ha-1.0.0.apk
```

(Use the actual file name you downloaded.) It should say `Success`.

## 6. Open it

On the TV, open your apps list (on Google TV: **Apps** at the top, or the apps row) and choose **TV Home**.
Google TV lets you move it to the front of your favourites so it's one click away.

Have a look around:
- **Settings** (top right) changes the background, turns weather on or off, and switches °F/°C.
- **Hold OK** on an app in the row to move it left or right or take it off. Add apps from **All apps** (hold OK
  on an app there).
- **All apps** also has a **Google TV** tile that opens Google's home screen, for search and your watchlist.

Next, connect it to Home Assistant: **[docs/HOME-ASSISTANT.md](docs/HOME-ASSISTANT.md)**. Until then, TV Home
works like the plain edition (with a pill saying Home Assistant isn't set up yet).

Everything below is optional.

---

## Optional extras

All of these run from the same terminal while the TV is connected (step 4.3 reconnects it if needed).

### Album art while music plays

This lets TV Home see what's playing, to show the cover art and the track name:

```
adb shell cmd notification allow_listener com.cesmotacon.tvhome/com.cesmotacon.tvhome.MediaWatcher
```

### Make the Home button open TV Home

Google TV always sends the Home button to its own home screen and doesn't let you pick another one. TV Home
works around this with a small helper (an accessibility service) that only listens for the Home button.

1. See whether any accessibility services are already on:

   ```
   adb shell settings get secure enabled_accessibility_services
   ```

2. If that printed `null` or nothing, run:

   ```
   adb shell settings put secure enabled_accessibility_services com.cesmotacon.tvhome/com.cesmotacon.tvhome.HomeKeyService
   ```

   If it printed something else, keep it and add ours after a colon (`:`), for example:

   ```
   adb shell settings put secure enabled_accessibility_services "what.it.printed/Service:com.cesmotacon.tvhome/com.cesmotacon.tvhome.HomeKeyService"
   ```

3. Turn accessibility on:

   ```
   adb shell settings put secure accessibility_enabled 1
   ```

4. In TV Home, open **Settings** and turn on **Home button opens TV Home**.

To go back to Google's Home button, turn that setting off again. Google's home screen is still there either way:
**All apps → Google TV**.

### TCL TVs only

TCL blocks the background helpers of apps that didn't come from the Play Store. If the Home button or album art
doesn't work on a TCL TV, allow it:

```
adb shell cmd appops set com.cesmotacon.tvhome AUTO_START allow
```

If the Home button still goes to Google afterwards, run the `enabled_accessibility_services` command from step 2
of the previous section again, or restart the TV.

### Set the weather location yourself

By default TV Home works out your town from your internet address, about once a week. To give it an exact place
instead (latitude and longitude from any map; the name is just for display):

```
adb shell am broadcast -a com.cesmotacon.tvhome.LOCATION -p com.cesmotacon.tvhome --ef lat 40.7128 --ef lon -74.0060 --es name "New York"
```

To go back to automatic:

```
adb shell am broadcast -a com.cesmotacon.tvhome.LOCATION -p com.cesmotacon.tvhome --ez auto true
```

### Set the whole Apps row at once

Arranging the row on the TV with the remote works fine. To set it from the computer instead, list the apps' package
names in order, separated by commas:

```
adb shell am broadcast -a com.cesmotacon.tvhome.FAVORITES -p com.cesmotacon.tvhome --es apps "com.google.android.youtube.tv,com.netflix.ninja,com.spotify.tv.android"
```

`adb shell pm list packages` lists every package on the TV.

---

## When you're done

Turn **Wireless debugging** off again: **Settings → System → Developer options → Wireless debugging**. You can
also turn off Developer options. The pairing is remembered, so next time you only need step 3 and `adb connect`.

## Updating

Download the new APK and run:

```
adb install -r tv-home-ha-<new version>.apk
```

Your settings and Apps row are kept.

## Removing TV Home

```
adb uninstall com.cesmotacon.tvhome
```

Or on the TV: **Settings → Apps → TV Home → Uninstall**. That removes the app, its settings and its helper. The
Home button goes back to Google straight away. TV Home never changes or disables anything of Google's, so there's
nothing else to undo.

## Troubleshooting

- **`adb connect` fails, or the TV dropped off.** Android switches Wireless debugging off whenever the TV loses
  Wi-Fi or restarts, and the port number changes each time. Turn it back on (step 3) and `adb connect` to the new
  address. You don't need to pair again.
- **`more than one device/emulator`.** Add `-s <address>` after `adb`, for example `adb -s 192.168.1.50:37215 install ...`.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`.** A copy signed differently is already installed (for example one you
  built yourself). Uninstall it first.
- **No temperature next to the clock.** Check **Settings → Weather and temperature** is on and the TV is online.
  The first reading takes a few seconds.
- **The Home button still opens Google TV.** See "Make the Home button open TV Home", and the TCL note.
