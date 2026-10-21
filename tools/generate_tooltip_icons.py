"""Build the campaign's original pixel-art glyphs; Minecraft tints each icon.

Every glyph is drawn on the same 9x9 grid as the bitmap provider height, so one
texel is exactly one GUI pixel at every GUI scale. Shading uses grey levels that
the text colour multiplies, and the shadow comes from Minecraft's text shadow.
"""

from pathlib import Path
import json

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "minecraft/src/main/resources/assets/eldencraft_bridge"
SIZE = 9
# Light, mid and shade tones; the tooltip colour multiplies each grey level.
TONES = {"#": 255, "o": 214, "x": 158}
# Invisible width marker: Minecraft measures glyph advance from the rightmost
# texel with non-zero alpha, while its text shader discards alpha below 0.1.
MARKER = (255, 255, 255, 1)
FIRST = 0xE000
# Every icon advances exactly the same distance, so labels and indents align.
ADVANCE = SIZE + 1

# Order matches CampaignTooltipTheme.Icon; each glyph is codepoint FIRST + index.
GLYPHS = {
    "DAMAGE": [
        ".......##",
        "......#o#",
        ".....#o#.",
        "..#.#o#..",
        "...#o#...",
        "...x#....",
        "..x..#...",
        ".x.......",
        "#........",
    ],
    "SPEED": [
        "...###...",
        "....#....",
        "..#####..",
        ".#xxxxx#.",
        "#xxx#xxx#",
        "#xxx#xxx#",
        "#xxxx##x#",
        ".#xxxxx#.",
        "..#####..",
    ],
    "STAMINA": [
        "......##.",
        ".......#.",
        "######.#.",
        "......#..",
        "#######..",
        ".........",
        "#####....",
        ".....#...",
        "....#....",
    ],
    "RUNES": [
        "....#....",
        "..##o##..",
        ".#..o..#.",
        "#..ooo..#",
        "#...o...#",
        "#...o...#",
        ".#..o..#.",
        "..##o##..",
        "....#....",
    ],
    "POISON": [
        "..#####..",
        "...#o#...",
        "...#o#...",
        "..#ooo#..",
        ".#ooooo#.",
        "#xx#xxxx#",
        "#xxxxx#x#",
        ".#xxxxx#.",
        "..#####..",
    ],
    "BLEEDING": [
        "....#....",
        "....#....",
        "...#o#...",
        "...#o#...",
        "..#ooo#..",
        ".##oooo#.",
        ".#oooox#.",
        ".#ooxxx#.",
        "..#####..",
    ],
    "FROSTBITE": [
        "....#....",
        ".#..#..#.",
        "..#.o.#..",
        "...#o#...",
        "#oooxooo#",
        "...#o#...",
        "..#.o.#..",
        ".#..#..#.",
        "....#....",
    ],
    "PROTECTION": [
        "#########",
        "#xxxoxxx#",
        "#xxxoxxx#",
        "#ooooooo#",
        "#xxxoxxx#",
        ".#xxoxx#.",
        ".#xxoxx#.",
        "..#xox#..",
        "...###...",
    ],
    "UNBREAKING": [
        ".........",
        ".........",
        "#########",
        ".##ooooo#",
        "...#oo#..",
        "...#oo#..",
        "..#xxxx#.",
        ".########",
        ".........",
    ],
    "WHETBLADE": [
        "....#....",
        "...#o#...",
        "...#o#...",
        "...#o#...",
        "...#o#...",
        ".#######.",
        "...#x#...",
        "...#x#...",
        "....#....",
    ],
    "LOCK": [
        "...###...",
        "..#...#..",
        "..#...#..",
        ".#######.",
        ".#ooooo#.",
        ".#oo#oo#.",
        ".#oo#oo#.",
        ".#xxxxx#.",
        ".#######.",
    ],
    "FOUND": [
        ".........",
        ".......##",
        "......##.",
        ".....##..",
        "##..##...",
        ".####....",
        "..##.....",
        ".........",
        ".........",
    ],
    "AFFINITY": [
        "....#....",
        "...#o#...",
        "..#o#o#..",
        ".#o#x#o#.",
        "#o#xxx#o#",
        ".#o#x#o#.",
        "..#o#o#..",
        "...#o#...",
        "....#....",
    ],
    "ORNAMENT": [
        ".........",
        ".........",
        "......#..",
        ".....#.#.",
        "xoo##.o.#",
        ".....#.#.",
        "......#..",
        ".........",
        ".........",
    ],
    "LAPIS": [
        "...###...",
        "..#ooo#..",
        ".#o#o#o#.",
        "#ooo#ooo#",
        "#xxx#xxx#",
        ".#xx#xx#.",
        "..#x#x#..",
        "...#x#...",
        "....#....",
    ],
    "EXPERIENCE": [
        "....#....",
        "....#....",
        "...#o#...",
        "..#ooo#..",
        "##ooooo##",
        "..#ooo#..",
        "...#o#...",
        "....#....",
        "....#....",
    ],
    "ORNAMENT_END": [
        ".........",
        ".........",
        "..#......",
        ".#.#.....",
        "#.o.##oox",
        ".#.#.....",
        "..#......",
        ".........",
        ".........",
    ],
    "FIRE": [
        "....#....",
        "...##....",
        "...#o#.#.",
        "..#oo##o.",
        ".#oo#oo#.",
        ".#o#x#o#.",
        "#oo#xx#o#",
        "#o#xxx#o#",
        ".#######.",
    ],
    "LIGHTNING": [
        ".....####",
        "....###..",
        "...###...",
        "..######.",
        "....###..",
        "...###...",
        "..##.....",
        ".##......",
        ".#.......",
    ],
    "MAGIC": [
        "..#####..",
        ".#.....#.",
        "#...#...#",
        "#..#o#..#",
        "#.#ooo#.#",
        "#..#o#..#",
        "#...#...#",
        ".#.....#.",
        "..#####..",
    ],
    "HOLY": [
        "....#....",
        ".#..#..#.",
        "..#ooo#..",
        "..o###o..",
        "##o###o##",
        "..o###o..",
        "..#ooo#..",
        ".#..#..#.",
        "....#....",
    ],
    "HEALING": [
        "...###...",
        "...#o#...",
        "...#o#...",
        "###ooo###",
        "#ooooooo#",
        "###ooo###",
        "...#o#...",
        "...#o#...",
        "...###...",
    ],
    "BLAST": [
        ".....#.#.",
        "......#..",
        ".....#...",
        "...###...",
        "..#ooo#..",
        ".#ooooo#.",
        ".#oooxx#.",
        ".#oxxxx#.",
        "..#####..",
    ],
    "BELL": [
        "....#....",
        "...###...",
        "..#ooo#..",
        "..#ooo#..",
        ".#ooooo#.",
        ".#ooooo#.",
        "#########",
        "....x....",
        ".........",
    ],
}


def cell(rows: list[str]) -> Image.Image:
    if len(rows) != SIZE or any(len(row) != SIZE for row in rows):
        raise ValueError("glyphs must be 9x9")
    image = Image.new("RGBA", (SIZE, SIZE))
    for y, row in enumerate(rows):
        for x, tone in enumerate(row):
            if tone != ".":
                image.putpixel((x, y), (TONES[tone],) * 3 + (255,))
    if all(row[-1] == "." for row in rows):
        image.putpixel((SIZE - 1, SIZE // 2), MARKER)
    return image


def generate() -> None:
    atlas = Image.new("RGBA", (SIZE * len(GLYPHS), SIZE))
    for index, rows in enumerate(GLYPHS.values()):
        atlas.alpha_composite(cell(rows), (index * SIZE, 0))
    texture = ASSETS / "textures/font/icons.png"
    texture.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(texture)
    chars = "".join(chr(FIRST + index) for index in range(len(GLYPHS)))
    providers = [
        {
            "type": "bitmap",
            "file": "eldencraft_bridge:font/icons.png",
            # Height equals the cell size: no resampling. Ascent 8 centres the
            # glyph on Minecraft's 7-pixel capitals, one pixel above and below.
            "ascent": SIZE - 1,
            "height": SIZE,
            "chars": [chars],
        },
        # A blank glyph with an icon's advance indents text under labels.
        {"type": "space", "advances": {chr(FIRST + 0xFF): ADVANCE}},
    ]
    font = ASSETS / "font/icons.json"
    font.parent.mkdir(parents=True, exist_ok=True)
    font.write_text(
        json.dumps({"providers": providers}, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )


if __name__ == "__main__":
    generate()
