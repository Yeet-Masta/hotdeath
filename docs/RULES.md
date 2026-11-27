# Hot Death Uno Redux — how the app implements the Constitution (v3.1)

This branch (`new`) replaces the old Hot Death rules (points, bastard zeros,
old card set) with the rules in *Hot Death Uno Redux Constitution v3.1*.
Card art is out of scope: cards without artwork are drawn as their base card
with the special's name on top (see "Artwork" at the end).

A table game relies on people talking, flipping cards and keeping secrets.
Where the Constitution needs that, the app does the closest thing a program
can do. Every such choice is listed here.

## Deck

Four standard 108-card decks (432 cards), with special cards replacing the
base card named in the Constitution ("x2 (Red & Yellow 6)" replaces one Red 6
and one Yellow 6, and so on). Quantities are exactly the Constitution's.

Every card has three attributes:

* **color** — red, green, blue, yellow, or wild;
* **suit** — the base face: 0–9, Skip, Reverse, Draw Two, Wild, Wild Draw Four
  (the 69 has both 6 and 9 as its suit);
* **text** — the special's name (plain cards: same as suit).

A card plays on the top card if any attribute matches, or if it is wild.
Defense cards play on anything only while defending; Fuck You, Magic 5 and
Clone play on anything at any time.

## Table rules

* 4 seats, 8 cards each. No points: the scoreboard counts round wins
  (normal mode) or last-place finishes (elimination mode).
* A turn is: play a card, or draw one card and end the turn.
* Stacking: Draw Two family (Draw Two, Backstab) on itself; Draw Four family
  (Wild Draw Four, Harvester of Sorrows, Hot Death, Delayed Blast) on itself.
  No 2s on 4s or 4s on 2s. Mystery Draw and Spreader do not stack.
* **Attacks resolve at once.** When a card attacks someone, that player
  answers straight away (even out of turn, for directed attacks): stack,
  play a defense, or take it. Taking a non-directed attack uses up your turn.
* Turn after an attack: after the player who took or blocked it, except that a
  directed attack that is simply taken continues after the attacker, and
  Fuck This Person In Particular continues after its player.
* **Jump-ins.** After any card is played, any other player holding an
  identical card (color, suit and text; 69 ↔ 6/9 of its color) may jump in,
  unless the card just played skipped them. People get a short window (set in
  Settings) in which tapping the identical card jumps in; tapping a different
  card in that window is a wrong jump-in (draw 2). Computers jump in at a
  random moment in the window. Jumping in on a stack resets it to the
  jumping card's value. Play continues from the jumper.
* **UNO.** One UNO button does both jobs. With one or two cards, it calls UNO
  for you. Otherwise it catches the first opponent who has one card and has
  not called (they draw 2). If nobody can be caught, it is a false call and
  you draw 2. A player who plays their last card without having called UNO
  has not won: they draw 2 instead. "Call UNO for me" in Settings turns this
  off for people. Computers sometimes forget, and sometimes catch.
* **Free actions** (do not use the turn): UNO, accuse, ask the Prophet,
  Conqueror color change. People can use them when the game is waiting for
  them. Free actions from anyone else wait for the game's next pause between
  plays.
* **Draw hierarchy.** Ping, Spreader, Gift and Gold Coin draws are never
  changed. Luck, Yin, Yang (−1 each) and Virus (+1 each) change every other
  draw, including AIDS shares, penalty draws and draws to skip your turn.

## Ending a round

* **Normal mode:** first player with no cards wins. Removed players (Quitter,
  M.A.D., Martyr, Death, accusations) lose. If only one player is left, they win.
  If nobody is left, the round has no winner.
* Ending on a special card does not use its effect, except: attacks and
  defenses still work (a reflected final attack puts you back in), Gold Coin is
  still flipped (face down: draw 1 and you are back in), and AIDS played on
  the last card still splits.
* **Elimination mode** (Settings): the goal is not to be last. Going out or
  being removed takes you out safely; the last player left loses. Quitter
  takes out its user. Catching a Quitter holder takes the accuser out instead
  and discards the Quitter; a wrong accusation costs 8 cards. Communism ignores
  players who are out. Ending on a card always uses its effect.
* **Duel** (2 players left): Delayed Blast is a plain Draw Four; Reverse,
  Double Skip and Reverse Skip are plain Skips.

## Start of a round (first card turned up)

* Ping or Glasnost: the dealer chooses the victim. Gift, Gold Coin, M.A.D. and
  Martyr do nothing (they would act on the dealer's own hand).
* Other attacks hit the dealer (no answers). Spreader: everyone draws 2.
* Field card: goes to the dealer, active. Horsemen go to neutral play and the
  top card counts as a plain Red 4.
* Reverse: play starts to the dealer's right. Skip / Double Skip skip the first
  one / two players. Reverse Skip starts to the right and skips one.
* Communism does nothing. Quitter quits the dealer. Plague is released by the
  dealer. Wild: the dealer chooses the color.

## Cards

| Card | How the app does it |
|---|---|
| 69 | Number card worth 69 (Mystery Draw = 69 cards). Suit 6 and 9. |
| AIDS | Answer to a draw attack, Quitter or Glasnost (not Spreader, Ping, field cards). Splits a draw between you and the last player who added to it (the attacker gets the odd card). Quitter: its user is removed too. Glasnost: both hands are shown. |
| Backstab | Reverses, then the new next player (who played before you) draws 2. Stacks with Draw Twos. |
| Blue Shield | Field card. Absorbs draw attacks aimed at you (your turn is skipped); ≥4 cards breaks it. Harvester breaks it and you still draw 4. Being hit by Fuck You / FTPIP always breaks it. You can still stack instead. |
| Clone | Repeats the top card's effect on the next player. On a stack it adds the last card's contribution. Copies Recycler (a second recycle pile). Not field cards or Communism. |
| Communism | Every active player's cards (and any recycle pile, which ends) are pooled and dealt out evenly from the player after you. Field cards you receive become active for you. |
| Conquest | Field card. On your turn you may *Invade* instead of playing: both players choose a number card; higher wins (69 = 69). See the Constitution for wins, losses, ties, Quitter armies and no-army defenders. Capture every other player's army to become the Conqueror (free color change before playing, not while on UNO). |
| Cure | Removes one Virus from its player. |
| Death | Directed, unblockable. The victim's clock is the number on the top card of the draw pile (otherwise 4; a Horseman goes to neutral play). The clock counts down at the start of each of the victim's turns, with +1 per Luck/Yin/Yang and −1 per Virus; at 0 they are removed. Received through Communism: 7 turns. |
| Delayed Blast | Skips the next player; the one after draws 4. Draw Four family. |
| Dodge | Answer to an attack (not Harvester, AIDS, Spreader, or unblockable directed cards): it passes to the next player. |
| Double Skip / Reverse Skip | As named. |
| Famine | Neutral field card. You must play when you can (no voluntary draw); if you cannot, draw until you can, then play. After a plain card (no effect on whose turn it is), you must keep playing while you can. |
| FTPIP | Answer to any blockable attack: you choose who takes it. Otherwise a plain Blue 0. |
| Fuck You | Answer to any attack except Spreader and unblockable ones: it goes back to whoever started it and play reverses. Can be Fuck You'd. Played without an attack, it is a Reverse. Also blocks a Necromancer revival (jump-in window). |
| Gift | Give another card (not your last) to a player you choose. They and you can see it. |
| Glasnost | Victim's hand is shown to everyone (cards they draw later are not). Caught holding Quitter: removed. |
| Gold Coin | Random flip. Face up: draw one card per active player and hand them out as you like. |
| Harvester of Sorrows | Draw 4, unblockable, Draw Four family. |
| Holy Defender | Blocks attacks except Harvester, Ping, Gift, AIDS, Spreader (and unblockable cards). Whenever played, its player may remove one active Horseman. |
| Hot Death | Draw 8, Draw Four family. |
| I Don't Like These | Discard your hand, draw back up to the same number. |
| Luck, Yin, Yang | Field cards, −1 card per draw. Yin and Yang activate the moment they are drawn. Different players with Yin and Yang become a team (the one with more cards drops out and shares the result); attacks on the team are halved (rounded up), the team's attacks and stack contributions doubled; Spreader, Ping and Harvester unaffected. One player with both is immune to attacks except Spreader, Gift, Ping, Gold Coin, Quitter, M.A.D. and AIDS; a draw attack then just skips them, unless the stack ended with a Harvester. |
| M.A.D. | You and a player you choose drop your hands and leave the round. Unblockable. |
| Magic 5 | Answer to an attack containing Hot Death: removes 8 per Hot Death. If anything is left you answer again. |
| Martyr | You leave the round and give your cards to a player you choose. Unblockable. |
| Mystery Draw | On a 1–9 (or the 69): next player draws that many. Otherwise a Wild. Does not stack. |
| Necromancer | Revive a removed player (elimination mode: anyone who is out) with 8 new cards. Anyone holding Fuck You may stop it in a jump-in window. Nobody to revive: a plain Green 7. |
| Penn State | The only answer to Spreader, and played automatically: the Spreader's player draws your 2 instead, and play continues after you. |
| Ping | Directed: one card, never changed. Can be Fuck You'd, FTPIP'd or Dodged. |
| Plague | Put in play by whoever draws it (they are the carrier). Each player who takes a turn draws 1 after playing (Luck holders none, Virus 2); a player who draws because of an attack or cannot play draws 1 extra. Skipped players do not. On the carrier's next turn the Plague goes on the discard pile and their turn is over. Removed by The Flood. |
| Prophet | Field card. Choose a player; you see their hand, and at the start of each of your turns you choose again. Other players may ask, once per turn, "does that player have card X?". The answer is always truthful and shown to everyone. A "yes" on Quitter removes its holder; a "yes" on M.A.D. removes the holder and the Prophet. A Horseman the Prophet sees goes into play. |
| Quitter | The next player is removed (elimination mode: you are). Dodge, Holy Defender, Fuck You, FTPIP and AIDS answer it. Anyone may accuse anyone of holding Quitter; see the Constitution for the outcome. |
| Recycler | Reverses; later cards go on a recycle pile until a reverse card (Reverse, Backstab, Swap, Recycler, Reverse Skip) is played. The new next player then draws the whole pile (can be Dodged or Fuck You'd — then the Recycler's player draws it — but not blocked). Backstab adds 2 and carries the pile in the stack. |
| Spreader | Everyone else draws 2; Penn State is the only answer; you go again. |
| Swap | Swap hands with the player before you (field cards stay), then reverse. |
| Taste the Rainbow | Next player draws until they have drawn all four colors or a wild. Blockable, not stackable. |
| The Chosen One | Draw 5, keep 1, the other 4 go back on top of the draw pile in the order drawn. |
| The Flood | Discards every field card except Death, War, Conquest, Yin and Yang. Ends Plague (its player skips the Plague draw this turn). |
| Virus | Directed field card on the victim: +1 card on every draw. Several can stack on one player. Answered like a blockable directed attack. |
| War | Neutral field card. Everyone turns up a card from the draw pile; until War is removed (Holy Defender) you cannot play that color (wild: no ban). Fuck You, Magic 5 and defenses are exempt. |

**The Apocalypse:** when Death, Famine, War and Conquest are all active, every
special card becomes its base card and the round finishes under original
Uno rules: no stacking, no jump-ins, no field effects, Wild Draw Four only
without a card of the current color, and you may play the card you draw.

## Artwork

`CardText.drawable` looks for a drawable named `card_<type key>` (for example
`card_red_4_death`). Until one exists, the base card's art is shown with the
special's name drawn across it.
