// Renders card designs with the Card Workshop page. Used by build_deck.py.
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const [tool, jobFile] = process.argv.slice(2);
  const job = JSON.parse(fs.readFileSync(jobFile, 'utf8'));
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto('file://' + tool);
  await page.waitForFunction(() => window.cardWorkshop && document.fonts && document.fonts.status === 'loaded');
  await page.evaluate(() => Promise.all(['40px "Archivo Black"', '40px "Bangers"', '700 40px "Archivo Narrow"', '700 40px "Source Sans 3"', '900 40px "Source Sans 3"', '40px "Black Ops One"', '40px "Pirata One"', '40px "Metal Mania"', '40px "Bungee"', '40px "Titan One"'].map(f => document.fonts.load(f))));
  const out = [];
  for (const c of job.cards) {
    const pngs = await page.evaluate(async ({ c, densities }) => {
      if (c.design.art) await window.cardWorkshop.preload(c.design.art);
      return densities.map(([dir, w, h]) => ({ dir, png: window.cardWorkshop.pngDataUrl(c.design, w, h) }));
    }, { c, densities: job.densities });
    for (const p of pngs) out.push({ key: c.key, ...p });
  }
  // contact sheet for review
  await browser.close();
  process.stdout.write(JSON.stringify(out));
})();
