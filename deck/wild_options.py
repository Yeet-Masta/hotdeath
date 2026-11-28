#!/usr/bin/env python3
"""Renders the wild cards in a few alternative styles for comparison (preview only)."""
import base64, io, json, subprocess, sys, tempfile
from pathlib import Path
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
import build_deck as bd  # noqa: E402

svgdir, root, out = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
icons = bd.Icons(svgdir)
types_kt = root / "app/src/main/java/com/smorgasbork/hotdeath/engine/Types.kt"
wilds = [(k, d) for k, d in bd.designs(types_kt, icons) if d["color"] == "WILD" and k != "back" and not k.endswith("_ff")]
DARK = {"grim-reaper", "plague-doctor-profile", "reaper-scythe", "perspective-dice-six-faces-random"}


def layout(key, d):
    """Shared fixes: no circle, corners inset, title band above a shorter panel, icons fitted by outline."""
    d = dict(d, corner="Wild", cornerX=12, cornerY=13, cornerSize=13, caption="", artFit=44, artScale=100,
             shapeScale=100, titleSize=12)
    name = bd.ART.get(key.split("_", 2)[-1] if key.startswith("wild_drawfour_") else key[5:], ("",))[0]
    if key == "wild":
        d.update(title="", shape="square", shapeH=96, shapeCY=80)
    elif key == "wild_drawfour":
        d.update(title="Draw Four", titleY=48, shape="square", shapeH=64, shapeCY=92, center="+4", centerSize=40)
    else:
        two = "\n" in name
        d.update(title=name, titleY=53 if two else 48, shape="square", shapeH=64, shapeCY=92)
        if key.endswith(("_yin", "_yang")):
            d.update(shape="none", artFit=50)
    return d


OPTIONS = {
    "A  White panel": lambda k, d: d,
    "B  Four-color panel": lambda k, d: dict(d, panel="quad", artTint=None if k.endswith(("_yin", "_yang")) else "#ffffff",
                                             centerColor="#ffffff" if d.get("center") else d.get("centerColor")),
    "C  Open, four-color frame": lambda k, d: dict(d, shape="none", frame="rainbow",
                                                   artTint="#f6f4ef" if k.endswith(("_reaper", "_harvester")) else d.get("artTint")),
    "D  White panel, four-color ring": lambda k, d: dict(d, panel="ring"),
}

cards = []
for oname, f in OPTIONS.items():
    for k, d in wilds:
        dd = f(k, layout(k, d))
        dd["id"] = k
        cards.append({"key": f"{oname}|{k}", "design": dd})
job = Path(tempfile.mkdtemp()) / "job.json"
job.write_text(json.dumps({"cards": cards, "densities": [("x", 206, 320)]}))
res = json.loads(subprocess.run(["node", str(Path(__file__).with_name("render.js")), str(bd.TOOL), str(job)],
                                check=True, capture_output=True, text=True).stdout)
per = {}
for r in res:
    o, k = r["key"].split("|")
    per.setdefault(o, []).append(Image.open(io.BytesIO(base64.b64decode(r["png"].split(",", 1)[1]))).convert("RGBA"))
for o, ims in per.items():
    W, H = 206, 320
    sheet = Image.new("RGBA", (len(ims) * (W + 12) + 12, H + 24), (31, 74, 55, 255))
    for i, im in enumerate(ims):
        sheet.alpha_composite(im, (12 + i * (W + 12), 12))
    sheet.save(out / f"wild_option_{o.split()[0]}.png")
print([k for k, _ in wilds])
