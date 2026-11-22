# Changes

## 0.5.1

- The panel no longer grows over the picture. The frost under its glass was
  as tall as the part of the panel lying on the picture, and the panel took
  its height from the frost: each taller than the last, until the panel
  filled the screen. The frost now fills the panel and is only painted over
  the part that lies on the picture.

## 0.5.0

- A new look. The viewfinder runs from edge to edge, pressed to the top of
  the screen; the zoom stands over it in large light figures with one line
  of monospaced facts below.
- Everything else floats on frosted glass: presets or zooms and the shutter
  row in one panel, and Photo, Video, Album under a glass drop that slides
  to the one chosen. Where the panel lies over the picture, it is frosted
  with the picture itself.
- The shutter ring shimmers and slowly turns; it turns faster while a clip
  records. A recording clip shows a red edge around the picture and its time
  floating at the top.
- Brightness is a rail on the right edge with a small sun that rides up and
  down. In photo the rail is a slider and a double tap on it gives
  brightness back to the camera; in video the whole viewfinder stays the
  slider.
- A ball on a spring at the top right: left keeps only the shot, the middle
  also develops its variants, a push to the right opens this camera's card.
- The photo frame is a fine rounded line; type is one grotesque and its
  monospaced sibling.

## 0.4.8

- Video brightness is kept between launches: it is there again every time
  video is turned on, until it is changed or given back to the camera with
  a double tap.

## 0.4.7

- Brightness in video, also while a clip records: the minus and plus
  buttons move it by a third of a stop, and the viewfinder is a slider -
  a finger going up, as the phone is held, makes the picture brighter,
  down darker. A double tap on the viewfinder gives brightness back to
  the camera. It holds for every clip until the app is closed.
- The brightness shows under the shutter and in the line under the zoom
  when it is not the camera's own.
- The diary notes each change and what the camera made of it a moment
  later.

## 0.4.6

- The step of the steady ladder that works is kept across new versions;
  it is forgotten only when the ladder itself changes.
- Before a steady clip rebuilds the session, the preview texture is held at
  the video size, so the preview stream cannot take the view's own shape.
- The diary notes the preview transform once before a clip and once while
  it records.

## 0.4.5

- Steady no longer pushes a stalled camera: a clip that gets no frames is
  dropped, the camera is opened afresh one step lower on a ladder of
  settings (60 fps with electronic and optical stabilisation, then 30 fps
  with both, optical only, none, and finally zoom by sensor crop), and the
  clip starts again by itself. The step that works is kept for this build.
- The steady preview mode is gone from the ladder.
- A clip session that finishes building after the camera was closed or
  switched is let go quietly instead of starting a recorder that is gone.
- The diary notes lost buffers, aborted sequences and whether the steady
  preview flows.

## 0.4.4

- Steady records: in the ordinary session each clip now gets its own
  recorder and the session is rebuilt around it, because the lasting
  recorder surface that serves the high-speed session got no frames there.
  Starting a steady clip takes a moment longer.

## 0.4.3

- Steady finds what the phone can carry: when recording gets no frames with
  the full stabilisation, it steps down without stopping (steady preview,
  then electronic, then optical only, then none). Each step and each lost
  frame is written in the diary.

## 0.4.2

- Video opens reliably: the session no longer loses its surface when the
  screen closes the camera in the middle of building it (the sound question
  did that), and the preview keeps the video size when the screen stretches
  it; a refused size is tried once more.

## 0.4.1

- Steady, beside the video zooms: an ordinary session at up to 60 frames
  with the phone's own stabilisation, optical and electronic, and a steady
  preview where the phone offers it. Off, video stays at 120 frames, which the
  high-speed mode records without stabilisation.

## 0.4

- Video: 720p at 120 frames a second with sound, played back at its real
  speed, from the main lens, at 1x, 1.5x or 2x. The camera runs its
  high-speed mode when it offers one at that size; otherwise the fastest
  ordinary rate, and the diary says which.
- Photo, Video and Album tabs at the bottom; the shutter turns into a record
  button and the time runs under it.
- Clips go to `DCIM/Mirage` beside the photos, on the phone or the card; the
  last-shot ring plays the last clip.
- Sound is asked for the first time video is chosen; without it, clips are silent.

## 0.3.3

- Photos and variants go to `DCIM/Mirage` again, where cameras keep theirs.
- A choice in the camera's card: the phone's memory or the memory card
  (Android 10 and newer, when a card is in). Without the card, photos fall
  back to the phone and the diary says so.

## 0.3.2

- A diary: one plain text file a day in `Documents/Mirage`, with every camera
  of the phone as it describes itself, the chosen camera, every tap, lock,
  preset and frame move, every shot with its path, crop and turns, what the
  camera says it did, the darkroom's readings and timings, and every error,
  a crash included.

## 0.3.1

- Everything is saved by itself: the shot and every variant as soon as it is
  developed, all in `Pictures/Mirage`.
- In the darkroom, Keep gave way to Delete; Edit opens the picture on screen.

## 0.3

- The view shows three quarters of the photo's zoom; the photo is a frame
  inside it, moved with a finger and left where the finger left it. The view
  around the frame is dimmed, the thirds grid lies inside the frame.
- When the camera can crop anywhere on its sensor, the frame is cut by the
  camera itself and loses nothing; when it can only crop about the centre,
  the whole view is taken and the frame cut afterwards.
- Tap to focus and meter on a point; press and hold to lock focus and
  exposure there; double tap to bring the frame back and give focus back.
- Stills ask the camera for its best noise reduction and sharpening.
- Every shot is saved at once and then developed in the background into
  variants with the photo editor's own light and geometry: Fix, Bright,
  Level, and Fix with the verticals stood up. The last-shot ring opens them;
  keep the ones you like, or open one in an editor.
- The grid switch moved into the camera's card, with a line on the gestures.

## 0.2

- A new look, shared with the author's other apps: a warm near-black ground,
  paper-colored type, a serif title that shows the zoom, brass rings and a
  cinnabar shutter.
- The viewfinder is a rounded card that keeps its 3:4 shape on any screen.
- A rule-of-thirds grid over the viewfinder; hold the picture to hide or show it.
- The screen is lit by the scene: a blurred glow of the viewfinder sits behind
  the controls.
- Live shutter speed, ISO and aperture on the viewfinder.
- The last photo in a ring; tap it to open the photo, or Album for the gallery.
- White balance as a drawn sign with its name under it.
- Tap the app's sign at the top for the camera's facts: photo size, sensor
  size, and whether the full sensor resolution is open to the app.

## 0.1

The first build.

- Shoots on the main rear lens only; zoom never goes below 1×.
- Opens at the default preset, 2× out of the box.
- Presets with zoom, exposure and white balance: add, save into, rename,
  delete, make default.
- Pinch to zoom, double tap for the default preset.
- Shutter on screen, on the hardware camera key and on the volume keys.
- Photos in `DCIM/Mirage`; Android 8 and newer.
