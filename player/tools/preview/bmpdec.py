import struct
from PIL import Image

def decode_bmp(path):
    """Decodes BI_RGB and BI_RLE8 BMPs. Mirrors the Java implementation exactly."""
    d = open(path, "rb").read()
    if d[:2] != b"BM": raise ValueError("not a BMP")
    dataOff = struct.unpack_from("<I", d, 10)[0]
    hdrSize = struct.unpack_from("<I", d, 14)[0]
    w, h = struct.unpack_from("<ii", d, 18)
    bpp = struct.unpack_from("<H", d, 28)[0]
    comp = struct.unpack_from("<I", d, 30)[0]
    used = struct.unpack_from("<I", d, 46)[0]
    topDown = h < 0
    h = abs(h)

    palette = []
    if bpp <= 8:
        n = used if used else (1 << bpp)
        p = 14 + hdrSize
        for i in range(n):
            b, g, r = d[p], d[p+1], d[p+2]
            palette.append((r, g, b)); p += 4

    px = [[(0, 0, 0)] * w for _ in range(h)]

    def put(x, y, idx):
        if 0 <= x < w and 0 <= y < h:
            px[y][x] = palette[idx] if idx < len(palette) else (0, 0, 0)

    if comp == 1 and bpp == 8:               # BI_RLE8
        p = dataOff; x = 0; y = 0
        while p < len(d) - 1:
            cnt = d[p]; val = d[p+1]; p += 2
            if cnt > 0:
                for i in range(cnt): put(x + i, y, val)
                x += cnt
            else:
                if val == 0:      x = 0; y += 1          # end of line
                elif val == 1:    break                  # end of bitmap
                elif val == 2:                           # delta
                    x += d[p]; y += d[p+1]; p += 2
                else:                                    # absolute run
                    for i in range(val):
                        put(x + i, y, d[p + i])
                    x += val
                    p += val + (val & 1)                 # pad to word boundary
    elif comp == 0:                          # BI_RGB
        rowBytes = ((w * bpp + 31) // 32) * 4
        for row in range(h):
            p = dataOff + row * rowBytes
            for x in range(w):
                if bpp == 8:   put(x, row, d[p + x])
                elif bpp == 24:
                    o = p + x*3; px[row][x] = (d[o+2], d[o+1], d[o])
                elif bpp == 32:
                    o = p + x*4; px[row][x] = (d[o+2], d[o+1], d[o])
                elif bpp == 4:
                    b = d[p + x//2]
                    put(x, row, (b >> 4) if x % 2 == 0 else (b & 0xF))
    else:
        raise ValueError(f"unsupported bpp={bpp} comp={comp}")

    img = Image.new("RGB", (w, h))
    for y in range(h):
        src = px[y] if topDown else px[h - 1 - y]
        for x in range(w): img.putpixel((x, y), src[x])
    return img
