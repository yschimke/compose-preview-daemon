"""Regenerates before.png / after.png for the "downloadable fonts drop their axes" fix.

A FONT-LEVEL PROXY, not a captured Glimmer sticker: it draws the seven Glimmer type roles with the
two files the resolver picks between, so the difference the fix makes to the face is visible without
standing up a catalog render against a locally published daemon. The renderer applies the very same
axes, through `Paint.setFontVariationSettings`, to the very same files.

Inputs, fetched rather than committed (the variable file alone is 4.2 MB):

  gsf-400.ttf  the static instance the Google Fonts CSS API serves, which is what the cache holds
               today. Send an Android-2.3 User-Agent, as `downloadFromGoogleFonts` does, or the
               endpoint answers in WOFF2:
                 curl -A "<android-2.3 UA>" \
                   "https://fonts.googleapis.com/css2?family=Google+Sans+Flex:wght@400"
               then fetch the `src: url(....ttf)` it names.

  gsf-var.ttf  the pre-instancing file `GoogleFontSource.loadVariable` fetches, the only one with an
               `fvar` table:
                 https://raw.githubusercontent.com/google/fonts/main/ofl/googlesansflex/
                   GoogleSansFlex%5BGRAD,ROND,opsz,slnt,wdth,wght%5D.ttf

Axis values are `GoogleSansFlexTypographyDefaults` from androidx.xr.glimmer:glimmer-google-fonts.

    pip install pillow && python3 render-proxy.py
"""

from PIL import Image, ImageDraw, ImageFont

DENSITY = 2.625  # the Robolectric glimmer lane's default density
# GoogleSansFlexTypographyDefaults: (role, sp, wght). opsz 9, wdth 100, GRAD 0, ROND 100, slnt 0.
ROLES = [
    ("titleLarge",  30, 750), ("titleMedium", 24, 750), ("titleSmall", 20, 750),
    ("bodyLarge",   30, 520), ("bodyMedium",  24, 520), ("bodySmall",  20, 520),
    ("caption",     18, 650),
]
SAMPLE = "Glimmer"
LABEL_PX = 22
PAD, GAP = 40, 18

def build(path, variable, out, title):
    label_font = ImageFont.truetype("gsf-400.ttf", LABEL_PX)
    rows = []
    for role, sp, wght in ROLES:
        px = round(sp * DENSITY)
        f = ImageFont.truetype(path, px)
        if variable:
            # axis order from get_variation_axes(): opsz, wdth, wght, GRAD, ROND, slnt
            f.set_variation_by_axes([9.0, 100.0, float(wght), 0.0, 100.0, 0.0])
            note = f"{role}  {sp}sp  wght {wght} · ROND 100 · opsz 9"
        else:
            note = f"{role}  {sp}sp  wght 400 · ROND 0 · opsz 18"
        rows.append((f, note, px))

    w = 1100
    h = PAD * 2 + 54 + sum(px + LABEL_PX + GAP + 10 for _, _, px in rows)
    img = Image.new("RGB", (w, h), (0, 0, 0))
    d = ImageDraw.Draw(img)
    head = ImageFont.truetype("gsf-400.ttf", 28)
    d.text((PAD, PAD), title, font=head, fill=(120, 190, 255))
    y = PAD + 54
    for f, note, px in rows:
        d.text((PAD, y), note, font=label_font, fill=(130, 130, 130))
        y += LABEL_PX + 6
        d.text((PAD, y), SAMPLE, font=f, fill=(240, 240, 240))
        y += px + GAP + 4
    img.save(out)
    print(out, img.size)

build("gsf-400.ttf", False, "before.png",
      "BEFORE — every role resolves the same static wght-400 instance; axes dropped")
build("gsf-var.ttf", True, "after.png",
      "AFTER — the variable face, instanced at each role's axes")
