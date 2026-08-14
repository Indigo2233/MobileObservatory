# Hardware smoke tests

Run these checks before a release that changes device, permission, or preview code.
Record Android version, device model, accessory firmware, result, and relevant logs.

## Camera

- Connect each supported camera family and start preview.
- Change exposure, gain, pixel format, readout mode, ROI, flip, and rotation.
- Confirm the gain control shows the vendor-native value (not dB as the primary number).
  Enter an exact gain, drag the slider, and for Player One tap HDR/HCG/Unity/lowest-noise presets.
  After pixel-format and readout-mode changes, confirm the gain range and current value refresh.
- Capture JPG and FITS; record and reopen SER, PSER, and MP4.
  Confirm FITS `GAIN` is native (not labelled dB). If a dB conversion exists, `GAINDB` is present.
- Leave preview running for 30 minutes. Record `Preview baseline` Logcat values,
  visible tearing, input latency, device temperature, and any reconnect failure.
- Disconnect and reconnect after Activity recreation.

## Mount

Repeat for one OnStep/LX200, iOptron V3, and SkyWatcher/SynScan mount where available.

- Connect through every supported transport for that mount.
- Read coordinates and site, sync site, change slew rate, and tracking.
- Start GOTO, manual movement, fixed-distance RA movement, and home movement.
- Press global STOP from camera, mount, star map, guiding, and player pages.
- Disconnect during motion and confirm the physical mount stops.

## OnStep Bluetooth

Test on Android 12, 13, 14, and 15 where available.

- Grant nearby-device permissions and connect using standard RFCOMM.
- Use an HC-05/HC-06 style module that requires insecure RFCOMM fallback.
- Cancel during standard and compatible connection stages.
- Turn Bluetooth off, remove pairing, deny permission, and connect to an
  unresponsive device; verify distinct messages and no indefinite spinner.
- Read coordinates after a successful connection.

## Guiding and accessories

- Connect the main camera and guide camera independently in both orders.
- Detect and lock stars, calibrate, guide, stop, and clear calibration.
- Connect focuser, filter wheel, cover/calibrator, and rotator independently.
- Recreate the Activity and confirm every previously disconnected device can reconnect.

## Accessibility and UI

- Enable TalkBack and complete Bluetooth connect/cancel, star-map back, GOTO,
  and global STOP actions.
- Repeat critical flows at 200% font scaling and in landscape on a small screen.
