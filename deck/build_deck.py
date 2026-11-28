#!/usr/bin/env python3
"""
Draws the complete Hot Death Uno Redux deck with the Card Workshop.

Reads every card from engine/Types.kt (plain cards, wilds and all specials),
draws each one with the Card Workshop's own renderer (headless Chromium via
Playwright) using clip art from game-icons.net (CC BY 3.0), and writes
card_<key>.png into every drawable density folder, plus card_back.png and
family-friendly (_ff) variants. CardText.artwork() finds them by name.

Usage:
    mkdir -p /tmp/gi && (cd /tmp/gi && npm pack game-icons-transparent && tar xzf game-icons-transparent-*.tgz)
    python3 deck/build_deck.py /tmp/gi/package/svgs path/to/hotdeath
"""
import base64, io, json, re, subprocess, sys, tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOOL = HERE.parent / "card-designer.html"

FACE_KEY = {"ZERO": "0", "ONE": "1", "TWO": "2", "THREE": "3", "FOUR": "4", "FIVE": "5", "SIX": "6",
            "SEVEN": "7", "EIGHT": "8", "NINE": "9", "SKIP": "s", "REVERSE": "r", "DRAW_TWO": "d",
            "WILD": "wild", "WILD_DRAW_FOUR": "wild_drawfour"}
CORNER = {"s": "S", "r": "R", "d": "+2", "wild": "", "wild_drawfour": "+4"}
COLOR_HEX = {"RED": "#cb0d28", "GREEN": "#048540", "BLUE": "#0456a5", "YELLOW": "#e99206", "WILD": "#1d1b1a"}
INK = "#1d1b1a"

# special -> (title ("\n" = two lines), picture, extra design options).
# A picture is an icon name drawn in one color, or a list of layers for multicolor art:
#   ("icon", name, color[, scale, dx, dy])   a game-icons.net picture
#   ("disc", radius, fill[, stroke, width])  a filled circle
#   ("rainbow",)                             rainbow arcs
INK, GOLD, NAVY = "#2b2622", "#e0a800", "#1b3a6b"
ART = {
    "sixty_nine":        ("", None, {"center": "69", "mash69": True, "centerSize": 70}),
    "aids":              ("AIDS", [("disc", 236, "#f1c40f", INK, 18), ("icon", "biohazard", INK, 0.78)], {"ff": "Cooties"}),
    "backstab":          ("Backstab", "backstab", {"color": INK}),
    "blue_shield":       ("Blue Shield", "checked-shield", {"color": "#1f6fd1"}),
    "clone":             ("Clone", [("icon", "dna2", "#8e44ad", 0.78, -50, -30), ("icon", "dna2", "#e84393", 0.78, 50, 30)], {}),
    "communism":         ("Communism", [("disc", 236, "#c0392b"), ("icon", "hammer-sickle", "#f1c40f", 0.66)], {}),
    "conquest":          ("Conquest", "crown", {"color": GOLD}),
    "cure":              ("Cure", "medicines", {"color": "#e84393"}),
    "death":             ("Death", "grim-reaper", {"color": INK}),
    "delayed_blast":     ("Delayed\nBlast", [("icon", "explosion-rays", "#f39c12"), ("icon", "time-bomb", INK, 0.62)], {}),
    "dodge":             ("Dodge", "dodging", {"color": "#16a085"}),
    "double_skip":       ("Double\nSkip", "next-button", {}),
    "famine":            ("Famine", "scales", {"color": "#8e5a2b"}),
    "ftpip":             ("F.T.P.I.P.", "pointing", {"color": "#e67e22"}),
    "fuck_you":          ("Fuck You", "shield-reflect", {"color": "#c0392b", "ff": "Forget You"}),
    "gift":              ("Gift", "present", {"color": "#c0392b"}),
    "glasnost":          ("Glasnost", "eye-target", {"color": "#6b2fa0"}),
    "gold_coin":         ("Gold Coin", [("disc", 220, "#f1c40f", "#b8860b", 26), ("icon", "crown-coin", "#b8860b", 0.62)], {}),
    "harvester":         ("Harvester\nof Sorrows", "reaper-scythe", {"color": "#6b2fa0"}),
    "holy_defender":     ("Holy\nDefender", [("disc", 236, "#fbe7a1"), ("icon", "holy-symbol", "#1f6fd1", 0.78)], {}),
    "hot_death":         ("Hot Death", [("icon", "fire", "#e8590c", 1.08, 0, -24), ("icon", "death-skull", INK, 0.6, 0, 46)], {}),
    "i_dont_like_these": ("I Don't\nLike These", "card-discard", {"color": "#6b2fa0"}),
    "luck":              ("Luck", "clover", {"color": "#27ae60"}),
    "mad":               ("M.A.D.", [("disc", 236, INK, "#c0392b", 22), ("icon", "radioactive", "#f1c40f", 0.8)], {}),
    "magic_5":           ("Magic 5", [("icon", "sparkles", "#f1c40f"), ("icon", "crystal-wand", "#8e44ad", 0.8, 10, 10)], {}),
    "martyr":            ("Martyr", "angel-wings", {"color": GOLD}),
    "mystery_draw":      ("Mystery\nDraw", "perspective-dice-six-faces-random", {"color": "#8e44ad"}),
    "necromancer":       ("Necromancer", "raise-skeleton", {"color": "#4a235a"}),
    "penn_state":        ("Penn State", "lion", {"color": NAVY}),
    "ping":              ("Ping", "radar-sweep", {"color": "#16a085"}),
    "plague":            ("Plague", "plague-doctor-profile", {"color": INK}),
    "prophet":           ("Prophet", [("disc", 236, "#d6c5f0"), ("icon", "third-eye", "#6b2fa0", 0.82)], {}),
    "quitter":           ("Quitter", "exit-door", {"color": "#8e5a2b"}),
    "recycler":          ("Recycler", "recycle", {"color": "#27ae60"}),
    "reverse_skip":      ("Reverse\nSkip", "return-arrow", {}),
    "spreader":          ("Spreader", "expanded-rays", {}),
    "swap":              ("Swap", "card-exchange", {}),
    "taste_the_rainbow": ("Taste the\nRainbow", [("rainbow",)], {}),
    "chosen_one":        ("The Chosen\nOne", [("icon", "laurel-crown", "#27ae60"), ("icon", "crown", GOLD, 0.42, 0, -40)], {}),
    "flood":             ("The Flood", "flood", {"color": "#1f6fd1"}),
    "virus":             ("Virus", "virus", {"color": "#27ae60"}),
    "war":               ("War", [("icon", "crossed-swords", "#7f8c8d"), ("icon", "drop", "#c0392b", 0.3, 0, 150)], {}),
    "yin":               ("Yin", "yin-yang", {"shape": "none", "color": "#f6f4ef", "artScale": 110}),
    "yang":              ("Yang", "yin-yang", {"rotate": True, "shape": "none", "color": "#f6f4ef", "artScale": 110}),
}
# One-color pictures on cards that come in every color: pick a color that isn't the card's.
PER_CARD_COLOR = {"next-button": ("#e67e22", "#6b2fa0"), "return-arrow": ("#6b2fa0", "#e67e22"),
                  "expanded-rays": ("#e67e22", "#6b2fa0"), "card-exchange": ("#1f6fd1", "#8e44ad")}


def specials(types_kt):
    src = types_kt.read_text()
    block = src[src.index("enum class Special("):src.index("val key: String get() = name.lowercase()")]
    for m in re.finditer(r"^\t([A-Z0-9_]+)\(Face\.([A-Z_]+), (listOf\(([^)]*)\)|CardColor\.SUITS), Category\.([A-Z]+)", block, re.M):
        name, face, colors_raw, color_list, cat = m.groups()
        colors = ["RED", "GREEN", "BLUE", "YELLOW"] if colors_raw == "CardColor.SUITS" else \
            list(dict.fromkeys(c.strip().replace("CardColor.", "") for c in color_list.split(",")))
        yield name.lower(), face, colors, cat.capitalize()


class Icons:
    def __init__(self, svgdir):
        self.svgdir, self.used = Path(svgdir), {}

    def get(self, icon, color, rotate=False):
        hits = list(self.svgdir.glob(f"*/{icon}.svg"))
        if not hits:
            sys.exit(f"icon not found: {icon}")
        svg = re.sub(r'fill="(#[0-9a-fA-F]{3,6}|currentColor)"', f'fill="{color}"', hits[0].read_text())
        if rotate:
            svg = re.sub(r"(<svg[^>]*>)", r'\1<g transform="rotate(180 256 256)">', svg, count=1).replace("</svg>", "</g></svg>")
        self.used[icon] = hits[0].parent.name
        return "data:image/svg+xml;base64," + base64.b64encode(svg.encode()).decode()

    def inner(self, icon, color):
        hits = list(self.svgdir.glob(f"*/{icon}.svg"))
        if not hits:
            sys.exit(f"icon not found: {icon}")
        self.used[icon] = hits[0].parent.name
        svg = re.sub(r'fill="(#[0-9a-fA-F]{3,6}|currentColor)"', f'fill="{color}"', hits[0].read_text())
        return svg[svg.index(">") + 1:svg.rindex("</svg>")]

    def compose(self, layers):
        """Stacks layers (see ART) into one 512x512 SVG picture."""
        parts = []
        for L in layers:
            if L[0] == "icon":
                _, name, color, *rest = L
                sc, dx, dy = (list(rest) + [1, 0, 0][len(rest):])[:3]
                parts.append(f'<g transform="translate({256 + dx} {256 + dy}) scale({sc}) translate(-256 -256)">{self.inner(name, color)}</g>')
            elif L[0] == "disc":
                _, r, fill, *stroke = L
                st = f' stroke="{stroke[0]}" stroke-width="{stroke[1]}"' if stroke else ""
                parts.append(f'<circle cx="256" cy="256" r="{r}" fill="{fill}"{st}/>')
            elif L[0] == "rainbow":
                bands = ["#e74c3c", "#e67e22", "#f1c40f", "#27ae60", "#1f6fd1", "#8e44ad"]
                for i, c in enumerate(bands):
                    r = 236 - i * 34
                    parts.append(f'<path d="M{256 - r} 360 A{r} {r} 0 0 1 {256 + r} 360" fill="none" stroke="{c}" stroke-width="35"/>')
        svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">{"".join(parts)}</svg>'
        return "data:image/svg+xml;base64," + base64.b64encode(svg.encode()).decode()


# Wild cards: the plain Wild's white panel (66x96, as on the colored cards), corners inset a little.
WILD_LAYOUT = dict(shape="square", shapeW=66, shapeH=96, shapeCY=80, corner="Wild", cornerX=12, cornerY=13,
                   cornerSize=13, titleSize=13, captionY=130, artFit=50, artScale=100)


HOT_DEATH = dict(corner="", title="Hot Death", caption="", font="Metal Mania", titleSize=25, titleY=26,
                 titleColor="fire", titleStroke="#c0161a", mirrorTitle=True, titleDither=85,
                 shape="circle", shapeW=89, shapeH=90, shapeCY=82.5, shapeFill="#dad9d4", shapeScale=100,
                 center="Wild", centerColor="rainbow", centerFont="Source Sans 3:900", noArt=True)


def _close(a, b):
    import colorsys
    h = [colorsys.rgb_to_hsv(*(int(c[i:i + 2], 16) / 255 for i in (1, 3, 5)))[0] for c in (a, b)]
    return min(abs(h[0] - h[1]), 1 - abs(h[0] - h[1])) * 360 < 40


def base_design(key, color):
    return {"id": key, "color": color, "corner": "", "shape": "square", "shapeScale": 100, "dither": 40, "edgeWear": 75,
            "border": False, "center": "", "centerColor": "auto", "centerSize": 51, "title": "", "caption": "",
            "captionColor": "#ffffff", "font": "Source Sans 3:900", "titleSize": 15, "artScale": 78, "artX": 0, "artY": 0}


def designs(types_kt, icons):
    out = []
    # plain colored cards
    for col in ["RED", "GREEN", "BLUE", "YELLOW"]:
        for fk in ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "s", "r", "d"]:
            key = f"{col.lower()}_{fk}"
            d = base_design(key, col)
            d["corner"] = CORNER.get(fk, fk)
            d["center"] = {"d": "+2", "s": "S", "r": "R"}.get(fk, fk)
            out.append((key, d))
    # plain wilds
    d = base_design("wild", "WILD")
    d.update(WILD_LAYOUT, center="Wild", centerColor="rainbow", centerFont="Source Sans 3:900")
    out.append(("wild", d))
    d = base_design("wild_drawfour", "WILD")
    d.update(WILD_LAYOUT, center="+4", centerColor="rainbow", centerFont="Source Sans 3:900", title="Draw", caption="Four")
    out.append(("wild_drawfour", d))
    # specials
    for sp, face, colors, cat in specials(types_kt):
        title, icon, extra = ART[sp]
        fk = FACE_KEY[face]
        wild = fk.startswith("wild")
        for col in (["WILD"] if wild else colors):
            key = (f"{col.lower()}_" if not wild else "") + f"{fk}_{sp}"
            d = base_design(key, col)
            d.update(corner="69" if sp == "sixty_nine" else CORNER.get(fk, fk), title=title, caption="")
            if wild:
                # "Wild" sits in the corners; the name is split between the top and bottom of the panel.
                first, _, second = title.partition("\n") if "\n" in title else title.partition(" ")
                d.update(WILD_LAYOUT, title=first, caption=second)
                if not second:          # one-word names (Yin, Yang) get bigger lettering
                    d.update(titleSize=17, titleY=40)
                if sp == "hot_death":
                    # after the original card: fiery "Hot Death" banners top and bottom (dithered like
                    # print), and its grey-white circle with the rainbow "Wild"
                    d.update(HOT_DEATH)
            if d.pop("noArt", False):
                icon = None
            if isinstance(icon, list):
                d["art"] = icons.compose(icon)
            elif icon:
                color = extra.get("color") or PER_CARD_COLOR[icon][0]
                if icon in PER_CARD_COLOR and not wild and _close(color, COLOR_HEX[col]):
                    color = PER_CARD_COLOR[icon][1]
                d["art"] = icons.get(icon, color, extra.get("rotate"))
            if d.get("art"):
                d["artShadow"] = True
            if "\n" in title and not wild:
                d.update(artScale=70, artY=8)   # make room for the second title line
            d.update({k: v for k, v in extra.items() if k in ("center", "centerSize", "shape", "artScale", "mash69")})
            out.append((key, d))
            if "ff" in extra:
                out.append((key + "_ff", dict(d, id=key + "_ff", title=extra["ff"])))
    # card back
    d = base_design("back", "WILD")
    # after the original back: a tilted "HD" (yellow, cracked), here on a red oval tilted the same way;
    # dithered like the rest of the deck
    # the original back's off-white border: about 8px, inner panel corners about 3px, a bright rim inside
    d.update(border=True, borderWidth=8, borderColor="#dad4cb", borderRadius=3, borderRim="#faf7ef",
             shape="oval", shapeW=92, shapeH=58, shapeRotate=-35, shapeFill="#cb0d28",
             logo=dict(text="HD", font="Bungee", size=39, stretch=0.72, rotate=-35, tracking=-2, fill="#ffc800",
                       stroke="#1d1b1a", strokeW=2.8, cracks=0, cy=80, maxW=66))
    out.append(("back", d))
    return out


def main():
    svgdir = sys.argv[1]
    if len(sys.argv) < 3:
        sys.exit("usage: build_deck.py <game-icons svgs dir> <hotdeath checkout>")
    root = Path(sys.argv[2]).resolve()
    res = root / "app/src/main/res"
    icons = Icons(svgdir)
    cards = [{"key": k, "design": d} for k, d in designs(root / "app/src/main/java/com/smorgasbork/hotdeath/engine/Types.kt", icons)]
    densities = [("drawable-mdpi", 52, 80), ("drawable-hdpi", 77, 120), ("drawable-xhdpi", 103, 160),
                 ("drawable-xlarge-mdpi", 77, 120), ("drawable-xlarge-hdpi", 103, 160)]
    job = Path(tempfile.mkdtemp()) / "job.json"
    job.write_text(json.dumps({"cards": cards, "densities": densities}))
    out = subprocess.run(["node", str(HERE / "render.js"), str(TOOL), str(job)],
                         check=True, capture_output=True, text=True).stdout
    from PIL import Image
    for r in json.loads(out):
        img = Image.open(io.BytesIO(base64.b64decode(r["png"].split(",", 1)[1]))).convert("RGBA")
        # The original card PNGs were reduced to 128 colors; do the same (already dithered).
        img = img.quantize(colors=128, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE)
        img.save(res / r["dir"] / f"card_{r['key']}.png", optimize=True)
    print(f"wrote {len(cards)} cards x {len(densities)} densities")

    lines = ["# Card artwork credits", "",
             "The card faces are drawn by the Card Workshop. Their pictures come from",
             "[game-icons.net](https://game-icons.net), licensed under",
             "[CC BY 3.0](https://creativecommons.org/licenses/by/3.0/), recolored and placed on the cards.", "",
             "| Icon | Artist |", "|---|---|"]
    for icon, author in sorted(icons.used.items()):
        lines.append(f"| [{icon}](https://game-icons.net/1x1/{author}/{icon}.html) | {author} |")
    (root / "docs/ARTWORK_CREDITS.md").write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
