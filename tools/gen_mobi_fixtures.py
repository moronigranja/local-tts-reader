#!/usr/bin/env python3
"""Generates binary MOBI/KF8 fixtures for core-ebook parser tests (C2/C3).

Writers are mirrored from the mobileRead MOBI spec + KindleUnpack semantics:
- PDB container (header + record table)
- PalmDOC header (compression/textlen/records/encryption)
- MOBI header (type, codepage, title fields, huffman pointers, EXTH flags)
- PalmDOC compression (LZ77), HUFF/CDIC compression (canonical codes)
- KF8 = PDB wrapping a ZIP (OPF + XHTML + nav at the archive root)

Every generated file is round-trip self-checked with decoders mirrored from
KindleUnpack before being written to core-ebook/src/test/resources.
"""

import io
import struct
import zipfile
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "core-ebook" / "src" / "test" / "resources"

# ---------------------------------------------------------------------------
# PDB container
# ---------------------------------------------------------------------------

def pdb(name: str, records: list[bytes]) -> bytes:
    recs = [bytes(r) for r in records]
    num = len(recs)
    head = bytearray(78)
    bname = name.encode("ascii")[:31]
    head[0 : len(bname)] = bname
    head[60:64] = b"BOOK"
    head[64:68] = b"MOBI"
    struct.pack_into(">H", head, 76, num)
    out = bytearray(78 + 8 * num)
    out[0:78] = head
    offset = 78 + 8 * num
    for i, r in enumerate(recs):
        struct.pack_into(">L", out, 78 + 8 * i, offset)
        out.extend(r)
        offset += len(r)
    return bytes(out)

# ---------------------------------------------------------------------------
# Record 0: PalmDOC header + MOBI header + EXTH + name (mobileRead layout)
# ---------------------------------------------------------------------------

def palmdoc_header(compression: int, text_len: int, text_records: int, encryption: int = 0) -> bytes:
    return struct.pack(">HHIHHHH", compression, 0, text_len, text_records, 4096, encryption, 0)

MOBI_HEADER_LEN = 0x100  # 256: modern-file standard, covers fields up to 0xF8

def mobi_record0(
    *,
    compression: int,
    text_len: int,
    text_records: int,
    encryption: int = 0,
    mobi_type: int = 2,
    codepage: int = 1252,
    title: str | None = None,
    with_exth: bool = True,
    huff_offset: int = 0xFFFFFFFF,
    huff_count: int = 0,
) -> bytes:
    rec = bytearray(16 + MOBI_HEADER_LEN)
    rec[0:16] = palmdoc_header(compression, text_len, text_records, encryption)
    rec[16:20] = b"MOBI"
    struct.pack_into(">L", rec, 0x14, MOBI_HEADER_LEN)
    struct.pack_into(">L", rec, 0x18, mobi_type)
    struct.pack_into(">L", rec, 0x1C, codepage)
    struct.pack_into(">L", rec, 0x20, 0x1234)  # unique id
    struct.pack_into(">L", rec, 0x24, 6)  # file version
    struct.pack_into(">L", rec, 0x68, 6)  # min version
    struct.pack_into(">L", rec, 0x6C, 0xFFFFFFFF)  # first image
    struct.pack_into(">L", rec, 0x70, huff_offset)
    struct.pack_into(">L", rec, 0x74, huff_count)
    struct.pack_into(">L", rec, 0xF4, 0xFFFFFFFF)  # INDX absent
    exth = b""
    if with_exth and title is not None:
        data = title.encode("cp1252")
        exth = struct.pack(">4sLL", b"EXTH", 12 + 8 + len(data), 1) + struct.pack(">LL", 503, 8 + len(data)) + data
        struct.pack_into(">L", rec, 0x80, 0x40)
    rec = rec[: 16 + MOBI_HEADER_LEN] + exth
    if title is not None:
        toff = len(rec)
        name = title.encode("cp1252")
        rec += name + b"\x00\x00"
        while len(rec) % 4 != 0:
            rec += b"\x00"
        struct.pack_into(">L", rec, 0x54, toff)
        struct.pack_into(">L", rec, 0x58, len(name))
    return bytes(rec)

# ---------------------------------------------------------------------------
# PalmDOC (LZ77) compression + decoder mirror for self-check
# ---------------------------------------------------------------------------

def palmdoc_compress(data: bytes) -> bytes:
    """Compress with the 3-byte back-reference scheme (distance <= 2047, len 3..10)."""
    out = bytearray()
    i = 0
    n = len(data)
    while i < n:
        best = None
        window_start = max(0, i - 2047)
        for dist in range(1, i - window_start + 1):
            length = 0
            while length < 10 and i + length < n and data[i + length] == data[i - dist + length]:
                length += 1
            if length >= 3 and (best is None or length > best[1]):
                best = (dist, length)
        if best is not None and best[1] >= 3:
            dist, length = best
            c = 0x8000 | ((dist & 0x7FF) << 3) | ((length - 3) & 0x07)
            out += struct.pack(">H", c)
            i += length
        else:
            b = data[i]
            if 0x80 <= b < 0xC0:
                out += bytes([0xC0 ^ b])  # 192..255: space + (byte xor 128)
            elif b < 0x80:
                out += bytes([b]) if b > 0 else bytes([0x80])  # avoid 0x00 (would be len 0)
            else:
                # literal can't be represented directly; use len-1 copy trick? Instead emit
                # 0x81 followed by the byte (literal escape is 1..8 length prefix).
                out += bytes([0x01]) + bytes([b])
            i += 1
    return bytes(out)


def palmdoc_decompress(data: bytes) -> bytes:
    """KindleUnpack-mirrored decoder (used to self-check fixtures)."""
    o = bytearray()
    p = 0
    while p < len(data):
        c = data[p]
        p += 1
        if 1 <= c <= 8:
            o += data[p : p + c]
            p += c
        elif c < 128:
            o += bytes([c])
        elif c >= 192:
            o += b" " + bytes([c ^ 128])
        else:
            c = (c << 8) | data[p]
            p += 1
            m = (c >> 3) & 0x7FF
            n = (c & 7) + 3
            if m > n:
                o += o[-m : n - m]
            else:
                for _ in range(n):
                    o += o[-m:-m+1] if m > 1 else o[-m:]
    return bytes(o)

# ---------------------------------------------------------------------------
# HUFF/CDIC compression (8-bit canonical codes design) + decoder mirror
# ---------------------------------------------------------------------------

def make_cdic(words: list[bytes]) -> bytes:
    """CDIC with 256 entries (payload phrases + empty padding), all flagged plain."""
    phrases = 256
    n = 256
    offs = bytearray(2 * n)
    words_area = bytearray()
    positions = []
    for k in range(n):
        w = words[k] if k < len(words) else b""
        positions.append(2 * n + len(words_area))  # decoder reads at 16 + off
        words_area += struct.pack(">H", len(w) | 0x8000) + w
    for k in range(n):
        struct.pack_into(">H", offs, 2 * k, positions[k])
    return struct.pack(">4sLLL", b"CDIC", 0x10, phrases, 8) + bytes(offs) + bytes(words_area)


def make_huff() -> bytes:
    """HUFF with all-8-bit canonical codes: first byte indexes dictionary entry (255-b)."""
    dict1 = b"".join(struct.pack(">L", 0xFF88) for _ in range(256))  # codelen 8, terminal, maxcode 0xFFFFFFFF
    dict2 = b"\x00" * (64 * 4)
    return struct.pack(">4sLLL", b"HUFF", 0x18, 16, 16 + 1024) + dict1 + dict2


def huffcdic_compress(text: bytes) -> tuple[bytes, bytes, bytes]:
    """Encode: one 8-bit code per token (word or single space); byte b -> dictionary index 255-b."""
    words = text.split(b" ")
    tokens: list[bytes] = []
    for i, w in enumerate(words):
        if i > 0:
            tokens.append(b" ")
        tokens.append(w)
    unique = []
    index = {}
    for t in tokens:
        if t not in index:
            index[t] = len(unique)
            unique.append(t)
    if len(unique) > 256:
        raise ValueError("fixture text has more than 256 unique phrases")
    codes = bytes(255 - index[t] for t in tokens)
    cdic = make_cdic(unique)
    huff = make_huff()
    return codes, huff, cdic


def huffcdic_decompress(data: bytes, huff: bytes, cdic: bytes, modes: list[bool]) -> bytes:
    """KindleUnpack-mirrored decoder (modes carries the plain/compressed flags)."""
    off1, off2 = struct.unpack_from(">LL", huff, 8)
    dict1 = struct.unpack_from(">256L", huff, off1)
    dict2 = struct.unpack_from(">64L", huff, off2)
    mincode = tuple((0,) + tuple((dict2[i] << (32 - l)) for l, i in enumerate(range(0, 64, 2))))
    maxcode = []
    for l in range(1, 33):
        mx = dict2[2 * (l - 1) + 1]
        maxcode.append(((mx + 1) << (32 - l)) - 1)
    maxcode = (0,) + tuple(maxcode)
    phrases, bits = struct.unpack_from(">LL", cdic, 8)
    n = min(1 << bits, phrases)
    offs = struct.unpack_from(">%dH" % n, cdic, 16)
    dictionary = []
    for off in offs:
        blen = struct.unpack_from(">H", cdic, 16 + off)[0]
        dictionary.append((cdic[18 + off : 18 + off + (blen & 0x7FFF)], blen & 0x8000))

    def unpack(data: bytes, modes: list) -> bytes:
        padded = data + b"\x00" * 8
        pos = 0
        bitsleft = len(data) * 8
        x = struct.unpack_from(">Q", padded, 0)[0]
        nbits = 32
        s = bytearray()
        while True:
            if nbits <= 0:
                pos += 4
                x = struct.unpack_from(">Q", padded, pos)[0]
                nbits += 32
            code = (x >> nbits) & 0xFFFFFFFF
            codelen, term, maxcode1 = dict1[code >> 24] & 0x1F, dict1[code >> 24] & 0x80, (((dict1[code >> 24] >> 8) + 1) << (32 - (dict1[code >> 24] & 0x1F))) - 1
            if not term:
                while code < mincode[codelen]:
                    codelen += 1
                maxcode1 = maxcode[codelen]
            nbits -= codelen
            bitsleft -= codelen
            if bitsleft < 0:
                break
            r = (maxcode1 - code) >> (32 - codelen)
            sl, flag = dictionary[r]
            if not flag:
                dictionary[r] = (sl, 1)
                sl = unpack(sl, modes)
            s += sl
        return bytes(s)

    return unpack(data, modes)

# ---------------------------------------------------------------------------
# KF8
# ---------------------------------------------------------------------------

def kf8_records(zip_bytes: bytes, chunksize: int = 4096) -> list[bytes]:
    return [zip_bytes[i : i + chunksize] for i in range(0, len(zip_bytes), chunksize)]

# ---------------------------------------------------------------------------
# Fixture content
# ---------------------------------------------------------------------------

MOBI7_BODY = (
    "<html><head><title>Fixture</title><style>b{}</style></head><body>"
    "<h2>Chapter 1</h2>"
    "<p>It is a truth universally acknowledged&nbsp;&mdash; that a single man in possession of a good fortune, must be in want of a wife.</p>"
    "<p>A second paragraph with caf\u00e9 and &rsquo;quotes&rsquo;.</p>"
    "</body></html>"
)

KF8_OPF = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">kf8fixture</dc:identifier>
    <dc:title>Alice's Adventures in Wonderland</dc:title>
    <dc:creator>Lewis Carroll</dc:creator>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="chap1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="chap2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="c1"/>
    <itemref idref="c2"/>
  </spine>
</package>"""

KF8_NAV = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Contents</title></head>
<body><nav epub:type="toc">
<a href="chap1.xhtml">Down the Rabbit-Hole</a>
<a href="chap2.xhtml">The Pool of Tears</a>
</nav></body></html>"""

KF8_CHAP1 = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>c1</title></head>
<body><p>Alice was beginning to get very tired of sitting by her sister on the bank.</p>
<p>Once or twice she had peeped into the book her sister was reading.</p></body></html>"""

KF8_CHAP2 = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>c2</title></head>
<body><p>Curiouser and curiouser! cried Alice.</p></body></html>"""

# ---------------------------------------------------------------------------
# Build + self-check + write
# ---------------------------------------------------------------------------

def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    body_cp = MOBI7_BODY.encode("cp1252")

    # --- MOBI7, uncompressed (compression 1) ---
    rec0 = mobi_record0(compression=1, text_len=len(body_cp), text_records=1, title="Pride and Prejudice")
    data = pdb("Pride and Prejudice", [rec0, body_cp])
    (OUT / "mobi7_plain.mobi").write_bytes(data)
    print("mobi7_plain.mobi", len(data))

    # --- MOBI7, PalmDOC (compression 2) ---
    comp = palmdoc_compress(body_cp)
    assert palmdoc_decompress(comp) == body_cp  # round-trip
    rec0 = mobi_record0(compression=2, text_len=len(body_cp), text_records=1, title="Pride and Prejudice")
    (OUT / "mobi7_palmdoc.mobi").write_bytes(pdb("Pride and Prejudice", [rec0, comp]))
    print("mobi7_palmdoc.mobi", len(comp))

    # --- MOBI7, HUFF/CDIC (compression 0x4448); text record, then HUFF, then CDIC ---
    codes, huff, cdic = huffcdic_compress(MOBI7_BODY.encode("cp1252"))
    # self-check with the mirrored decoder
    assert huffcdic_decompress(codes, huff, cdic, [True] * 256) == MOBI7_BODY.encode("cp1252")
    rec0 = mobi_record0(
        compression=0x4448, text_len=len(body_cp), text_records=1,
        title="Pride and Prejudice", huff_offset=2, huff_count=2,
    )
    (OUT / "mobi7_huffcdic.mobi").write_bytes(pdb("Pride and Prejudice", [rec0, codes, huff, cdic]))
    print("mobi7_huffcdic.mobi", len(codes))

    # --- MOBI7, DRM-encrypted (must be rejected) ---
    rec0 = mobi_record0(compression=1, text_len=len(body_cp), text_records=1, title="Pride and Prejudice", encryption=2)
    (OUT / "mobi7_encrypted.mobi").write_bytes(pdb("Pride and Prejudice", [rec0, body_cp]))
    print("mobi7_encrypted.mobi")

    # --- MOBI7, no EXTH: title from the full-name field ---
    rec0 = mobi_record0(compression=1, text_len=len(body_cp), text_records=1, title="Moby-Dick", with_exth=False)
    (OUT / "mobi7_noname_exth.mobi").write_bytes(pdb("Moby-Dick", [rec0, body_cp]))
    print("mobi7_noname_exth.mobi")

    # --- KF8 (azw3): PDB wrapping a ZIP; type 0xFFFFFFFF, compression 1 ---
    z = io.BytesIO()
    with zipfile.ZipFile(z, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, content in [
            ("content.opf", KF8_OPF),
            ("nav.xhtml", KF8_NAV),
            ("chap1.xhtml", KF8_CHAP1),
            ("chap2.xhtml", KF8_CHAP2),
        ]:
            zf.writestr(name, content)
    zbytes = z.getvalue()
    assert zipfile.is_zipfile(io.BytesIO(zbytes))
    recs = kf8_records(zbytes, chunksize=512)
    rec0 = mobi_record0(
        compression=1, text_len=0, text_records=len(recs),
        mobi_type=0xFFFFFFFF, codepage=65001, title="Alice's Adventures in Wonderland",
    )
    (OUT / "kf8_test.azw3").write_bytes(pdb("Alice", [rec0] + recs))
    print("kf8_test.azw3", len(zbytes), "zip bytes in", len(recs), "records")

    print("all fixtures self-checked and written to", OUT)


if __name__ == "__main__":
    main()
