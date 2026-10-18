"""Build the icon dataset (research/25, D-055): UIST'18's labelled app icons plus Material Symbols renders, 64x64 grey.

    python prepare.py      # writes data/icons.npz (uint8 images, int labels) and data/classes.json

- Real icons: UIST'18 `data.zip` (Rico apps, 99 classes, 32x32 grey), downloaded from Google Drive into data/.
  Rico's terms bind its users; the owner allowed it for personal research, not published (2026-10-19).
- Renders: Material Symbols (Apache-2.0), three styles. They add the classes the real icons lack (QR code, scan,
  wallet, rupee, ...), today's look for the common classes, and an "other" class (icons outside the list, and text).
- Splits: UIST'18's own train/validation split for real icons; 10% of the renders held out.
"""
import io
import json
import random
import shutil
import urllib.request
import zipfile
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image, ImageDraw, ImageFilter, ImageFont

D = Path("data")
SIZE = 64
DATA_ZIP = "https://drive.usercontent.google.com/download?id=1SiD_U5ifjX1poJZzLB-MwvoUQBhutYzH&export=download&confirm=t"
MS = "https://raw.githubusercontent.com/google/material-design-icons/master/variablefont/"
FONTS = ["MaterialSymbolsOutlined[FILL,GRAD,opsz,wght]", "MaterialSymbolsRounded[FILL,GRAD,opsz,wght]",
         "MaterialSymbolsSharp[FILL,GRAD,opsz,wght]"]

# UIST'18's 99 classes, in its index order (validation_metadata.json)
UIST = ("explore add lock follow minus flash email search arrow_downward pause book date_range filter_list arrow_upward "
        "expand_less av_rewind help close arrow_backward navigation dashboard arrow_forward weather info redo folder more "
        "emoji chat volume visibility thumbs_up file_download playlist stop repeat expand_more shop flight reply location "
        "undo photo call star list delete share warning avatar build play dialpad settings layers time videocam filter "
        "send power gift cart music av_forward refresh save copy launch group font skip_next history location_crosshair "
        "edit swap attach_file skip_previous facebook globe thumbs_down description notifications wallpaper bluetooth "
        "network_wifi compare home fullscreen check twitter national_flag microphone sliders favorite menu zoom_out label "
        "bookmark switcher").split()
assert len(UIST) == 99
# the word Laya sees for a class (default: the name with spaces)
WORDS = {"arrow_backward": "back", "avatar": "profile", "more": "more options", "av_rewind": "rewind",
         "av_forward": "fast forward", "thumbs_up": "like", "thumbs_down": "dislike", "file_download": "download",
         "location_crosshair": "my location", "national_flag": "flag", "switcher": "switch", "sliders": "adjust",
         "launch": "open", "minus": "remove", "follow": "follow", "font": "text", "time": "time", "globe": "language"}
# classes the real icons lack: rendered only (Material Symbols names; missing names are skipped)
ADDED = {"qr_code": ["qr_code", "qr_code_2"], "scan": ["qr_code_scanner", "document_scanner", "barcode_reader"],
         "camera": ["photo_camera", "camera"], "wallet": ["account_balance_wallet", "wallet"],
         "rupee": ["currency_rupee"], "translate": ["translate", "g_translate"], "apps": ["apps"], "sort": ["sort"]}
# today's look for the real classes
ALSO = {"search": ["search"], "menu": ["menu"], "settings": ["settings"], "share": ["share"], "cart": ["shopping_cart"],
        "more": ["more_vert", "more_horiz"], "close": ["close"], "filter_list": ["filter_list"], "filter": ["filter_alt"],
        "arrow_backward": ["arrow_back", "arrow_back_ios"], "avatar": ["account_circle", "person"], "home": ["home"],
        "favorite": ["favorite"], "notifications": ["notifications"], "microphone": ["mic"], "edit": ["edit"],
        "delete": ["delete"], "add": ["add"], "refresh": ["refresh"], "info": ["info"], "help": ["help"], "star": ["star"],
        "check": ["check"], "play": ["play_arrow"], "pause": ["pause"], "call": ["call"], "chat": ["chat"], "send": ["send"],
        "email": ["mail"], "location": ["location_on"], "bookmark": ["bookmark"], "history": ["history"],
        "file_download": ["download"], "expand_more": ["expand_more"], "expand_less": ["expand_less"],
        "arrow_forward": ["arrow_forward"], "arrow_upward": ["arrow_upward"], "arrow_downward": ["arrow_downward"],
        "lock": ["lock"], "videocam": ["videocam"], "music": ["music_note"], "photo": ["image"], "list": ["list"],
        "dashboard": ["dashboard"], "folder": ["folder"], "visibility": ["visibility"], "volume": ["volume_up"],
        "thumbs_up": ["thumb_up"], "thumbs_down": ["thumb_down"], "globe": ["language", "public"],
        "bluetooth": ["bluetooth"], "network_wifi": ["wifi"], "fullscreen": ["fullscreen"], "zoom_out": ["zoom_out"],
        "label": ["label"], "switcher": ["toggle_on"], "sliders": ["tune"], "flash": ["flash_on"], "stop": ["stop"],
        "repeat": ["repeat"], "reply": ["reply"], "undo": ["undo"], "redo": ["redo"], "skip_next": ["skip_next"],
        "skip_previous": ["skip_previous"], "attach_file": ["attach_file"], "save": ["save"], "copy": ["content_copy"],
        "launch": ["open_in_new"], "group": ["group"], "dialpad": ["dialpad"], "power": ["power_settings_new"],
        "gift": ["redeem"], "time": ["schedule"], "layers": ["layers"], "description": ["description"],
        "warning": ["warning"], "build": ["build"], "flight": ["flight"], "weather": ["sunny"], "date_range": ["date_range"],
        "explore": ["explore"], "navigation": ["navigation"], "emoji": ["mood"], "shop": ["store"],
        "wallpaper": ["wallpaper"], "compare": ["compare"], "national_flag": ["flag"], "location_crosshair": ["my_location"],
        "font": ["text_fields"], "playlist": ["playlist_play"], "av_rewind": ["fast_rewind"], "av_forward": ["fast_forward"],
        "minus": ["remove"], "follow": ["person_add"], "swap": ["swap_horiz"]}
OTHER_TEXT = ("Sign in|Buy now|Add to cart|Continue|Cancel|OK|Next|Skip|Done|Apply|View all|See more|Hello|Offers|"
              "Today|New|Free|50% off|4.3|12:45|Rs 299|Order|Pay|Track|Help|Ab|Q|Hi").split("|")


def fetch(url, path):
    if path.exists() and path.stat().st_size > 0:
        return
    print("download", path.name, flush=True)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=600) as r, open(path, "wb") as f:
        shutil.copyfileobj(r, f)


def real_icons():
    """UIST'18 crops as (x_train, y_train, x_test, y_test): 64x64 uint8, int labels."""
    z = D / "uist18_data.zip"
    fetch(DATA_ZIP, z)
    assert zipfile.is_zipfile(z), "data.zip didn't download as a zip (Drive's warning page?): fetch it by hand into data/"
    with zipfile.ZipFile(z) as zf:
        def arr(name):
            return np.load(io.BytesIO(zf.read("data/" + name)))
        names = json.loads(zf.read("data/validation_metadata.json"))["class_names"]
        assert names == UIST, "class order differs from research/25"
        out = []
        for xs, ys in (("training_x.npy", "training_y.npy"), ("validation_x.npy", "validation_y.npy")):
            x, y = arr(xs), arr(ys)
            x = x.reshape(len(x), 32, 32).astype(np.float32)
            x = x * 255 if x.max() <= 1.5 else x
            big = F.interpolate(torch.from_numpy(x)[:, None], size=SIZE, mode="bilinear", align_corners=False)[:, 0]
            y = y.argmax(1) if y.ndim == 2 else y
            out += [big.clamp(0, 255).round().to(torch.uint8).numpy(), y.astype(np.int64)]
            print("real", xs, out[-2].shape, flush=True)
    return out


def render_glyph(font, cp, rng):
    f = ImageFont.truetype(str(font), size=rng.randint(30, 50))
    try:
        f.set_variation_by_axes([rng.choice([0, 1]), 0, 48, rng.choice([300, 400, 500, 600])])  # FILL GRAD opsz wght
    except Exception:
        pass
    return draw(lambda d, xy, fg: d.text(xy, chr(cp), fill=fg, font=f), lambda d: d.textbbox((0, 0), chr(cp), font=f), rng)


def draw(paint, bbox, rng):
    """An icon on a light or dark ground, sometimes in a round or rounded button, sometimes blurred or low-res."""
    dark = rng.random() < 0.5
    fg, bg = (rng.randint(170, 255), rng.randint(0, 80)) if dark else (rng.randint(0, 90), rng.randint(175, 255))
    im = Image.new("L", (SIZE, SIZE), bg)
    d = ImageDraw.Draw(im)
    if rng.random() < 0.3:
        m = rng.randint(1, 8)
        shade = max(0, min(255, bg + rng.choice([-45, 45])))
        if rng.random() < 0.5:
            d.ellipse((m, m, SIZE - m, SIZE - m), fill=shade)
        else:
            d.rounded_rectangle((m, m, SIZE - m, SIZE - m), radius=rng.randint(4, 16), fill=shade)
    l, t, r, b = bbox(d)
    paint(d, ((SIZE - (r - l)) // 2 - l + rng.randint(-4, 4), (SIZE - (b - t)) // 2 - t + rng.randint(-4, 4)), fg)
    if rng.random() < 0.3:
        im = im.filter(ImageFilter.GaussianBlur(rng.uniform(0.3, 1.0)))
    if rng.random() < 0.3:
        s = rng.randint(20, 40)
        im = im.resize((s, s), Image.BILINEAR).resize((SIZE, SIZE), Image.BILINEAR)
    return np.asarray(im, np.uint8)


def render_text(text, rng):
    f = ImageFont.load_default(size=rng.randint(12, 26))
    return draw(lambda d, xy, fg: d.text(xy, text, fill=fg, font=f), lambda d: d.textbbox((0, 0), text, font=f), rng)


def main():
    D.mkdir(exist_ok=True)
    rng = random.Random(0)
    x_tr, y_tr, x_te, y_te = real_icons()
    classes = UIST + list(ADDED) + ["other"]
    idx = {c: i for i, c in enumerate(classes)}
    fonts, cps = [], {}
    for name in FONTS:
        url = MS + name.replace("[", "%5B").replace("]", "%5D").replace(",", "%2C")
        fetch(url + ".ttf", D / (name + ".ttf"))
        fetch(url + ".codepoints", D / (name + ".codepoints"))
        fonts.append(D / (name + ".ttf"))
        for line in (D / (name + ".codepoints")).read_text().splitlines():
            n, h = line.split()
            cps[n] = int(h, 16)
    used = {g for gs in list(ADDED.values()) + list(ALSO.values()) for g in gs}
    plan = [(c, g, 400) for c, gs in ADDED.items() for g in gs if g in cps]
    plan += [(c, g, 120) for c, gs in ALSO.items() for g in gs if g in cps]
    missing = [g for gs in list(ADDED.values()) + list(ALSO.values()) for g in gs if g not in cps]
    print("glyphs missing from Material Symbols (skipped):", missing, flush=True)
    rx, ry = [], []
    for c, g, n in plan:
        for _ in range(n // max(1, sum(1 for c2, _, _ in plan if c2 == c))):
            rx.append(render_glyph(rng.choice(fonts), cps[g], rng))
            ry.append(idx[c])
    others = rng.sample(sorted(set(cps) - used), 600)
    for g in others:
        for _ in range(4):
            rx.append(render_glyph(rng.choice(fonts), cps[g], rng))
            ry.append(idx["other"])
    for _ in range(1200):
        rx.append(render_text(rng.choice(OTHER_TEXT), rng))
        ry.append(idx["other"])
    rx, ry = np.stack(rx), np.array(ry, np.int64)
    order = np.random.default_rng(0).permutation(len(rx))
    held = order[: len(rx) // 10]
    keep = order[len(rx) // 10:]
    np.savez_compressed(D / "icons.npz", x_train=np.concatenate([x_tr, rx[keep]]), y_train=np.concatenate([y_tr, ry[keep]]),
                        x_test_real=x_te, y_test_real=y_te, x_test_render=rx[held], y_test_render=ry[held])
    (D / "classes.json").write_text(json.dumps({"classes": classes, "words": [WORDS.get(c, c.replace("_", " ")) for c in classes]}))
    print("classes", len(classes), "| train", len(x_tr) + len(keep), "| test real", len(x_te), "| test renders", len(held), flush=True)


if __name__ == "__main__":
    main()
