"""Build the Flask of Wondrous Physick and Crystal Tear pixel art.

Writes the flask item layers (glass and gold frame, two greyscale liquid halves
that the item model tints from the mixture's colours, and an empty flask), one
16x16 GUI sprite per tear for the mixing screen, and an 18x18 status effect
icon for every tear EldenCraft registers its own effect for. Tear IDs, colours
and order match dev.eldencraft.bridge.PhysickTears.
"""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "minecraft/src/main/resources/assets/eldencraft_bridge"

# (id, colour, shape). Shapes add the details that tell tear families apart.
TEARS = [
    ("crimson", 0xD0313D, "plain"),
    ("crimsonspill", 0xE0505A, "spill"),
    ("crimsonburst", 0xEF4A3A, "burst"),
    ("cerulean", 0x3D7BE0, "plain"),
    ("greenspill", 0x4FBF5A, "spill"),
    ("greenburst", 0x8BD650, "burst"),
    ("strength_knot", 0xD98B4A, "knot"),
    ("dexterity_knot", 0xE3CF5A, "knot"),
    ("intelligence_knot", 0x6D8FE8, "knot"),
    ("faith_knot", 0xF0D89A, "knot"),
    ("opaline_hard", 0xCFE3EA, "hard"),
    ("speckled", 0xE6E0CF, "speckled"),
    ("leaden", 0x8A8F96, "hard"),
    ("magic_shrouding", 0x6F8CF0, "cracked"),
    ("flame_shrouding", 0xF08A3C, "cracked"),
    ("lightning_shrouding", 0xF2DD52, "cracked"),
    ("holy_shrouding", 0xF6E9B0, "cracked"),
    ("stonebarb", 0xA3927A, "cracked"),
    ("spiked", 0xC25B4A, "cracked"),
    ("thorny", 0x7D9A4A, "cracked"),
    ("twiggy", 0x9B7A4F, "cracked"),
    ("winged", 0xBFE8F2, "plain"),
    ("windy", 0xA8E0B8, "plain"),
    ("crimson_bubble", 0xE04868, "bubble"),
    ("crimsonwhorl", 0x9C1C2C, "whorl"),
    ("opaline_bubble", 0xA9D8F0, "bubble"),
    ("cerulean_hidden", 0x2C48A8, "hidden"),
    ("purifying", 0xFFF6D8, "plain"),
    ("ruptured", 0xFF7A2E, "cracked"),
]

# Tears whose effect is vanilla or instant have no EldenCraft status icon.
VANILLA_OR_INSTANT = {
    "crimson",
    "cerulean",
    "ruptured",
    "crimsonspill",
    "crimsonburst",
    "strength_knot",
    "dexterity_knot",
    "opaline_hard",
    "winged",
    "windy",
}

DROP = [
    "................",
    ".......##.......",
    ".......##.......",
    "......####......",
    "......####......",
    ".....######.....",
    ".....######.....",
    "....########....",
    "...##########...",
    "...##########...",
    "...##########...",
    "...##########...",
    "....########....",
    ".....######.....",
    "................",
    "................",
]
ORB = [
    "................",
    "................",
    ".....######.....",
    "....########....",
    "...##########...",
    "..############..",
    "..############..",
    "..############..",
    "..############..",
    "..############..",
    "..############..",
    "...##########...",
    "....########....",
    ".....######.....",
    "................",
    "................",
]
# k gold shade, Y gold, y gold light, g glass edge, w glass shine, a empty
# glass, L/M left and right liquid, h liquid shine.
FLASK = [
    "......kkkk......",
    ".....kYyyYk.....",
    ".....kYYYYk.....",
    "......kkkk......",
    "......gwag......",
    "......gwag......",
    ".....gwaaag.....",
    "....gwaaaaag....",
    "...gYYYYYYYYg...",
    "..gwLLLLMMMMMg..",
    "..gLhLLLMMMMMg..",
    "..gLhLLLMMMMMg..",
    "..gLLLLMMMMMMg..",
    "...gLLLMMMMMg...",
    "....gLLMMMMg....",
    ".....gggggg.....",
]
FRAME = {
    "k": (90, 63, 18, 255),
    "Y": (201, 150, 46, 255),
    "y": (243, 210, 122, 255),
    "g": (70, 80, 95, 255),
    "w": (233, 243, 255, 255),
    "a": (200, 225, 240, 90),
}


def rgb(value: int) -> tuple[int, int, int]:
    return (value >> 16) & 255, (value >> 8) & 255, value & 255


def mix(a, b, t: float) -> tuple[int, int, int]:
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def tear(colour: int, shape: str) -> Image.Image:
    mask = ORB if shape in ("bubble", "whorl") else DROP
    base = rgb(colour)
    light = mix(base, (255, 255, 255), 0.42)
    dark = mix(base, (0, 0, 0), 0.34)
    edge = mix(base, (0, 0, 0), 0.66)
    shine = mix(base, (255, 255, 255), 0.85)
    image = Image.new("RGBA", (16, 16))

    def on(x: int, y: int) -> bool:
        return 0 <= x < 16 and 0 <= y < 16 and mask[y][x] == "#"

    def put(x: int, y: int, c) -> None:
        image.putpixel((x, y), tuple(c) + (255,))

    def paint(x: int, y: int, c) -> None:
        if on(x, y):
            put(x, y, c)

    for y in range(16):
        for x in range(16):
            if not on(x, y):
                continue
            if not all(on(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                put(x, y, edge)
                continue
            d = (x - 7.5) + (y - 9) * 0.7
            c = light if d < -2.6 else dark if d > 2.2 else base
            if shape == "bubble":
                c = mix(c, (255, 255, 255), 0.18)
            put(x, y, c)
    if mask is DROP:
        for x, y in ((5, 8), (5, 9), (6, 7)):
            paint(x, y, shine)
    else:
        for x, y in ((5, 4), (4, 5), (4, 6)):
            paint(x, y, shine)
    if shape == "spill":
        put(11, 13, base)
        put(11, 14, base)
        put(11, 15, dark)
    if shape == "burst":
        for x, y in ((2, 4), (13, 3), (14, 12), (1, 11), (12, 1)):
            put(x, y, light)
    if shape == "knot":
        for x in range(4, 12):
            paint(x, 10, mix(base, (255, 255, 255), 0.7))
        for x, y in ((7, 9), (8, 9), (7, 11), (8, 11)):
            paint(x, y, edge)
    if shape in ("hard", "speckled"):
        for x, y in ((4, 11), (5, 10), (6, 9), (7, 8)):
            paint(x, y, shine)
        for x, y in ((8, 8), (9, 9), (10, 10), (11, 11)):
            paint(x, y, dark)
    if shape == "speckled":
        for x, y in ((6, 11), (9, 7), (10, 11), (8, 12), (5, 9)):
            paint(x, y, (90, 80, 70))
    if shape == "cracked":
        for x, y in ((8, 3), (8, 4), (7, 5), (7, 6), (8, 7), (9, 8), (8, 9), (8, 10), (7, 11), (7, 12)):
            paint(x, y, edge)
    if shape == "hidden":
        for y in range(8, 12):
            for x in range(6, 10):
                paint(x, y, edge)
        paint(7, 9, light)
    if shape == "whorl":
        for x, y in ((7, 6), (8, 6), (9, 7), (9, 8), (8, 9), (7, 9), (6, 8), (6, 7), (10, 9), (10, 10), (9, 11)):
            paint(x, y, edge)
    return image


def flask(layer: str) -> Image.Image:
    """layer: frame, empty, left or right."""
    image = Image.new("RGBA", (16, 16))
    for y, row in enumerate(FLASK):
        for x, ch in enumerate(row):
            value = None
            if ch in "kYygw" or (ch == "a" and layer in ("frame", "empty")):
                value = FRAME[ch] if layer in ("frame", "empty") else None
            elif ch in "LMh":
                if layer == "empty":
                    value = FRAME["a"]
                elif (layer == "left" and ch in "Lh") or (layer == "right" and ch == "M"):
                    tone = 255 if ch == "h" else 226 if y < 12 else 196
                    value = (tone, tone, tone, 255)
            if value:
                image.putpixel((x, y), value)
    return image


def save(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)


def generate() -> None:
    item = ASSETS / "textures/item"
    save(flask("frame"), item / "wondrous_physick.png")
    save(flask("empty"), item / "wondrous_physick_empty.png")
    save(flask("left"), item / "wondrous_physick_left.png")
    save(flask("right"), item / "wondrous_physick_right.png")
    for tear_id, colour, shape in TEARS:
        icon = tear(colour, shape)
        save(icon, ASSETS / f"textures/gui/sprites/physick/{tear_id}.png")
        if tear_id not in VANILLA_OR_INSTANT:
            effect = Image.new("RGBA", (18, 18))
            effect.alpha_composite(icon, (1, 1))
            save(effect, ASSETS / f"textures/mob_effect/{tear_id}.png")


if __name__ == "__main__":
    generate()
