#!/usr/bin/env python3
"""
Builds the main-menu lettering from the original button artwork itself.

The six original buttons (CONTINUE, NEW GAME, SETTINGS, HELP, ABOUT, EXIT)
are cut into letters, giving a glyph set of A B C E G H I L M N O P S T U W X
taken straight from the originals. Letters they don't contain are built from
parts of real glyphs (for example R = P + the leg of an A, Y = the top of an
X + the stem of an I), so they carry the same distress.
"""
from pathlib import Path
import numpy as np
from PIL import Image

WORDS = {"continue": "CONTINUE", "new_game": "NEW GAME", "settings": "SETTINGS",
         "help": "HELP", "about": "ABOUT", "exit": "EXIT"}
H = 100                                  # glyphs are cut from the 400x100 (xhdpi) buttons


def load(res, name):
    return np.array(Image.open(Path(res) / "drawable-xhdpi" / f"btn_{name}.png").convert("RGBA"))


def runs_of(alpha):
    col = (alpha > 40).any(axis=0)
    out, start = [], None
    for x, v in enumerate(col):
        if v and start is None:
            start = x
        if not v and start is not None:
            out.append([start, x - 1]); start = None
    if start is not None:
        out.append([start, len(col) - 1])
    # slivers (broken-off ends of T crossbars) belong to the letter before them
    merged = []
    for r in out:
        if r[1] - r[0] < 4 and merged:
            merged[-1][1] = r[1]
        else:
            merged.append(r)
    return merged


def library(res):
    """letter -> list of RGBA glyph arrays (full 100px height, tight width); plus measured spacing."""
    lib, gaps, spaces = {}, [], []
    for name, word in WORDS.items():
        img = load(res, name)
        runs = runs_of(img[..., 3])
        letters = word.replace(" ", "")
        assert len(runs) == len(letters), (name, len(runs))
        for (x0, x1), ch in zip(runs, letters):
            lib.setdefault(ch, []).append(img[:, x0:x1 + 1].copy())
        # spacing between letters, and the extra width of a space
        li = 0
        for i in range(1, len(runs)):
            g = runs[i][0] - runs[i - 1][1] - 1
            li += 1
            if word.replace(" ", "")[:li] != word[:li + word[:li + 1].count(" ")].replace(" ", "") or \
                    word[len(word[:li]) + word[:li + 1].count(" ") - 1:].startswith(" "):
                pass
            gaps.append(g)
        if " " in word:
            k = word.index(" ")
            spaces.append(runs[k][0] - runs[k - 1][1] - 1)
    letter_gap = int(np.median([g for g in gaps if g < 12]))
    space = int(np.median(spaces)) if spaces else letter_gap * 3
    return lib, letter_gap, space


def _paste(dst, src, x, y=0):
    """Alpha-max composite of src into dst at (x, y)."""
    h, w = src.shape[:2]
    region = dst[y:y + h, x:x + w]
    take = src[..., 3] > region[..., 3]
    region[take] = src[take]


def synthesize(lib):
    """Builds letters the originals lack from parts of real glyphs."""
    out = {}
    P, A, X, I, E, L = lib["P"][0], lib["A"][0], lib["X"][0], lib["I"][0], lib["E"][0], lib["L"][0]
    # R: the top half of a B (bowl and stem), the B's lower stem, and the lower-right arm of an X as the leg
    B = lib["B"][0]
    ink = np.where((B[..., 3] > 40).any(axis=1))[0]
    mid = ink.min() + int((ink.max() - ink.min()) * 0.52)
    stem = int(B.shape[1] * 0.42)
    xink = np.where((X[..., 3] > 40).any(axis=1))[0]
    xmid = xink.min() + int((xink.max() - xink.min()) * 0.5)
    leg = X[xmid + 5:, X.shape[1] // 2:]
    lx = int(B.shape[1] * 0.30)
    R = np.zeros((H, max(B.shape[1], lx + leg.shape[1]), 4), np.uint8)
    _paste(R, B[:mid - 2], 0)
    _paste(R, B[mid - 2:, :stem], 0, mid - 2)
    _paste(R, leg, lx + 2, mid + 3)
    out["R"] = R
    # Y: the top half of an X over the bottom half of an I, centered
    Y = np.zeros((H, X.shape[1], 4), np.uint8)
    _paste(Y, X[:52], 0)
    _paste(Y, I[48:], X.shape[1] // 2 - I.shape[1] // 2, 48)
    out["Y"] = Y
    # F: an E without its bottom bar (E's bottom bar sits in the lowest quarter of the letter)
    F = E.copy(); ink = np.where((F[..., 3] > 40).any(axis=1))[0]
    bot = ink.max(); bar_h = int((ink.max() - ink.min()) * 0.22)
    stem_w = int(F.shape[1] * 0.38)
    F[bot - bar_h:bot + 1, stem_w:] = 0
    out["F"] = F
    return out


def word_image(lib, gap, space, text, seed=0):
    rnd = np.random.default_rng(seed)
    glyphs = []
    for ch in text.upper():
        if ch == " ":
            glyphs.append(None); continue
        options = lib[ch]
        glyphs.append(options[rnd.integers(len(options))])
    width = sum((space - gap) if g is None else g.shape[1] + gap for g in glyphs) - gap
    img = np.zeros((H, width, 4), np.uint8)
    x = 0
    for g in glyphs:
        if g is None:
            x += space - gap; continue
        _paste(img, g, x); x += g.shape[1] + gap
    return img


OUTLINE = (203, 13, 40)       # measured from the original pressed buttons
OUTLINE_R = 5.5               # round dilation radius that matches them (IoU ~0.99)


def _dilate(mask, r):
    out = np.zeros_like(mask); R = int(np.ceil(r)); h, w = mask.shape
    for dy in range(-R, R + 1):
        for dx in range(-R, R + 1):
            if dx * dx + dy * dy <= r * r + 0.25:
                sh = np.zeros_like(mask)
                sh[max(0, dy):h + min(0, dy), max(0, dx):w + min(0, dx)] = mask[max(0, -dy):h + min(0, -dy), max(0, -dx):w + min(0, -dx)]
                out |= sh
    return out


def with_outline(img):
    """The pressed look: a red rim around the letters, following every chip."""
    m = img[..., 3] > 100
    core, soft = _dilate(m, OUTLINE_R), _dilate(m, OUTLINE_R + 0.8)
    out = np.zeros_like(img)
    out[soft] = (*OUTLINE, 120); out[core] = (*OUTLINE, 255)
    return np.array(Image.alpha_composite(Image.fromarray(out), Image.fromarray(img)))


def button(lib, gap, space, text, w=400, h=100, outline=False, seed=0):
    """A menu button image: the word centered on a transparent w x h canvas (made at 400x100, then scaled)."""
    word = word_image(lib, gap, space, text, seed)
    pad = 12                                   # room for the outline
    canvas = np.zeros((H, 400, 4), np.uint8)
    if word.shape[1] > 400 - 2 * pad:          # very long words are scaled down to fit
        k = (400 - 2 * pad) / word.shape[1]
        small = Image.fromarray(word).resize((int(word.shape[1] * k), int(H * k)), Image.LANCZOS)
        word = np.zeros((H, small.width, 4), np.uint8)
        word[(H - small.height) // 2:(H - small.height) // 2 + small.height] = np.array(small)
    x = (400 - word.shape[1]) // 2
    canvas[:, x:x + word.shape[1]] = word
    if outline:
        canvas = with_outline(canvas)
    im = Image.fromarray(canvas)
    return im if (w, h) == (400, 100) else im.resize((w, h), Image.LANCZOS)
