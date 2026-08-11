# WandererAstro accessory support plan

## Scope and delivery order

This plan covers direct Android USB-serial support for WandererAstro accessories.
The application implements the device protocol in Kotlin and keeps each accessory
behind its existing controller interface.

| Phase | Status | Scope | Completion criterion |
|---|---|---|---|
| 1 | In progress | WandererRotator Mini, Lite V1, Lite V2 | Automatic identification, connection, movement, stop, zero and reverse operate on hardware. |
| 2 | Planned | WandererCover V3, V4-EC, V4 Pro-EC | Cover state and flat-panel brightness are available through the cover controller. |
| 3 | Planned | Wanderer Snowflake filter wheel | Slot selection, calibration and supported slot-name handling are available. |
| 4 | Planned | WandererBox Plus V3 and Pro V3 | Power, USB, PWM and environment monitoring are available through an independent power controller. |

## Phase 1: WandererRotator

### Supported models

| Wire model | Product name | Steps per degree | Minimum firmware |
|---|---:|---:|---:|
| `WandererRotatorMini` | Mini | 1142 | 20240226 |
| `WandererRotatorLite` | Lite V1 | 1155 | 20240403 |
| `WandererRotatorLiteV2` | Lite V2 | 1199 | 20240226 |

### Protocol contract

- USB serial parameters: 19200 baud, 8 data bits, no parity, one stop bit.
- Handshake command: `1500001` followed by LF.
- Handshake response: `modelAfirmwareAmechanical-angle-millidegreesAbacklashAreversedA`.
- Relative movement command: `1000000 + signed_steps`.
- Mechanical zero: `1500002`.
- Reverse: `1700000` and `1700001`.
- Stop: `Stop`.

The adapter accepts the command encoding used by the maintained INDI driver and
the MIT-licensed WandererRotator SDK. Hardware validation remains mandatory,
because earlier protocol documentation shows a signed-step movement form.

### Integration requirements

- Identify the model before connecting a controller.
- Keep WandererRotator and the existing electric CAA protocol in separate adapters.
- Use a controller router so the UI and ViewModel retain one CAA control surface.
- Wait after opening a CH340/CH341 device; avoid DTR and RTS toggling during
  WandererRotator probing.
- Expose model-specific fixed steps per degree. Motor hold and board step-scale
  settings remain unavailable for WandererRotator.

### Tests and hardware acceptance

- Unit tests cover status parsing, firmware validation and command encoding.
- Verify Mini, Lite V1 and Lite V2 when hardware is available.
- Verify positive and negative rotation, stop during motion, reverse, zero,
  reconnect after unplugging, and input-voltage failure feedback (`NP`).

## Deferred accessory work

The following phases require raw status captures from the target hardware before
implementation begins: WandererCover, Snowflake filter wheel, WandererBox Plus
V3 and WandererBox Pro V3.

## References

- INDI WandererRotator sources, commit `3220880734c86daa9f626fea011c231850f5d1f6`.
- WandererRotator SDK, commit `15aec854d89c433ecd88fc3a7b83c9dcfb39d22c`.
- WandererCover V4-EC serial protocol, firmware 20250506.
