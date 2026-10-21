#!/usr/bin/env node
// Static checks for the project site (no dependencies; Node 22+).
//
//   node scripts/site/check-page.mjs site/index.html
//
// Prints one line per failure as "<file>: <rule>: <offending value>" and exits 1 on any failure, 0 when clean.
// Also asserts the page carries the plan's verbatim links, required facts/captions and nav section ids.
// Scans the page, every local stylesheet it links (following @import), every local script it loads, and
// site/site.css. Budgets are measured over the site/ tree; files that do not exist yet are skipped.

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const SITE = join(REPO, 'site');

const ALLOWED_HOSTS = new Set(['github.com', 'tsukaima.app']);
const GITHUB_PATHS = ['/TsukaimaApp/app', '/ImranR98/Obtainium', '/NousResearch/hermes-agent'];
// Placeholder hosts the page may show as text (the Settings mock's Server URL). Allowed only in an HTML page's text
// content, never inside a tag (href, src, …) and never in CSS or scripts, so they can never become a request.
const EXAMPLE_TEXT_HOSTS = new Set(['hermes.your-tailnet.ts.net']);

// From the plan's Global Constraints. `token` is matched as a whole word; every term gets a word boundary on any
// edge that is a letter or digit, so "SSE" does not fire on "assess" and "hm_" still fires on "hm_3f9a".
const FORBIDDEN = ['SSE', 'hm_', '405B', 'Hermes 4', 'web build', 'token', 'fonts.googleapis.com', 'unpkg.com', 'cdn.'];
const forbiddenRe = (term) => {
  const esc = term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const pre = /^[a-z0-9]/i.test(term) ? '(?<![A-Za-z0-9])' : '';
  const post = /[a-z0-9]$/i.test(term) ? '(?![A-Za-z0-9])' : '';
  return new RegExp(pre + esc + post, 'i');
};

const GENERIC_FAMILIES = new Set(['serif', 'sans-serif', 'monospace', 'system-ui']);
const BUDGETS = [
  { path: 'fonts', max: 500_000, label: 'site/fonts total' },
  { path: 'site.js', max: 3_000, label: 'site/site.js' },
  { path: 'img/og.png', max: 200_000, label: 'site/img/og.png' },
];
const MAX_FILE = 1_000_000;

// Attributes whose values are read by people or assistive tech, so copy rules apply to them too.
const TEXT_ATTRS = ['alt', 'title', 'aria-label', 'content', 'placeholder'];

const failures = [];
const rel = (p) => relative(REPO, p) || p;
const fail = (file, rule, value) => failures.push(`${rel(file)}: ${rule}: ${value}`);
const isInside = (p, dir) => p === dir || p.startsWith(dir + sep);

function decodeEntities(s) {
  return s
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(parseInt(d, 10)))
    .replace(/&(nbsp|amp|lt|gt|quot|apos|excl);/g, (_, n) =>
      ({ nbsp: ' ', amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", excl: '!' })[n]);
}

// ---- external hosts -------------------------------------------------------------------------------------------

function checkUrls(file, text, isHtml = false) {
  // For the exemption below, script/style/template bodies and comments are blanked (same length, so indexes hold):
  // a placeholder host there is never element text.
  const textOnly = isHtml ? blankNonText(text) : null;
  // Absolute http(s) URLs anywhere in the file, including ones nested in another scheme (obtainium://add/https://…).
  for (const m of text.matchAll(/https?:\/\/[^\s"'<>()`\\]+/gi)) {
    if (isHtml && isExampleText(textOnly, m.index, m[0])) continue;
    checkHttpUrl(file, m[0]);
  }
  // Scheme-relative URLs in attributes and CSS url().
  for (const m of text.matchAll(/(?:\b(?:src|href|srcset|action|poster|data)\s*=\s*["']?|url\(\s*["']?)(\/\/[^\s"'<>()]+)/gi)) {
    checkHttpUrl(file, 'https:' + m[1]);
  }
}

// Blanks comments and <script>/<style>/<template> bodies with spaces of the same length (the same elements
// checkRequired strips), keeping every other character at its index.
function blankNonText(html) {
  const blank = (m) => m.replace(/[^\n]/g, ' ');
  return html.replace(/<!--[\s\S]*?-->/g, blank).replace(/<(script|style|template)\b[\s\S]*?<\/\1\s*>/gi, blank);
}

// True when the URL's host is a listed placeholder and the match sits in element text: not blanked away by
// blankNonText (comment, script, style, template) and not inside a tag.
function isExampleText(textOnly, index, raw) {
  let host;
  try { host = new URL(raw.replace(/[.,;:]+$/, '')).hostname.toLowerCase(); } catch { return false; }
  if (!EXAMPLE_TEXT_HOSTS.has(host)) return false;
  if (textOnly.slice(index, index + raw.length) !== raw) return false;
  const before = textOnly.slice(0, index);
  return before.lastIndexOf('<') <= before.lastIndexOf('>');
}

function checkHttpUrl(file, raw) {
  let url;
  try {
    url = new URL(raw.replace(/[.,;:]+$/, ''));
  } catch {
    fail(file, 'unparseable URL', raw);
    return;
  }
  const host = url.hostname.toLowerCase();
  if (!ALLOWED_HOSTS.has(host)) {
    fail(file, 'external host not allowed', `${host} (${raw})`);
    return;
  }
  if (host === 'github.com' && !GITHUB_PATHS.some((p) => url.pathname === p || url.pathname.startsWith(p + '/'))) {
    fail(file, 'github.com path not allowed', raw);
  }
}

// ---- local references -----------------------------------------------------------------------------------------

// Returns the absolute path of a local reference, or null for fragments, other schemes and absolute URLs.
function localTarget(fromFile, ref) {
  const v = decodeEntities(ref.trim());
  if (!v || v.startsWith('#') || v.startsWith('//') || /^[a-z][a-z0-9+.-]*:/i.test(v)) return null;
  const path = v.split(/[?#]/)[0];
  if (!path) return null;
  return path.startsWith('/') ? join(SITE, path) : resolve(dirname(fromFile), path);
}

function checkLocalRef(fromFile, ref) {
  const target = localTarget(fromFile, ref);
  if (!target) return null;
  if (!isInside(target, SITE)) {
    fail(fromFile, 'reference outside site/', ref);
  } else if (!existsSync(target) || !statSync(target).isFile()) {
    fail(fromFile, 'missing file', ref);
  }
  return target;
}

// ---- copy rules -----------------------------------------------------------------------------------------------

function visibleText(html) {
  const body = html
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<(script|style|template)\b[\s\S]*?<\/\1\s*>/gi, ' ')
    .replace(/<!doctype[^>]*>/gi, ' ');
  const attrs = [];
  for (const tag of body.matchAll(/<[a-z][^>]*>/gi)) {
    for (const a of tag[0].matchAll(/\s([a-z-]+)\s*=\s*("([^"]*)"|'([^']*)')/gi)) {
      if (TEXT_ATTRS.includes(a[1].toLowerCase())) attrs.push(a[3] ?? a[4]);
    }
  }
  const text = body.replace(/<[^>]*>/g, ' ');
  return decodeEntities([text, ...attrs].join('\n')).replace(/\s+/g, ' ');
}

function checkCopy(file, html) {
  const text = visibleText(html);
  const around = (i, len) => JSON.stringify(text.slice(Math.max(0, i - 30), i + len + 30).trim());
  for (const term of FORBIDDEN) {
    const re = new RegExp(forbiddenRe(term).source, 'gi');
    for (const m of text.matchAll(re)) fail(file, `forbidden term "${term}"`, around(m.index, m[0].length));
  }
  for (const m of text.matchAll(/!/g)) fail(file, 'exclamation mark in text', around(m.index, 1));
  // Extended_Pictographic includes ©, ® and ™, which are ordinary typography, not emoji.
  for (const m of text.matchAll(/(?![\u00A9\u00AE\u2122])\p{Extended_Pictographic}/gu)) {
    fail(file, 'emoji in text', `U+${m[0].codePointAt(0).toString(16).toUpperCase().padStart(4, '0')} ${around(m.index, m[0].length)}`);
  }
}

// ---- structure ------------------------------------------------------------------------------------------------

function checkStructure(file, html) {
  const markup = html.replace(/<!--[\s\S]*?-->/g, ' ').replace(/<(script|style)\b[\s\S]*?<\/\1\s*>/gi, ' ');
  const h1s = [...markup.matchAll(/<h1\b/gi)].length;
  if (h1s !== 1) fail(file, 'exactly one <h1> required', `${h1s} found`);

  // Every <section> needs an h2 or h3 of its own (nested sections are tracked separately); a section that
  // contains the page's h1 is headed by it.
  const open = [];
  for (const m of markup.matchAll(/<(\/?)(section|h[1-3])\b([^>]*)>/gi)) {
    const closing = m[1] === '/';
    const name = m[2].toLowerCase();
    if (name === 'section') {
      if (!closing) {
        const id = /\bid\s*=\s*["']?([^"'\s>]+)/i.exec(m[3])?.[1];
        const cls = /\bclass\s*=\s*["']([^"']*)["']/i.exec(m[3])?.[1];
        open.push({ desc: id ? `#${id}` : cls ? `.${cls.trim().split(/\s+/).join('.')}` : `<section> #${open.length + 1}`, heading: false, h1: false });
      } else {
        const s = open.pop();
        if (s && !s.heading && !s.h1) fail(file, 'section without an h2/h3 heading', s.desc);
      }
    } else if (!closing && name === 'h1') {
      for (const s of open) s.h1 = true;
    } else if (!closing && open.length) {
      open[open.length - 1].heading = true;
    }
  }
  for (const s of open) fail(file, 'unclosed <section>', s.desc);

  // Every in-page link (href="#id") must land on an element with that id. A bare "#" is the top of the page.
  const ids = new Set([...markup.matchAll(/\sid\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/gi)].map((m) => m[1] ?? m[2] ?? m[3]));
  for (const m of markup.matchAll(/\shref\s*=\s*(?:"#([^"]*)"|'#([^']*)'|#([^\s>]+))/gi)) {
    const raw = m[1] ?? m[2] ?? m[3];
    if (raw === '') continue;
    let id = raw;
    try { id = decodeURIComponent(raw); } catch { /* keep the raw fragment */ }
    if (!ids.has(id)) fail(file, 'in-page link without a target id', `#${raw}`);
  }
}

// ---- required content -----------------------------------------------------------------------------------------

// The plan's verbatim links (each must be the exact href of some link), the facts and captions the page must carry
// (in the visible text or any attribute value), and the section ids the nav points at.
const REQUIRED_HREFS = [
  'https://github.com/TsukaimaApp/app/releases/latest',
  'https://github.com/TsukaimaApp/app',
  'obtainium://add/https://github.com/TsukaimaApp/app',
  'https://github.com/TsukaimaApp/app/releases',
  'https://github.com/TsukaimaApp/app/issues',
  'https://github.com/ImranR98/Obtainium',
  'https://github.com/NousResearch/hermes-agent',
];
const REQUIRED_TEXT = [
  'Android 10+',
  'about 6 MB',
  'no account',
  'Connected · Hermes 0.22.2',
  'Opens in Obtainium on your phone',
  'Running shell command…',
  'Hermes dashboard',
  'username and password',
  'not affiliated with Nous',
];
const REQUIRED_SECTION_IDS = ['how', 'features', 'voice', 'selfhost', 'faq'];

function checkRequired(file, html) {
  const markup = html.replace(/<!--[\s\S]*?-->/g, ' ').replace(/<(script|style|template)\b[\s\S]*?<\/\1\s*>/gi, ' ');
  const attrValues = [];
  const hrefs = new Set();
  const sectionIds = new Set();
  for (const tag of markup.matchAll(/<([a-z][a-z0-9-]*)\b[^>]*>/gi)) {
    for (const a of tag[0].matchAll(/\s([a-z-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))/gi)) {
      const name = a[1].toLowerCase();
      const value = decodeEntities(a[2] ?? a[3] ?? a[4]);
      attrValues.push(value);
      if (name === 'href') hrefs.add(value.trim());
      if (name === 'id' && tag[1].toLowerCase() === 'section') sectionIds.add(value.trim());
    }
  }
  const haystack = [visibleText(html), ...attrValues].join('\n').replace(/\s+/g, ' ');
  for (const href of REQUIRED_HREFS) if (!hrefs.has(href)) fail(file, 'required link missing', href);
  for (const s of REQUIRED_TEXT) if (!haystack.includes(s)) fail(file, 'required text missing', JSON.stringify(s));
  for (const id of REQUIRED_SECTION_IDS) if (!sectionIds.has(id)) fail(file, 'required section id missing', `<section id="${id}">`);
}

// ---- stylesheets ----------------------------------------------------------------------------------------------

function checkFontFaces(file, css) {
  const clean = css.replace(/\/\*[\s\S]*?\*\//g, ' ');
  for (const m of clean.matchAll(/@font-face\s*\{([^}]*)\}/gi)) {
    if (!/font-display\s*:\s*swap\b/i.test(m[1])) {
      const family = /font-family\s*:\s*([^;]+)/i.exec(m[1])?.[1].trim() ?? '(no family)';
      fail(file, '@font-face without font-display: swap', family);
    }
  }
  for (const m of clean.matchAll(/(--font-[\w-]+)\s*:\s*([^;}]+)/g)) {
    const last = m[2].split(',').pop().trim().replace(/^["']|["']$/g, '').toLowerCase();
    if (!GENERIC_FAMILIES.has(last)) fail(file, `${m[1]} does not end in a generic family`, m[2].trim());
  }
}

const seenCss = new Set();
function scanCss(file, css) {
  checkUrls(file, css);
  checkFontFaces(file, css);
  const clean = css.replace(/\/\*[\s\S]*?\*\//g, ' ');
  for (const m of clean.matchAll(/url\(\s*["']?([^"')]+)["']?\s*\)/gi)) checkLocalRef(file, m[1]);
  for (const m of clean.matchAll(/@import\s+(?:url\()?\s*["']([^"']+)["']/gi)) {
    const target = localTarget(file, m[1]);
    if (target && existsSync(target)) scanCssFile(target);
  }
}
function scanCssFile(path) {
  if (seenCss.has(path)) return;
  seenCss.add(path);
  scanCss(path, readFileSync(path, 'utf8'));
}

// ---- budgets --------------------------------------------------------------------------------------------------

function sizeOf(path) {
  const st = statSync(path);
  if (!st.isDirectory()) return st.size;
  return readdirSync(path).reduce((sum, name) => sum + sizeOf(join(path, name)), 0);
}

function* walk(dir) {
  for (const ent of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, ent.name);
    if (ent.isDirectory()) yield* walk(p);
    else if (ent.isFile()) yield p;
  }
}

function checkBudgets() {
  if (!existsSync(SITE)) return;
  for (const b of BUDGETS) {
    const p = join(SITE, b.path);
    if (!existsSync(p)) continue;
    const size = sizeOf(p);
    if (size > b.max) fail(p, 'over budget', `${b.label} is ${size} bytes, limit ${b.max}`);
  }
  for (const p of walk(SITE)) {
    const size = statSync(p).size;
    if (size > MAX_FILE) fail(p, 'file over 1 MB', `${size} bytes`);
  }
}

// ---- head metadata and images ---------------------------------------------------------------------------------

function metaContent(markup, attr, name) {
  for (const tag of markup.matchAll(/<meta\b[^>]*>/gi)) {
    const a = new RegExp(`\\b${attr}\\s*=\\s*["']${name}["']`, 'i');
    if (!a.test(tag[0])) continue;
    const c = /\bcontent\s*=\s*"([^"]*)"/i.exec(tag[0]) ?? /\bcontent\s*=\s*'([^']*)'/i.exec(tag[0]);
    return c ? decodeEntities(c[1]) : '';
  }
  return null;
}

function linkTag(markup, rel) {
  for (const tag of markup.matchAll(/<link\b[^>]*>/gi)) {
    if (new RegExp(`\\brel\\s*=\\s*["']${rel}["']`, 'i').test(tag[0])) return tag[0];
  }
  return null;
}

function pngSize(path) {
  const b = readFileSync(path);
  if (b.length < 24 || b.readUInt32BE(0) !== 0x89504e47) return null;
  return [b.readUInt32BE(16), b.readUInt32BE(20)];
}

function checkHead(file, html) {
  const head = /<head\b[^>]*>([\s\S]*?)<\/head\s*>/i.exec(html)?.[1] ?? '';
  const markup = head.replace(/<!--[\s\S]*?-->/g, ' ');
  const SITE_URL = 'https://tsukaima.app/';
  const canonical = linkTag(markup, 'canonical');
  if (!canonical || !new RegExp(`\\bhref\\s*=\\s*["']${SITE_URL}["']`).test(canonical)) fail(file, 'head: canonical link missing or wrong', SITE_URL);
  const desc = metaContent(markup, 'name', 'description');
  if (!desc) fail(file, 'head: meta description missing or empty', '');
  else if (desc.length > 160) fail(file, 'head: meta description over 160 chars', String(desc.length));
  const exact = [
    ['property', 'og:type', 'website'],
    ['property', 'og:url', SITE_URL],
    ['property', 'og:image', 'https://tsukaima.app/img/og.png'],
    ['name', 'twitter:card', 'summary_large_image'],
    ['name', 'theme-color', '#F7F4ED'],
  ];
  for (const [attr, name, want] of exact) {
    const got = metaContent(markup, attr, name);
    if (got !== want) fail(file, `head: ${name} must be`, `${want} (found ${got === null ? 'none' : got})`);
  }
  for (const name of ['og:title', 'og:description']) {
    if (!metaContent(markup, 'property', name)) fail(file, `head: ${name} missing or empty`, '');
  }
  const icon = linkTag(markup, 'icon');
  if (!icon || !/\btype\s*=\s*["']image\/svg\+xml["']/i.test(icon)) fail(file, 'head: <link rel="icon" type="image/svg+xml"> missing', '');
  if (!linkTag(markup, 'apple-touch-icon')) fail(file, 'head: <link rel="apple-touch-icon"> missing', '');

  for (const [img, w, h] of [['img/og.png', 1200, 630], ['img/apple-touch-icon.png', 180, 180]]) {
    const p = join(SITE, img);
    if (!existsSync(p)) { fail(p, 'image missing', `site/${img}`); continue; }
    const size = pngSize(p);
    if (!size || size[0] !== w || size[1] !== h) fail(p, `image must be ${w}x${h} PNG`, size ? `${size[0]}x${size[1]}` : 'not a PNG');
  }
}

// ---- main -----------------------------------------------------------------------------------------------------

const target = process.argv[2];
if (!target || process.argv.length > 3) {
  console.error('usage: node scripts/site/check-page.mjs <page.html>');
  process.exit(2);
}
const page = resolve(target);
if (!existsSync(page)) {
  console.error(`check-page: no such file: ${target}`);
  process.exit(2);
}
const html = readFileSync(page, 'utf8');

checkUrls(page, html, true);
checkCopy(page, html);
checkStructure(page, html);
checkRequired(page, html);
checkHead(page, html);

const markup = html.replace(/<!--[\s\S]*?-->/g, ' ');
for (const m of markup.matchAll(/\s(src|href|poster)\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))/gi)) {
  checkLocalRef(page, m[3] ?? m[4] ?? m[5]);
}
for (const m of markup.matchAll(/\ssrcset\s*=\s*("([^"]*)"|'([^']*)')/gi)) {
  for (const candidate of (m[2] ?? m[3]).split(',')) {
    const ref = candidate.trim().split(/\s+/)[0];
    if (ref) checkLocalRef(page, ref);
  }
}

// Linked stylesheets (followed even when outside site/, so what they pull in is reported too), inline styles,
// local scripts, and site/site.css whether or not this page links it.
for (const tag of markup.matchAll(/<link\b[^>]*>/gi)) {
  if (!/\brel\s*=\s*["']?[^"'>]*\bstylesheet\b/i.test(tag[0])) continue;
  const href = /\bhref\s*=\s*["']?([^"'\s>]+)/i.exec(tag[0])?.[1];
  const path = href && localTarget(page, href);
  if (path && existsSync(path)) scanCssFile(path);
}
for (const m of html.matchAll(/<style\b[^>]*>([\s\S]*?)<\/style\s*>/gi)) scanCss(page, m[1]);
for (const m of markup.matchAll(/<script\b[^>]*\bsrc\s*=\s*["']?([^"'\s>]+)/gi)) {
  const path = localTarget(page, m[1]);
  if (path && isInside(path, SITE) && existsSync(path)) checkUrls(path, readFileSync(path, 'utf8'));
}
const siteCss = join(SITE, 'site.css');
if (existsSync(siteCss)) scanCssFile(siteCss);

checkBudgets();

for (const f of failures) console.log(f);
if (failures.length) {
  console.log(`\ncheck-page: ${failures.length} failure(s) in ${rel(page)}`);
  process.exit(1);
}
console.log(`check-page: ${rel(page)} clean`);
