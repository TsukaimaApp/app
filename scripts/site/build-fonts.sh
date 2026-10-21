#!/usr/bin/env bash
# Subsets the app's bundled fonts to the characters the site uses and writes them as woff2 into site/fonts/,
# together with the SIL OFL 1.1 licence text. Idempotent: re-running overwrites the same outputs.
#
#   bash scripts/site/build-fonts.sh
#
# Needs fonttools with brotli (pyftsubset) — `sudo apt install fonttools python3-brotli` or `pip install fonttools brotli`.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
src="$repo/app/src/main/res/font"
out="$repo/site/fonts"

command -v pyftsubset >/dev/null || { echo "build-fonts: pyftsubset not found (install fonttools)" >&2; exit 1; }
python3 -c 'import brotli' 2>/dev/null || { echo "build-fonts: python brotli module not found (needed for woff2)" >&2; exit 1; }

# Basic Latin, Latin-1, en/em dash, curly quotes, bullet, ellipsis, right arrow, middle dot.
latin='U+0020-007E,U+00A0-00FF,U+2013,U+2014,U+2018,U+2019,U+201C,U+201D,U+2022,U+2026,U+2192,U+00B7'
# 使 い 魔
kanji='U+4F7F,U+3044,U+9B54'

mkdir -p "$out"

# Renames a built woff2 (OFL section 3: a subset is a Modified Version and must not use a Reserved Font Name).
# Family, full, unique and PostScript names (IDs 1, 3, 4, 6, 16, and 17 if it carries the family) plus the
# per-instance PostScript names get the new family; copyright, licence and designer records are untouched.
rename() { # <woff2> <new family>
  python3 -c '
import re, sys
from fontTools.ttLib import TTFont
path, family = sys.argv[1], sys.argv[2]
ps_family = family.replace(" ", "")
font = TTFont(path, recalcTimestamp=False)  # keep head.modified from the subset, so reruns are byte-identical
name = font["name"]
old_ps_family = (name.getDebugName(6) or "").split("-")[0]
# Instance names keep a style suffix the original prefix had (SourceSerif4Italic-Light -> TsukaimaSerifItalic-Light).
instance_ps_family = ps_family + ("Italic" if old_ps_family.endswith("Italic") else "")
old_family = name.getDebugName(16) or name.getDebugName(1)
sub = name.getDebugName(2) or "Regular"
full = family if sub == "Regular" else f"{family} {sub}"
ps = f"{ps_family}-{sub.replace(" ", "")}"
for rec in name.names:
    text = rec.toUnicode()
    if rec.nameID in (1, 16):
        new = family
    elif rec.nameID == 4:
        new = full
    elif rec.nameID == 6:
        new = ps
    elif rec.nameID == 3:
        new = f"{ps};tsukaima.app"
    elif rec.nameID == 17 and old_family and old_family in text:
        new = text.replace(old_family, family)
    elif rec.nameID >= 256 and old_ps_family and text.startswith(old_ps_family + "-"):
        new = instance_ps_family + text[len(old_ps_family):]
    else:
        continue
    rec.string = new
font.flavor = "woff2"
font.save(path)
' "$1" "$2"
}

subset() { # <source ttf> <output woff2> <unicodes> <new family>
  local in="$src/$1" dest="$out/$2"
  [[ -f "$in" ]] || { echo "build-fonts: missing $in" >&2; exit 1; }
  # Variation axes are kept (no --instance), so one file per style covers every weight.
  pyftsubset "$in" --flavor=woff2 --layout-features='*' --unicodes="$3" --output-file="$dest"
  rename "$dest" "$4"
  printf '%-34s %8d bytes  (%s)\n' "$2" "$(stat -c %s "$dest")" "$4"
}

subset source_serif_4.ttf         source-serif-4.woff2         "$latin" 'Tsukaima Serif'
subset source_serif_4_italic.ttf  source-serif-4-italic.woff2  "$latin" 'Tsukaima Serif'
subset instrument_sans.ttf        instrument-sans.woff2        "$latin" 'Tsukaima Sans'
subset ibm_plex_mono.ttf          ibm-plex-mono.woff2          "$latin" 'Tsukaima Mono'
subset noto_serif_jp_subset.ttf   noto-serif-jp-subset.woff2   "$kanji" 'Tsukaima Kanji'

# Licence: one copyright line per family, read from each font's name table (ID 0), then the OFL 1.1 text.
python3 - "$src" "$out/OFL.txt" <<'PY'
import sys
from pathlib import Path
from fontTools.ttLib import TTFont

src, dest = Path(sys.argv[1]), Path(sys.argv[2])
fonts = [
    ('source-serif-4.woff2', 'Source Serif 4 (Regular)', 'source_serif_4.ttf', 'Tsukaima Serif'),
    ('source-serif-4-italic.woff2', 'Source Serif 4 (Italic)', 'source_serif_4_italic.ttf', 'Tsukaima Serif'),
    ('instrument-sans.woff2', 'Instrument Sans', 'instrument_sans.ttf', 'Tsukaima Sans'),
    ('ibm-plex-mono.woff2', 'IBM Plex Mono', 'ibm_plex_mono.ttf', 'Tsukaima Mono'),
    ('noto-serif-jp-subset.woff2', 'Noto Serif JP', 'noto_serif_jp_subset.ttf', 'Tsukaima Kanji'),
]
header = ['The fonts in this directory are modified versions (subsets, converted to WOFF2 and renamed) of the',
          'fonts below. Each is licensed under the SIL Open Font License, Version 1.1, reproduced below.', '']
for out_file, original, file, renamed in fonts:
    copyright = TTFont(src / file)['name'].getDebugName(0)
    if not copyright:
        sys.exit(f'build-fonts: {file} has no copyright string (name ID 0)')
    header += [f'{out_file}', f'  Original font: {original}', f'  {copyright}',
               f'  Subset and renamed to {renamed} for tsukaima.app; not the original font.', '']

OFL = """
-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded,
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
"""
dest.write_text('\n'.join(header) + '\n' + OFL)
print(f'{dest.name:<34} {dest.stat().st_size:8d} bytes')
PY

printf '%-34s %8d bytes\n' 'total woff2' "$(cat "$out"/*.woff2 | wc -c)"

# Final assertion: every required glyph is in each subset's cmap, and no original family name survived.
python3 - "$out" <<'PY'
import sys
from pathlib import Path
from fontTools.ttLib import TTFont

out = Path(sys.argv[1])
latin = '…—·→“”‘’–•'
need = {
    'source-serif-4.woff2': latin, 'source-serif-4-italic.woff2': latin, 'instrument-sans.woff2': latin,
    'ibm-plex-mono.woff2': latin, 'noto-serif-jp-subset.woff2': '使い魔',
}
old_names = ('Source', 'Instrument', 'Plex', 'Noto')
bad = []
for file, chars in need.items():
    font = TTFont(out / file)
    cmap = font.getBestCmap()
    missing = [f'U+{ord(c):04X}' for c in chars if ord(c) not in cmap]
    if missing:
        bad.append(f'{file}: missing glyphs {" ".join(missing)}')
    for rec in font['name'].names:
        if rec.nameID in (1, 3, 4, 6, 16, 17) or rec.nameID >= 256:
            if any(o in rec.toUnicode() for o in old_names):
                bad.append(f'{file}: name ID {rec.nameID} still reads {rec.toUnicode()!r}')
for b in bad:
    print(f'build-fonts: {b}', file=sys.stderr)
if bad:
    sys.exit(1)
print('cmap and names: ok')
PY
