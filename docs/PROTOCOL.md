# Sony A6400 over USB: what SimpleAlphaDroid uses

Findings from testing an A6400 (ILCE-6400) in **USB Connection: PC Remote**, from observing the USB traffic of
Sony's Imaging Edge Desktop Remote, and from open-source projects (libgphoto2, alphawire, darkgrade and others).
Only the A6400 was tested; other Sony bodies of the same generation (SDIO protocol v200) may behave similarly.
All codes are hex. "Prop" = device property read with 0x9209, "control" = button/action sent with 0x9207.

## Camera settings

| Setting | Value | Why |
|---|---|---|
| Setup › USB Connection | **PC Remote** | Mass Storage only mounts the card; MTP gives card access without remote control |
| Network › Ctrl w/ Smartphone | Off | |
| Focus › AF w/ shutter | **On** | The remote half-press (S1) obeys it; with Off, S1 doesn't focus |
| PC Remote Settings › Still Img. Save Dest. | PC+Camera | PC Only: nothing reaches the card. Prop 0xD222 (read-only over USB) allows only 0x11 (both) and 0x01 (PC): there is no card-only option on this body |
| Setup › USB Power Supply | Off | Otherwise the camera draws power from the phone |
| Focus Area | Flexible Spot | Needed to move the focus point (also settable over USB, see Focus) |

## USB and session

- USB id `054C:0CAA` in PC Remote, `054C:0CA9` in MTP. Interface class 6, bulk IN 0x81 / OUT 0x02, interrupt 0x83.
- Init: OpenSession → 0x9201 [1,0,0] → 0x9201 [2,0,0] → 0x9202 [300] (ext. device info; the camera answers version
  **200**) → 0x9201 [3,0,0] → 0x9209 (all props). Adding the "extended" flag (`[300,1]`) lists nothing more.
- The 0x9209 layout is v200: each enum lists its values once. The "settable" byte is unreliable (see focus mode).
- **Never send 0x9201 in MTP mode**: the link jams until a replug. Check GetDeviceInfo for 0x9201 first.
- **DeviceReset (class request 0x66) ends the session**: GetDeviceInfo still answers, every other op then fails with
  a bulk OUT error. Recovery = clear endpoint halts + 0x66, then close, reopen and redo the whole init.
- Some Android phones (MediaTek) fail large USB reads instantly; reading at most 16 KB per bulk transfer works.
- GetPartialObject 0x101B made the camera drop off USB. GetStorageInfo in PC Remote mode did too.

## Live view

- GetObject on handle **0xFFFFC002** → dataset: u32 JPEG offset, u32 JPEG size, then a 1024×680 JPEG (~115 KB),
  up to ~25 fps. AccessDenied (0x200F) = next frame not ready, retry quickly.
- The 1012 bytes before the JPEG are not decoded. Only bytes 4–6 change (every frame, likely the JPEG size): not when
  the focus point moves, and not while Tracking follows a subject. The A6400 sends no focus-frame info there (newer
  bodies put focus frames at offset/size fields 8–15).
- 0xD26A live-view quality: set answers OK, no effect.

## Focus

| What | How |
|---|---|
| Focus point | Set prop **0xD232** = `x<<16 \| y` with 0x9205 (Flexible Spot). x 0..639 spans the picture width; y uses the **same unit** from the top, so a 3:2 picture is ~426 tall (not 480). Ignored in MF. |
| Half-press / full press | Controls **0xD2C1** (S1) / **0xD2C2** (S2): value 2 = down, 1 = up |
| Focus state | Prop **0xD213**: 1 idle, 2 focused (AF-S), 3 failed (AF-S), 5 focusing, 6 focused (AF-C), 7 failed (AF-C) |
| Focus mode | Prop 0x500A (1 MF, 2 AF-S, 0x8004 AF-C, 0x8005 AF-A, 0x8006 DMF). Marked read-only, but a plain 0x9205 set works. |
| Back-button focus | Control **0xD2D2** (AF/MF hold) held down = camera in MF, shutter doesn't refocus. AF-ON = release the hold + S1 down; letting go = S1 up + hold down. The hold **survives a USB disconnect**: release it on connect. |
| Focus area | Prop **0xD22C**, plain 0x9205 set: 1 Wide, 2 Zone, 3 Center, 0x101/0x102/0x103 Flexible Spot S/M/L, 0x104 Expand Flexible Spot. **In AF-C only** it also allows 0x201–0x207 = Tracking (see below). |
| Tracking | Set 0xD22C to 0x201–0x207 in AF-C; the order is assumed from Sony's menu (Wide, Zone, Center, Flexible Spot S/M/L, Expand), 0x204 = Tracking: Flexible Spot S verified. The focus point (0xD232) sets the start, S1 held tracks, and the camera's own box follows the subject. **The tracked position is not reported**: 0xD232 stays at the start point and the live-view header doesn't change. Switching to AF-S drops the camera back to the plain Flexible Spot. |
| Manual focus | Control 0xD2D1, i16 ±1..7 (near/far), when prop 0xD235 = 1 (MF or the hold above) |
| Magnifier | Control **0xD2CB** steps: frame → 5.9× → 11.7× → off. 0xD2CC exits. 0xD2CD/CE/CF/D0 move the area up/down/left/right. Props 0xD22D (0 off, 1 frame, 2 magnified), 0xD22F (ratio ×10), 0xD230 (position). Refused in AF-C. |

Not available on the A6400 over USB: an AF-ON control (0xD309 button function answers OK but doesn't focus), touch
focus (0xD2E4), the 0xD2DC focus-area control, focus steps 0xD2D7/D8. The Bluetooth remote's AF-ON acts like a
half-press too (follows AF w/ shutter), so it doesn't give real back-button focus either.

## Shooting and getting the files

- Shoot: S1 down, wait for 0xD213 = 2/6 (or 3/7 = failed), up to ~2 s, then S2 down, ~100 ms, S2 up, S1 up. A fixed
  short delay makes the camera skip the shot when it hasn't focused yet (focus priority).
- In PC Remote the camera **keeps every shot for the host**, also ones taken with its own button. Prop **0xD215**:
  bit 15 = shots waiting, bits 0–14 = count (0xFFFF = no info). With two RAW shots waiting it refuses to focus or
  fire. Download each with GetObjectInfo + GetObject on handle **0xFFFFC001** (25 MB ARW in ~0.8 s); that frees it.
- Events (interrupt endpoint): 0xC201 ObjectAdded (p1 = 0xFFFFC001, ~0.5 s after the shot), 0xC202 ObjectRemoved
  (after the download), 0xC203 property changed (p1 = prop code).

### Drive mode and burst

- Prop **0x5013** drive mode is settable with a plain 0x9205 (u16). 50 values are offered. Speeds below were measured
  by holding S2 for ~1.9 s and counting the files:

  | Value | Mode | Measured |
  |---|---|---|
  | 0x0001 | Single | |
  | 0x8012 | Continuous Lo | 3 fps |
  | 0x8015 | Continuous Mid | 6 fps |
  | 0x0002 | Continuous Hi | 8 fps |
  | 0x8010 | Continuous Hi+ | 11 fps |

  Other values offered (names from libgphoto2's Sony table, not checked on the camera screen): 0x8003–0x8005
  self-timers, 0x8008/0x8009 self-timer with several shots, 0x800C–0x800F continuous self-timer, 0x83xx/0x85xx/0x89xx
  exposure brackets (3/5/9 frames, low byte = step), 0x8018/0x8028 white-balance bracket, 0x8019/0x8029 DRO bracket.
- **Burst = S2 held in a continuous mode.** The camera fills its own buffer at full speed and never refuses: 21 RAW
  in 2.2 s with 0xD215 counting up to 17. The two-waiting-RAW limit above applies to single shots only. Files are
  downloaded the same way afterwards (~0.85 s per 25 MB ARW incl. saving). Don't run a GetObject while S2 is held:
  it holds the USB link for ~0.8 s and delays S2 up, so the camera keeps firing (~10 extra frames at Hi+).
- Prop 0xD24F "Interval Shooting" (1/2) is settable; not tested.

## Exposure and locks

- Shutter 0xD20D, aperture 0x5007, ISO 0xD21E and exposure compensation 0x5010 can't be set directly on this body.
  Step them like Imaging Edge does: 0x9207 on the prop code with +1 / −1 (2 bytes `01 00` / `FF FF`; 4 bytes for ISO).
  ISO Auto is one step below the lowest ISO.
- Value formats: shutter `num<<16|den` (0 = bulb), aperture ×100, exposure compensation ×1000 (signed), ISO in the
  low 24 bits (0xFFFFFF = Auto).
- Locks are controls held down: AEL 0xD2C3 (indicator 0xD217), FEL 0xD2C9 (0xD21F), AWB lock 0xD2D9 (0xD24E);
  indicators are 1 off / 2 locked. Movie record 0xD2C8 toggles (state 0xD21D).

## MTP mode (card access)

- 24 ops, no SDIO ops, no DeleteObject; storage is read-only. Date folders hold the files (ARW format 0xB101, MP4
  0xB982). Card file names differ from PC Remote downloads (`_DSC5231.ARW` vs `DSC05231.ARW`).
