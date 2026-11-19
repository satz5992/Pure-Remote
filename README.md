# Pure Remote

Two remotes in one ad-free app:

* **Wi-Fi remote** for Android TV / Google TV (below).
* **IR remote** for *any* TV, using your phone's IR blaster (many Xiaomi/Redmi/Poco/Vivo/iQOO phones): pick the brand, test codes until the TV responds, done. 115 brands, ~370 code sets, works fully offline. Can't find your brand? "Find my TV" tries every power code.


Ad-free Android phone remote for **Android TV / Google TV** (Sony, TCL, Mi, OnePlus, Hisense, Chromecast with Google TV, Mi Box and other Android TV boxes).

* No ads, no analytics, no accounts, no Google Play Services – the app only talks to your TV over your home Wi-Fi.
* Uses the same Wi-Fi protocol as Google's official remote (pair once with a 6-character code shown on the TV).
* D-pad, OK, Back, Home, Power, Volume, Mute, Channel ±, Play/Pause, Rewind/Fast-forward, Input, TV settings, number pad, keyboard (type into TV search), quick launch for YouTube / Netflix / Prime Video / Hotstar.
* Phone volume buttons control the TV volume while the remote is open. Press-and-hold arrows/volume to repeat.

## Install
Open the **Releases** page of this repo on your phone → download `PureRemote.apk` → install (allow "Install unknown apps" for your browser when asked).

## Use
1. TV and phone on the same Wi-Fi, TV switched on.
2. Open Pure Remote → tap your TV (or *Add TV by IP address*).
3. Type the code the TV shows → done. Next time the app opens straight to the remote.

If the TV isn't found: TV → Settings → System → About → make sure "Android TV Remote Service" is enabled (it is by default), or add the TV by IP.

## Build
`./gradlew assembleRelease` (JDK 17 + Android SDK 35). GitHub Actions builds the APK on every push to `main`.

## Credits
IR codes: [Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB) (CC0), encoded with Flipper Zero firmware IR encoders (GPL-3.0, used only at build time).
