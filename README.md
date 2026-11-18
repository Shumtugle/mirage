# Mirage

A small Android camera with presets. It always shoots on the main rear lens
and opens at the default preset, which is 2× out of the box.

Project page: https://shumtugle.github.io/mirage/

## Use

- Tap a preset to apply it. The one marked • is the default the camera opens with.
- Press and hold a preset: save the current settings into it, make it the default,
  rename, delete.
- `+` at the end of the row adds a preset from the current settings.
- Pinch to zoom, double tap to return to the default preset, hold the picture
  to show or hide the rule-of-thirds grid.
- `−` / `+` beside the shutter change exposure, the ringed button on the right
  cycles white balance.
- Shutter: the round button, the hardware camera key or either volume key.
- The ring on the left shows the last photo; tap it to open the photo.
- The app's sign at the top opens a card with the camera's facts: photo size,
  sensor size, and whether the full sensor resolution is open to the app.

Photos go to `DCIM/Mirage`.

## Main lens only

The camera opens the first rear camera the system lists. On phones with several
rear lenses that is the main lens itself, or a combined camera whose 1× is the
main lens. Zoom never goes below 1×, so the camera never switches to the
wide-angle lens. Phones whose telephoto lens starts above the chosen zoom keep
cropping the main lens.

## Presets file

`presets.json` in the app's private storage:

```json
{ "version": 1,
  "presets": [ { "name": "2×", "zoom": 2.0, "ev": 0.0, "wb": 0, "default": true } ] }
```

`wb`: 0 auto, 1 daylight, 2 cloudy, 3 incandescent, 4 fluorescent.

## Build

Platform tools only: no IDE, no build system, no libraries. Java 8 language
level against an API 34 platform jar.

```sh
export ANDROID_JAR=/path/to/android-34.jar
export KEYSTORE=/path/to/your.keystore KEY_ALIAS=yourkey KS_PASS=yourpassword
./build.sh
```

The last line printed is the signed APK, `out/mirage.apk`.
Android 8 or newer. See `RELEASE.md` for releases.

## Licence

0BSD, see `LICENSE`. The fonts in `assets/fonts` are under the SIL Open Font
License; see `assets/licenses/fonts.ofl`.
