#!/usr/bin/env python3
"""Renders a few card-back designs for comparison (preview only)."""
import base64, io, json, subprocess, sys, tempfile
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).parent))
import build_deck as bd  # noqa: E402

svgdir, out = sys.argv[1], Path(sys.argv[2])
icons = bd.Icons(svgdir)
base = bd.base_design("back", "WILD")
R, Y, G, B = "#cb0d28", "#e99206", "#048540", "#0456a5"
HD = dict(text="HD", font="Bungee", size=44, stretch=0.72, rotate=-35, tracking=-2, fill="fire", stroke="#e3120f",
          strokeW=3.5, cracks=3, dither=70, cy=80, maxW=74)
skull = icons.compose([("icon", "fire", "#e8590c", 1.08, 0, -24), ("icon", "death-skull", "#1d1b1a", 0.6, 0, 46)])

OPTIONS = {
    "1  Original, redrawn": dict(logo=HD),
    "2  Tilted red oval": dict(shape="oval", shapeW=104, shapeH=66, shapeRotate=-35, shapeFill=R,
                               logo=dict(HD, fill="#ffc800", stroke="#1d1b1a", strokeW=3, cracks=0)),
    "3  Four-color band": dict(band=dict(colors=[R, Y, G, B], width=40, rotate=-35, cy=80),
                               logo=dict(HD, stroke="#1d1b1a", strokeW=3.5)),
    "4  Hot Death banners": dict(title="Hot Death", font="Metal Mania", titleSize=22, titleY=24, titleColor="fire",
                                 titleStroke="#c0161a", mirrorTitle=True, titleDither=85, logo=dict(HD, size=40)),
    "5  Flaming skull": dict(art=skull, artFit=86, artScale=100, shape="none", artAlpha=0.55,
                             logo=dict(HD, size=46)),
    "6  Rounded letters": dict(logo=dict(HD, font="Titan One", size=46, stretch=0.78, tracking=-3, cracks=5)),
}
cards = [{"key": k, "design": {**base, "id": "back_" + k[:1], "shape": "none", "center": "", **v}} for k, v in OPTIONS.items()]
job = Path(tempfile.mkdtemp()) / "job.json"
job.write_text(json.dumps({"cards": cards, "densities": [("x", 206, 320)]}))
res = json.loads(subprocess.run(["node", str(Path(__file__).with_name("render.js")), str(bd.TOOL), str(job)],
                                check=True, capture_output=True, text=True).stdout)
orig = Path(sys.argv[3]) if len(sys.argv) > 3 else None
ims = [(r["key"], Image.open(io.BytesIO(base64.b64decode(r["png"].split(",", 1)[1]))).convert("RGBA")) for r in res]
if orig:
    ims.insert(0, ("0  Original", Image.open(orig).convert("RGBA").resize((206, 320), Image.LANCZOS)))
W, H, lab = 206, 320, 30
sheet = Image.new("RGBA", (len(ims) * (W + 14) + 14, H + lab + 20), (31, 74, 55, 255))
d = ImageDraw.Draw(sheet)
f = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 14)
for i, (k, im) in enumerate(ims):
    x = 14 + i * (W + 14)
    d.text((x, 8), k, fill=(235, 235, 225), font=f)
    sheet.alpha_composite(im, (x, lab))
sheet.save(out / "back_options.png")
