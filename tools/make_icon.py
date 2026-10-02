#!/usr/bin/env python3
"""Génère l'icône du lanceur (un Kraton bleu) en PNG, sans dépendance externe."""
import math, os, struct, zlib

def png(path, w, h, px):
    raw = b''.join(b'\x00' + bytes(px[y * w * 4:(y + 1) * w * 4]) for y in range(h))
    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    data += chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'wb').write(data)

def shade(x, y):
    """Couleur RGBA en coordonnées normalisées [-1, 1]."""
    r2 = x * x + y * y
    col = None
    if r2 <= 1.0:  # fond : disque vert-herbe avec dégradé
        t = (y + 1) / 2
        col = (int(96 - 30 * t), int(170 - 40 * t), int(90 - 30 * t), 255)
        if r2 > 0.92 ** 2:
            col = (40, 80, 50, 255)
    # ombre
    if ((x) / 0.5) ** 2 + ((y - 0.55) / 0.13) ** 2 <= 1:
        col = (40, 70, 40, 255)
    # pousse
    if ((x - 0.05) / 0.11) ** 2 + ((y + 0.62) / 0.16) ** 2 <= 1:
        col = (170, 205, 255, 255)
    # corps
    bx, by = x / 0.55, (y - 0.05) / 0.56
    if bx * bx + by * by <= 1:
        col = (58, 128, 232, 255)
        if bx * bx + by * by > 0.88 ** 2:
            col = (28, 64, 140, 255)
        elif (x / 0.38) ** 2 + ((y - 0.3) / 0.25) ** 2 <= 1:
            col = (150, 190, 250, 255)
        for ex in (-0.19, 0.19):
            if ((x - ex) / 0.15) ** 2 + ((y + 0.07) / 0.15) ** 2 <= 1:
                col = (255, 255, 255, 255)
                if ((x - ex - 0.04) / 0.07) ** 2 + ((y + 0.05) / 0.07) ** 2 <= 1:
                    col = (20, 20, 30, 255)
    return col

def render(size):
    ss = 4
    px = bytearray(size * size * 4)
    for j in range(size):
        for i in range(size):
            acc = [0, 0, 0, 0]
            for sj in range(ss):
                for si in range(ss):
                    x = ((i + (si + 0.5) / ss) / size) * 2 - 1
                    y = ((j + (sj + 0.5) / ss) / size) * 2 - 1
                    c = shade(x, y)
                    if c:
                        a = c[3]
                        acc[0] += c[0] * a; acc[1] += c[1] * a; acc[2] += c[2] * a; acc[3] += a
            n = ss * ss
            a = acc[3] / n
            o = (j * size + i) * 4
            if acc[3] > 0:
                px[o] = int(acc[0] / acc[3]); px[o + 1] = int(acc[1] / acc[3]); px[o + 2] = int(acc[2] / acc[3])
            px[o + 3] = int(a)
    return px

root = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res')
for name, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
    png(os.path.join(root, 'mipmap-' + name, 'ic_launcher.png'), size, size, render(size))
print('ok')
