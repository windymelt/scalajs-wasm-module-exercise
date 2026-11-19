import sys, struct, zlib
for path in sys.argv[1:]:
    d = open(path, "rb").read()
    assert d[:8] == b"\x89PNG\r\n\x1a\n"
    pos, idat, info = 8, b"", []
    while pos < len(d):
        n, = struct.unpack(">I", d[pos:pos+4]); t = d[pos+4:pos+8]; body = d[pos+8:pos+8+n]
        crc, = struct.unpack(">I", d[pos+8+n:pos+12+n])
        assert zlib.crc32(t + body) == crc, f"CRC mismatch in {t}"
        if t == b"IHDR": w, h = struct.unpack(">II", body[:8])
        if t == b"IDAT": idat += body
        info.append(t.decode()); pos += 12 + n
    raw = zlib.decompress(idat)
    filters = [raw[y * (w*3+1)] for y in range(h)]
    print(f"{path.split('/')[-1]}: {w}x{h} chunks={info} CRC ok, inflate ok ({len(raw)} bytes = expected {(w*3+1)*h}), "
          f"ratio {len(idat)/len(raw):.1%}, filters used={ {f: filters.count(f) for f in sorted(set(filters))} }")
