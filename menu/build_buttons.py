#!/usr/bin/env python3
"""
Writes main-menu button art in the original lettering (see glyphs.py).

    python3 build_buttons.py <res dir> multiplayer=MULTIPLAYER stats=STATISTICS

For each name=TEXT it writes btn_<name>.png and btn_<name>_outline.png (pressed)
at every density the other menu buttons use, plus a sel_btn_<name>.xml selector
next to them, like sel_btn_continue.xml.
"""
import sys
from pathlib import Path
import glyphs as G

DENSITIES = {"drawable-mdpi": (200, 50), "drawable-hdpi": (300, 75), "drawable-xhdpi": (400, 100),
             "drawable-xlarge-mdpi": (300, 75), "drawable-xlarge-hdpi": (400, 100)}
SELECTOR = """<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
     <item android:state_pressed="true"
           android:drawable="@drawable/btn_{n}_outline" /> <!-- pressed -->
     <item android:state_focused="true"
           android:drawable="@drawable/btn_{n}" /> <!-- focused -->
     <item android:drawable="@drawable/btn_{n}" /> <!-- default -->
</selector>
"""


def main():
    res = Path(sys.argv[1])
    lib, gap, space = G.library(res)
    lib.update({k: [v] for k, v in G.synthesize(lib).items() if k not in lib})
    for arg in sys.argv[2:]:
        name, text = arg.split("=", 1)
        for d, (w, h) in DENSITIES.items():
            G.button(lib, gap, space, text, w, h).save(res / d / f"btn_{name}.png", optimize=True)
            G.button(lib, gap, space, text, w, h, outline=True).save(res / d / f"btn_{name}_outline.png", optimize=True)
            (res / d / f"sel_btn_{name}.xml").write_text(SELECTOR.format(n=name))
        print("wrote", name)


if __name__ == "__main__":
    main()
