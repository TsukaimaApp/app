# tsukaima.app

The project site: one static page, published as-is to GitHub Pages at <https://tsukaima.app/>. No build step for the
page; fonts are subset once by `scripts/site/build-fonts.sh` and committed. Development tooling lives in
`scripts/site/`, never in this directory, because everything here is served.

| Path | What |
|------|------|
| `index.html` | The page. |
| `site.css` | Tokens (light values from `design/tokens/`), `@font-face`, type roles, base styles, then page styles. |
| `fonts/` | WOFF2 subsets of Source Serif 4, Instrument Sans, IBM Plex Mono and Noto Serif JP, renamed Tsukaima Serif / Sans / Mono / Kanji (OFL reserved names), with `OFL.txt`. |
| `img/` | The mark and other images. |
| `CNAME` | `tsukaima.app`, for the Pages custom domain. |

## Links

- Download APK: <https://github.com/TsukaimaApp/app/releases/latest>
- View source: <https://github.com/TsukaimaApp/app>
- Obtainium: `obtainium://add/https://github.com/TsukaimaApp/app`

Each release carries the signed APK as an asset; "Download APK" and Obtainium both depend on that.

## Publishing

`.github/workflows/pages.yml` runs the checks on every push and pull request that touches `site/**` or
`scripts/site/**`, on any branch, and deploys this directory only from `main` (a push, or a manual run on `main`).
Until Pages is enabled the deploy job fails; that is expected. When the repo goes
public:

1. Repo Settings → Pages → Build and deployment → Source: **GitHub Actions**.
2. DNS for `tsukaima.app`: apex `A` records `185.199.108.153`, `185.199.109.153`, `185.199.110.153`,
   `185.199.111.153` and `AAAA` records `2606:50c0:8000::153`, `2606:50c0:8001::153`, `2606:50c0:8002::153`,
   `2606:50c0:8003::153` (GitHub Pages), or a `CNAME` to `tsukaimaapp.github.io` where the DNS host supports
   CNAME flattening at the apex.
3. Settings → Pages: set the custom domain to `tsukaima.app` and, once the certificate is issued, enable
   **Enforce HTTPS**.
4. Push a change under `site/` (or run the workflow by hand).

## Checks

```bash
bash scripts/site/check.sh          # html-validate (pinned) + scripts/site/check-page.mjs; exit 0 = pass
bash scripts/site/build-fonts.sh    # rebuild fonts/ from app/src/main/res/font/ (needs fonttools + brotli)
```

`check-page.mjs` fails on any `http(s)://` host other than github.com (TsukaimaApp/app, ImranR98/Obtainium,
NousResearch/hermes-agent) and tsukaima.app, local references that do not exist under `site/`, forbidden copy (see
the plan's Global Constraints), exclamation marks, emoji, anything but exactly one `h1`, a `section` without an
`h2`/`h3` (unless it holds the `h1`), an in-page `#id` link with no target, `@font-face` without `font-display:
swap`, font stacks without a generic fallback, and the size budgets (`fonts/` ≤ 500 kB, `site.js` ≤ 3 kB,
`img/og.png` ≤ 200 kB, no file over 1 MB).

## Attribution

<!-- Task 4: Obtainium badge attribution (project, repo URL, GPL-3.0) goes here. -->
