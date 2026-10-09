# Diginika companion app

The Pi's gallery and Wigglegram Bench pages in an app, with a button that joins the camera's Wi-Fi.
The Pi side is unchanged, so the same pages still work in any browser on a computer.

## Build the APK without installing anything (GitHub)

1. Create a new GitHub repository and upload everything in this folder to it (this folder is the repo root).
2. Open the repository's Actions tab. The "Build APK" workflow runs on every push, or run it by hand.
3. When it finishes, open the run and download the `diginika-companion-apk` artifact (a zip with `app-debug.apk`).
4. Copy the APK to the phone and open it. Android will ask you to allow installing from that source.

## Or build with Android Studio

Open this folder in Android Studio and run Build > Build APK(s).

## Notes

- The Wi-Fi name and password are set at the top of `MainActivity.java` (`SSID`, `PASSWORD`). They must match `WIFI_AP_SSID` and `WIFI_AP_PASSWORD` in `diginika_capture.py`.
- The APK is signed with the committed `app/debug.keystore`, so a newer APK installs over an older one.
- Saved photos and exports go to `Downloads/Diginika`.
