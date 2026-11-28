# Hot Death art tools

Tools for making the game's card art and main-menu buttons. This branch holds
only the tools; they read from and write into a checkout of the game.

## Card Workshop (`card-designer.html`)

Open it in a desktop browser. Design a card (color, corner index, center
panel, lettering, a picture), see it at the sizes the game uses, and download
the drawables for every density. The card look is matched to the original
scans: the 66x96 center pill, 14px corner indexes in Source Sans 3 Bold,
scan-style dithering, and worn, see-through edges.

## Whole deck (`deck/`)

`build_deck.py` reads every card type from the game's `engine/Types.kt` and
draws the complete deck with the Card Workshop's renderer (headless Chromium
via Playwright), writing `card_<key>.png` at all five densities plus the card
back and family-friendly variants. Pictures come from
[game-icons.net](https://game-icons.net) (CC BY 3.0); the script also rewrites
`docs/ARTWORK_CREDITS.md` in the game.

```bash
mkdir -p /tmp/gi && (cd /tmp/gi && npm pack game-icons-transparent && tar xzf game-icons-transparent-*.tgz)
```

```bash
python3 deck/build_deck.py /tmp/gi/package/svgs path/to/hotdeath
```

Each card's title, picture and options are in the `ART` table at the top of
the script; wild-card layout is `WILD_LAYOUT`, and the Hot Death card is
`HOT_DEATH`. `wild_options.py` renders the wild cards in alternative styles
for comparison.

Needs Python 3 with Pillow and NumPy, Node, and Playwright.

## Menu lettering (`menu/`)

The main-menu buttons' lettering, rebuilt from the original button art.
`glyphs.py` cuts the six original buttons (CONTINUE, NEW GAME, SETTINGS, HELP,
ABOUT, EXIT) into letters, builds the ones they lack from parts (R, Y, F), and
adds the pressed look's red outline (#cb0d28, 5.5px round).

Write buttons at every density, with their pressed/normal selector:

```bash
cd menu && python3 build_buttons.py path/to/hotdeath/app/src/main/res multiplayer=MULTIPLAYER stats=STATISTICS
```

`use_image_buttons.py` turns plain text menu buttons into image buttons in
`layout/main.xml` and `layout-land/main.xml`.
