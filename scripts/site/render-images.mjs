#!/usr/bin/env node
// Renders site/img/og.png (1200x630, from scripts/site/og.html) and site/img/apple-touch-icon.png (180x180, from
// design/assets/app-icon.svg). Run from anywhere: node scripts/site/render-images.mjs
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { chromium } from 'playwright';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..', '..');

const browser = await chromium.launch();
try {
  const og = await browser.newPage({ viewport: { width: 1200, height: 630 }, deviceScaleFactor: 1 });
  await og.goto(pathToFileURL(join(HERE, 'og.html')).href);
  await og.evaluate(() => document.fonts.ready);
  await og.screenshot({ path: join(REPO, 'site/img/og.png'), clip: { x: 0, y: 0, width: 1200, height: 630 } });

  const icon = await browser.newPage({ viewport: { width: 180, height: 180 }, deviceScaleFactor: 1 });
  const svg = readFileSync(join(REPO, 'design/assets/app-icon.svg'), 'utf8').replace(/<svg\b([^>]*)>/, (_, a) =>
    `<svg${a.replace(/\s(width|height)="[^"]*"/g, '')} width="180" height="180">`);
  await icon.setContent(`<!doctype html><style>html,body{margin:0}svg{display:block}</style>${svg}`);
  await icon.screenshot({ path: join(REPO, 'site/img/apple-touch-icon.png'), clip: { x: 0, y: 0, width: 180, height: 180 } });
} finally {
  await browser.close();
}
