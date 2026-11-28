#!/usr/bin/env python3
"""Turns the plain text menu buttons (btn_multiplayer, btn_stats) into image buttons like their neighbours."""
import re, sys
from pathlib import Path

IMAGE_BUTTON = """<ImageButton
{ind}	android:src="@drawable/sel_btn_{n}"
{ind}	android:id="@+id/btn_{n}"
{ind}	android:contentDescription="@string/{label}"
{ind}	android:layout_width="fill_parent"
{ind}	android:layout_height="wrap_content"
{ind}	android:scaleType="center"
{ind}	android:background="@null" />"""
LABELS = {"multiplayer": "lbl_multiplayer", "stats": "lbl_stats"}


def convert(path):
    s = path.read_text()
    for n, label in LABELS.items():
        m = re.search(r'([ \t]*)<Button\s+android:id="@\+id/btn_' + n + r'".*?/>', s, re.S)
        if m:
            ind = m.group(1)
            s = s[:m.start()] + ind + IMAGE_BUTTON.format(ind=ind, n=n, label=label) + s[m.end():]
    path.write_text(s)


def add_before(path, n, before_id):
    """Adds an image button for btn_<n> just above btn_<before_id> if the layout lacks it."""
    s = path.read_text()
    if f'@+id/btn_{n}"' in s:
        return
    m = re.search(r'([ \t]*)<ImageButton\s+android:src="@drawable/sel_btn_' + before_id, s)
    ind = m.group(1)
    s = s[:m.start()] + ind + IMAGE_BUTTON.format(ind=ind, n=n, label=LABELS[n]) + "\n\n" + s[m.start():]
    path.write_text(s)


if __name__ == "__main__":
    res = Path(sys.argv[1])
    for p in [res / "layout/main.xml", res / "layout-land/main.xml"]:
        convert(p)
    for extra in sys.argv[2:]:          # e.g. land:multiplayer:help
        where, n, before = extra.split(":")
        add_before(res / ("layout-land/main.xml" if where == "land" else "layout/main.xml"), n, before)
