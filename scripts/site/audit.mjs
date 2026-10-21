#!/usr/bin/env node
// Browser audit for the project site, run locally (not in CI) with Playwright's Chromium.
//
//   bash scripts/site/audit.sh                 # installs the pinned devDependencies, then runs this file
//   SHOTS_DIR=/some/dir node scripts/site/audit.mjs
//
// Loads site/index.html over file:// and asserts:
//   - axe-core (wcag2a + wcag2aa) reports no serious or critical violations;
//   - no horizontal page scroll at 360, 390 and 1280 px wide;
//   - prefers-reduced-motion: reduce stops the flair cycling and the dot pulse;
//   - without reduced motion the hero flair verb changes within 4 s;
//   - with JavaScript disabled the approval card is visible;
//   - Approve swaps the card for a tool row and a reply, Deny swaps in the other reply, Reset restores the card.
// Writes full-page screenshots site-1280.png and site-390.png to SHOTS_DIR. Exit 0 = pass, 1 = any failure.

import { mkdirSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { chromium } from 'playwright';

const HERE = dirname(fileURLToPath(import.meta.url));
const PAGE = pathToFileURL(resolve(HERE, '..', '..', 'site', 'index.html')).href;
const SHOTS_DIR = process.env.SHOTS_DIR
  || '/tmp/claude-999/-home-claude-projects-tsukaima/7f5c22e9-e6ca-4c09-b478-33d4ee25e148/scratchpad/site-shots/';
const AXE_SOURCE = readFileSync(createRequire(import.meta.url).resolve('axe-core/axe.min.js'), 'utf8');

const failures = [];
const check = (ok, name, detail = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ` — ${detail}` : ''}`);
  if (!ok) failures.push(name);
};

// Runs one step in its own context so a thrown error fails that step only.
async function step(browser, name, opts, fn) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, ...opts });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('requestfailed', (r) => errors.push(`request failed: ${r.url()} (${r.failure()?.errorText})`));
  try {
    await page.goto(PAGE, { waitUntil: 'load' });
    await page.evaluate(() => document.fonts.ready);
    await fn(page);
    check(errors.length === 0, `${name}: no page errors or failed requests`, errors.join('; '));
  } catch (e) {
    check(false, name, e.message.split('\n')[0]);
  } finally {
    await ctx.close();
  }
}

const flair = (page) => page.locator('#flair').textContent();

const browser = await chromium.launch();
try {
  await step(browser, 'fonts', {}, async (page) => {
    const loaded = await page.evaluate(() => [...document.fonts].filter((f) => f.status === 'loaded').map((f) => f.family));
    check(loaded.length >= 3, 'web fonts load over file://', [...new Set(loaded)].join(', ') || 'none loaded');
  });

  await step(browser, 'axe', {}, async (page) => {
    await page.addScriptTag({ content: AXE_SOURCE });
    // preload: false — axe would re-fetch the stylesheets itself (for its CSSOM rules), which fails over file://.
    const res = await page.evaluate(() => window.axe.run(document, { runOnly: ['wcag2a', 'wcag2aa'], preload: false }));
    const bad = res.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
    const counts = {};
    for (const v of res.violations) counts[v.impact] = (counts[v.impact] || 0) + 1;
    console.log(`     axe: ${res.passes.length} passes, ${res.violations.length} violations ${JSON.stringify(counts)}`);
    for (const v of res.violations) {
      console.log(`     - [${v.impact}] ${v.id}: ${v.help}`);
      for (const n of v.nodes.slice(0, 5)) console.log(`         ${n.target.join(' ')} ${n.failureSummary?.split('\n')[1]?.trim() ?? ''}`);
    }
    check(bad.length === 0, 'axe: zero serious/critical violations', `${bad.length} found`);
  });

  for (const width of [360, 390, 1280]) {
    await step(browser, `overflow ${width}`, { viewport: { width, height: 800 } }, async (page) => {
      const { sw, iw, wide } = await page.evaluate(() => {
        const iw = window.innerWidth;
        const wide = [...document.querySelectorAll('body *')]
          .filter((el) => el.getBoundingClientRect().right > iw + 0.5)
          .slice(0, 5)
          .map((el) => `${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''}${el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/).join('.') : ''}`);
        return { sw: document.documentElement.scrollWidth, iw, wide };
      });
      check(sw <= iw, `no horizontal scroll at ${width}`, `scrollWidth ${sw}, innerWidth ${iw}${sw > iw && wide.length ? `; wide: ${wide.join(', ')}` : ''}`);
    });
  }

  await step(browser, 'reduced motion', { reducedMotion: 'reduce' }, async (page) => {
    const before = await flair(page);
    await page.waitForTimeout(2500);
    const after = await flair(page);
    check(after === before, 'reduced motion: flair unchanged after 2.5 s', JSON.stringify(after));
    const anims = await page.evaluate(() => [...document.querySelectorAll('.dot, .sl .d')].map((d) => getComputedStyle(d).animationName));
    const heroDot = await page.evaluate(() => getComputedStyle(document.querySelector('.hero .dot')).animationName);
    check(heroDot === 'none', 'reduced motion: hero dot animation-name is none', heroDot);
    check(anims.length > 1 && anims.every((a) => a === 'none'), 'reduced motion: every status dot is still', `${anims.length} dots: ${[...new Set(anims)].join(', ')}`);
  });

  await step(browser, 'flair cycling', {}, async (page) => {
    const before = await flair(page);
    const changed = await page.waitForFunction((b) => document.querySelector('#flair').textContent !== b, before, { timeout: 4000 })
      .then(() => true, () => false);
    check(changed, 'flair changes within 4 s', `${JSON.stringify(before)} -> ${JSON.stringify(await flair(page))}`);
  });

  await step(browser, 'no javascript', { javaScriptEnabled: false }, async (page) => {
    check(await page.locator('#d-approval .approval').isVisible(), 'JS off: approval card visible');
    check(!(await page.locator('#d-approval .approval-done').isVisible()), 'JS off: approval result hidden');
    check(await page.locator('#flair').textContent() === 'Running shell command…', 'JS off: hero status is the static line');
  });

  await step(browser, 'approval demo', {}, async (page) => {
    const card = page.locator('#d-approval .approval');
    const done = page.locator('#d-approval .approval-done');
    await card.getByRole('button', { name: 'Approve' }).click();
    check(!(await card.isVisible()) && await done.locator('.toolrow').isVisible() && await done.locator('.msg').isVisible(),
      'Approve: card replaced by tool row + reply');
    const approved = (await done.locator('.msg').textContent()).trim();
    await done.getByRole('button', { name: 'Reset' }).click();
    check(await card.isVisible() && !(await done.isVisible()), 'Reset: card restored');
    await card.getByRole('button', { name: 'Deny' }).click();
    const denied = (await done.locator('.msg').textContent()).trim();
    check(!(await card.isVisible()) && denied !== approved && denied.startsWith('Understood'), 'Deny: card replaced by the deny reply', JSON.stringify(denied));
    await done.getByRole('button', { name: 'Reset' }).click();
    check(await card.isVisible() && (await done.locator('.msg').textContent()).trim() === approved, 'Reset after Deny: card and approve reply restored');
  });

  mkdirSync(SHOTS_DIR, { recursive: true });
  for (const width of [1280, 390]) {
    await step(browser, `screenshot ${width}`, { viewport: { width, height: 900 }, deviceScaleFactor: width < 600 ? 2 : 1 }, async (page) => {
      const path = join(SHOTS_DIR, `site-${width}.png`);
      await page.screenshot({ path, fullPage: true });
      console.log(`     wrote ${path}`);
    });
  }
} finally {
  await browser.close();
}

if (failures.length) {
  console.log(`\naudit: ${failures.length} failure(s)`);
  process.exit(1);
}
console.log('\naudit: pass');
