# Bundled fonts

`app/src/main/res/font/` holds Latin subsets of three SIL Open Font License 1.1 families, from
[google/fonts](https://github.com/google/fonts):

| File | Family | Change |
| --- | --- | --- |
| `ibm_plex_sans_regular.ttf`, `ibm_plex_sans_semibold.ttf` | IBM Plex Sans | static instances (wght 400, 600) of the variable font, Latin subset |
| `ibm_plex_mono_regular.ttf`, `ibm_plex_mono_medium.ttf` | IBM Plex Mono | Latin subset |
| `shippori_mincho_b1_extrabold.ttf` | Shippori Mincho B1 | Latin subset (the full font is 15 MB of CJK) |

Subset with fontTools (`U+0020-007E, U+00A0-00FF` plus dashes, quotes, bullet, ellipsis, arrows, ✓).
Arabic and other scripts fall back to the system font. Licence texts are beside this file.
