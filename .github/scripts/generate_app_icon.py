from pathlib import Path
import sys
# Generate the launcher artwork in all legacy densities and as an Android
# adaptive icon. Pillow is provided by the Ubuntu runner's python3-pil package.
from PIL import Image, ImageDraw, ImageFont

root = Path(sys.argv[1])
res = root / "app" / "src" / "main" / "res"
res.mkdir(parents=True, exist_ok=True)

def font(size):
    candidates = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf",
    ]
    for candidate in candidates:
        if Path(candidate).exists():
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default()

def draw_mark(size, transparent=False):
    scale = size / 512
    bg = (0, 0, 0, 0) if transparent else (0, 0, 0, 255)
    im = Image.new("RGBA", (size, size), bg)
    d = ImageDraw.Draw(im)
    def p(points):
        return [(int(x*scale), int(y*scale)) for x,y in points]
    yellow=(255,224,0,255); black=(0,0,0,255)
    # Strong yellow caution-sign frame and triangular field
    if not transparent:
        d.rounded_rectangle((3*scale,3*scale,509*scale,509*scale),
            radius=28*scale, outline=yellow, width=max(2,int(10*scale)))
    d.polygon(p([(256,48),(468,420),(44,420)]), fill=yellow)
    d.line(p([(256,48),(468,420),(44,420),(256,48)]), fill=black, width=max(3,int(16*scale)), joint="curve")
    # Headband and ear cups
    d.arc((151*scale,137*scale,361*scale,354*scale), 190, 350,
          fill=black, width=max(5,int(23*scale)))
    d.rounded_rectangle((125*scale,239*scale,190*scale,362*scale),
        radius=23*scale, fill=black)
    d.rounded_rectangle((322*scale,239*scale,387*scale,362*scale),
        radius=23*scale, fill=black)
    d.rounded_rectangle((140*scale,251*scale,177*scale,347*scale),
        radius=13*scale, fill=yellow)
    d.rounded_rectangle((335*scale,251*scale,372*scale,347*scale),
        radius=13*scale, fill=yellow)
    # Bold EQ lettering
    f=font(int(105*scale))
    box=d.textbbox((0,0),"EQ",font=f,stroke_width=0)
    tw=box[2]-box[0]; th=box[3]-box[1]
    d.text(((size-tw)/2, int(245*scale)-th/2-box[1]), "EQ", font=f, fill=black)
    # Audio level bars below the lettering
    heights=[15,25,38,55,72,88,70,52,38,25,15]
    center=256
    for i,h in enumerate(heights):
        x=center+(i-5)*14
        d.rectangle((int((x-3)*scale),int((399-h)*scale),int((x+3)*scale),int(399*scale)),fill=black)
    return im

# Legacy launcher icons
for density, px in [("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)]:
    folder=res / f"mipmap-{density}"
    folder.mkdir(parents=True, exist_ok=True)
    draw_mark(px).save(folder / "ic_launcher.png", optimize=True)
    draw_mark(px).save(folder / "ic_launcher_round.png", optimize=True)

# Adaptive icon foreground uses transparent pixels outside the emblem.
folder=res / "drawable-nodpi"
folder.mkdir(parents=True, exist_ok=True)
draw_mark(432, transparent=True).save(folder / "ic_launcher_foreground.png", optimize=True)
folder=res / "mipmap-anydpi-v26"
folder.mkdir(parents=True, exist_ok=True)
(folder / "ic_launcher.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\\n'
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\\n'
    '    <background android:drawable="@color/ic_launcher_background" />\\n'
    '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\\n'
    '</adaptive-icon>\\n', encoding="utf-8")
folder=res / "values"
folder.mkdir(parents=True, exist_ok=True)
colors=folder / "ic_launcher_background.xml"
colors.write_text(
    '<?xml version="1.0" encoding="utf-8"?>\\n'
    '<resources><color name="ic_launcher_background">#000000</color></resources>\\n',
    encoding="utf-8")

# Point both regular and round launcher entries at the new icon.
manifest=root / "app" / "src" / "main" / "AndroidManifest.xml"
if manifest.exists():
    import xml.etree.ElementTree as ET
    ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
    tree=ET.parse(manifest)
    app=tree.getroot().find("application")
    if app is not None:
        ns="{http://schemas.android.com/apk/res/android}"
        app.set(ns+"icon", "@mipmap/ic_launcher")
        app.set(ns+"roundIcon", "@mipmap/ic_launcher_round")
        tree.write(manifest, encoding="utf-8", xml_declaration=True)
else:
    raise SystemExit(f"AndroidManifest.xml not found: {manifest}")
