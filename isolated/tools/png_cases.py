"""bench.pngCheck で使う PNG と、ほかの実装で求めた RGBA を作る。

    python3 -m pip install pypng pillow
    python3 tools/png_cases.py /tmp/pngcases
    sbt "demo/runMain bench.pngCheck /tmp/pngcases"

- pypng: 色の種類とビット深度の組み合わせをすべて (パレットの tRNS も)
- Pillow: 行ごとにフィルターを選ぶ PNG (左・上・Paeth が出る)
- 自前の書き出し: 全行を同じフィルター (なし・左・上・平均・Paeth) にした PNG
"""
import os, random, struct, sys, zlib

import png
from PIL import Image

d = sys.argv[1]
os.makedirs(d, exist_ok=True)
random.seed(7)


def pypng_cases():
    for ct, depths in [(0, [1, 2, 4, 8, 16]), (2, [8, 16]), (3, [1, 2, 4, 8]), (4, [8, 16]), (6, [8, 16])]:
        for depth in depths:
            w, h = 13, 7
            ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ct]
            mx = (1 << depth) - 1
            kw = dict(bitdepth=depth)
            pal = None
            if ct == 3:
                pal = [tuple(random.randrange(256) for _ in range(4)) for _ in range(1 << depth)]
                kw["palette"] = pal
            else:
                kw["greyscale"] = ct in (0, 4)
                kw["alpha"] = ct in (4, 6)
            rows = [[random.randrange(mx + 1) for _ in range(w * ch)] for _ in range(h)]
            rows[3] = rows[2][:]
            hi = lambda c: c >> 8 if depth == 16 else c
            out = bytearray()
            for row in rows:
                if ct == 0:
                    for v in row:
                        g = hi(v) if depth >= 8 else v * 255 // mx
                        out += bytes([g, g, g, 255])
                elif ct == 3:
                    for v in row:
                        out += bytes(pal[v])
                else:
                    for i in range(0, len(row), ch):
                        px = [hi(c) for c in row[i:i + ch]]
                        if ct == 2:
                            px += [255]
                        elif ct == 4:
                            px = [px[0], px[0], px[0], px[1]]
                        out += bytes(px)
            name = f"{d}/ct{ct}_d{depth}"
            with open(name + ".png", "wb") as f:
                png.Writer(w, h, **kw).write(f, rows)
            open(name + ".rgba", "wb").write(out)


def pillow_cases():
    w, h = 97, 61
    for mode in ["RGBA", "RGB", "L", "LA"]:
        im = Image.new(mode, (w, h))
        px = im.load()
        for y in range(h):
            for x in range(w):
                v = [(x * 37 + y * 11) % 256, (y * 29 + x * x) % 256, random.randrange(4) * 60, (x * 5 + y) % 256]
                px[x, y] = tuple(v[:len(mode)]) if len(mode) > 1 else v[0]
        im.save(f"{d}/pil_{mode}.png")
        open(f"{d}/pil_{mode}.rgba", "wb").write(im.convert("RGBA").tobytes())


def filter_cases():
    w, h, bpp = 23, 9, 4
    rows = [bytes(random.randrange(256) if random.random() < 0.5 else (x * 7 + y * 3) % 256 for x in range(w * bpp)) for y in range(h)]

    def paeth(a, b, c):
        p = a + b - c
        pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
        return a if pa <= pb and pa <= pc else (b if pb <= pc else c)

    def filt(t, row, prev):
        out = bytearray([t])
        for i, x in enumerate(row):
            a = row[i - bpp] if i >= bpp else 0
            b = prev[i] if prev else 0
            c = prev[i - bpp] if prev and i >= bpp else 0
            out.append((x - [0, a, b, (a + b) // 2, paeth(a, b, c)][t]) & 0xff)
        return bytes(out)

    def chunk(t, data):
        return struct.pack(">I", len(data)) + t + data + struct.pack(">I", zlib.crc32(t + data))

    for t in range(5):
        raw = b"".join(filt(t, rows[y], rows[y - 1] if y else None) for y in range(h))
        body = chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
        open(f"{d}/filter{t}.png", "wb").write(b"\x89PNG\r\n\x1a\n" + body)
        open(f"{d}/filter{t}.rgba", "wb").write(b"".join(rows))


pypng_cases()
pillow_cases()
filter_cases()
print(f"{len(os.listdir(d)) // 2} 組を {d} に書いた")
