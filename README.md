# Mirage

A small Android camera with presets. It always shoots on the main rear lens
and opens at the default preset, which is 2× out of the box.

Project page: https://shumtugle.github.io/mirage/

## Use

- Tap a preset to apply it. The one marked • is the default the camera opens with.
- Press and hold a preset: save the current settings into it, make it the default,
  rename, delete.
- `+` at the end of the row adds a preset from the current settings.
- The viewfinder shows a little more than the photo: the photo is the frame
  inside it. Drag the frame; it stays where you leave it.
- Tap to focus and meter, press and hold to lock focus and exposure, double
  tap to bring the frame back to the middle, pinch to zoom.
- `−` / `+` beside the shutter change exposure, the ringed button on the right
  cycles white balance.
- Shutter: the round button, the hardware camera key or either volume key.
- The ring on the left shows the last photo. Tap it to look through the shot
  and its variants — corrected light, brighter, the horizon levelled, light
  with the verticals stood up. All of them are already saved; delete the ones
  you do not want, or open one in an editor.
- The app's sign at the top opens a card with the camera's facts: photo size,
  sensor size, whether the full sensor resolution is open to the app, and
  whether the camera can crop off the centre. The thirds grid is switched there.

The light and geometry of the variants are the same code as in the author's
photo editor: `Light`, `Upright`, `Pose` and `Warp` are copies of its files.

Photos go to `Pictures/Mirage`, and so do the variants, each as soon as it is ready.
A diary of each day, plain text, goes to `Documents/Mirage`.

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
