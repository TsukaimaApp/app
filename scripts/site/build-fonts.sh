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

subset() { # <source ttf> <output woff2> <unicodes>
  local in="$src/$1" dest="$out/$2"
  [[ -f "$in" ]] || { echo "build-fonts: missing $in" >&2; exit 1; }
  # Variation axes are kept (no --instance), so one file per style covers every weight.
  pyftsubset "$in" --flavor=woff2 --layout-features='*' --unicodes="$3" --output-file="$dest"
  printf '%-34s %8d bytes\n' "$2" "$(stat -c %s "$dest")"
}

subset source_serif_4.ttf         source-serif-4.woff2         "$latin"
subset source_serif_4_italic.ttf  source-serif-4-italic.woff2  "$latin"
subset instrument_sans.ttf        instrument-sans.woff2        "$latin"
subset ibm_plex_mono.ttf          ibm-plex-mono.woff2          "$latin"
subset noto_serif_jp_subset.ttf   noto-serif-jp-subset.woff2   "$kanji"

# Licence: one copyright line per family, read from each font's name table (ID 0), then the OFL 1.1 text.
python3 - "$src" "$out/OFL.txt" <<'PY'
import sys
from pathlib import Path
from fontTools.ttLib import TTFont

src, dest = Path(sys.argv[1]), Path(sys.argv[2])
fonts = [
    ('Source Serif 4', 'source_serif_4.ttf'),
    ('Instrument Sans', 'instrument_sans.ttf'),
    ('IBM Plex Mono', 'ibm_plex_mono.ttf'),
    ('Noto Serif JP', 'noto_serif_jp_subset.ttf'),
]
header = ['The fonts in this directory are subsets of the following families, converted to WOFF2.',
          'Each is licensed under the SIL Open Font License, Version 1.1, reproduced below.', '']
for family, file in fonts:
    copyright = TTFont(src / file)['name'].getDebugName(0)
    if not copyright:
        sys.exit(f'build-fonts: {file} has no copyright string (name ID 0)')
    header.append(f'{family}: {copyright}')

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
