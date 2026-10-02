#!/usr/bin/env python3
"""Frame phone captures for the Google Play listing (MESHSAT-1346).

Each capture is placed on a 9:16 Space Black canvas under one headline, set in the app's own
typeface. Google's rules for listing screenshots (Play Console Help, answer 9866151, read
2 Oct 2026) that this layout follows:

  - "JPEG or 24-bit PNG (no alpha)", 320 to 3840 px, and "the maximum dimension of your
    screenshot can't be more than twice as long as the minimum dimension". The phone's
    1080 x 2424 is longer than that, hence the canvas.
  - "9:16 for portrait screenshots (minimum 1080x1920px)".
  - "Taglines should not take up more than 20% of the image": HEADLINE_BAND is 16% of the
    height and the capture starts below it.
  - No device imagery: the capture gets rounded corners and a hairline, never a phone frame.
  - "Screenshots must demonstrate the actual in-app or in-game experience": the capture is
    cropped to the app (Android's status bar and gesture pill go) and scaled. The only
    retouching is a blur over a private detail, listed per capture in SHOTS.

Usage:
  frame-play-screenshots.py <captures dir> <output dir>

The captures dir holds the raw `adb exec-out screencap -p` files named in SHOTS below. They
stay out of the repo: the raw Home capture shows a name. Output files are 1.png, 2.png, ... in
the order of SHOTS, which is the order of the listing. Needs Pillow.

The set of 2 Oct 2026 was taken on the owner's phone, which runs the full edition, so Home
shows the SMS lane the Google Play edition does not have (owner's decision that day).
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# (capture file, headline lines, alt text for the Console's alt text field, boxes to blur)
# A box is (left, top, right, bottom) in the capture's own pixels and hides a private detail,
# here the emergency contact's name on Home's SOS card. The boxes belong to one capture: a
# retake needs them measured again.
SHOTS = [
    ("home.png", ["One message.", "Every way out."],
     "MeshSat home screen with the satellite, mesh and Hub routes and the SOS button",
     [(885, 1772, 950, 1822), (65, 1826, 246, 1876)]),
    ("people.png", ["Everyone your node", "can hear."],
     "People screen listing the mesh nodes heard, with signal, battery and last heard", []),
    ("passes.png", ["Know when a satellite", "is overhead."],
     "Satellite passes screen with the satellite overhead now and the signal and pass chart", []),
    ("map.png", ["Everyone you hear,", "on one map."],
     "Map with the phone's position and a mesh node", []),
    ("node.png", ["One node in your pocket:", "LoRa mesh and Iridium."],
     "Your MeshSat node screen with the Bluetooth connection, battery and mesh nodes", []),
]

W, H = 1440, 2560                       # 9:16
HEADLINE_BAND = H * 16 // 100           # Google's ceiling for a tagline is 20%
BOTTOM_MARGIN = 72
CROP_TOP = 128                          # Android's own status bar (clock, battery) on 1080 x 2424
CROP_BOTTOM = 44                        # the gesture pill
RADIUS = 44
SPACE_BLACK = (4, 4, 6)                 # ui/theme/Color.kt SpaceBlack
OFF_WHITE = (247, 247, 244)             # OffWhite
SIGNAL_ORANGE = (249, 97, 24)           # SignalOrange
BORDER = (58, 58, 68)                   # Theme.kt outline

FONT = Path(__file__).resolve().parent.parent / "app/src/main/res/font/plex_sans_semibold.ttf"


def headline(draw: ImageDraw.ImageDraw, lines: list[str]) -> None:
    size = 104
    while True:
        font = ImageFont.truetype(str(FONT), size)
        widest = max(draw.textlength(line, font=font) for line in lines)
        line_height = int(size * 1.18)
        if widest <= W - 2 * 120 and line_height * len(lines) <= HEADLINE_BAND - 130:
            break
        size -= 4
    y = (HEADLINE_BAND - line_height * len(lines)) // 2 + 24
    for i, line in enumerate(lines):
        # The first line is the claim, the second its turn: orange carries the turn.
        colour = OFF_WHITE if i == 0 else SIGNAL_ORANGE
        draw.text(((W - draw.textlength(line, font=font)) // 2, y), line, font=font, fill=colour)
        y += line_height


def frame(capture: Path, lines: list[str], out: Path, blur: list[tuple] = ()) -> None:
    canvas = Image.new("RGB", (W, H), SPACE_BLACK)
    draw = ImageDraw.Draw(canvas)
    headline(draw, lines)

    shot = Image.open(capture).convert("RGB")
    for box in blur:
        shot.paste(shot.crop(box).filter(ImageFilter.GaussianBlur(14)), box)
    shot = shot.crop((0, CROP_TOP, shot.width, shot.height - CROP_BOTTOM))
    target_h = H - HEADLINE_BAND - BOTTOM_MARGIN
    target_w = round(shot.width * target_h / shot.height)
    shot = shot.resize((target_w, target_h), Image.LANCZOS)

    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, target_w - 1, target_h - 1), RADIUS, fill=255)
    x, y = (W - target_w) // 2, HEADLINE_BAND
    canvas.paste(shot, (x, y), mask)
    draw.rounded_rectangle((x - 2, y - 2, x + target_w + 1, y + target_h + 1), RADIUS + 2,
                           outline=BORDER, width=3)
    canvas.save(out, "PNG", optimize=True)      # RGB: 24-bit, no alpha


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    dst.mkdir(parents=True, exist_ok=True)
    missing = [name for name, _, _, _ in SHOTS if not (src / name).exists()]
    if missing:
        print("missing captures:", ", ".join(missing))
        return 1
    for n, (name, lines, alt, blur) in enumerate(SHOTS, start=1):
        frame(src / name, lines, dst / f"{n}.png", blur)
        print(f"{n}.png  {name:12} {' '.join(lines)}  | alt: {alt}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
