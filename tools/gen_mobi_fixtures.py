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
    ncx_index: int | None = None,
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
    # 0xF4: first INDX record (the NCX index) or 0xFFFFFFFF when absent
    struct.pack_into(">L", rec, 0xF4, ncx_index if ncx_index is not None else 0xFFFFFFFF)
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
# MOBI7 NCX (INDX "filepos" navigation index); KindleUnpack MobiIndex layout
# ---------------------------------------------------------------------------

def vwi_encode(value: int) -> bytes:
    """Amazon variable-width integer: 7-bit big-endian groups, high bit of the final
    byte marks the end (KindleUnpack getVariableWidthValue)."""
    out = bytearray()
    while True:
        out.insert(0, value & 0x7F)
        value >>= 7
        if value == 0:
            break
    out[-1] |= 0x80
    return bytes(out)


# tag-table entry: (tag id, values-per-entry, control-byte mask, end flag)
NCX_TAG_TABLE = [
    (1, 1, 0x01, 0),  # pos: chapter filepos (byte offset into the text records)
    (2, 1, 0x02, 0),  # len
    (4, 1, 0x04, 0),  # hlvl
]

INDX_HEADER = 0x38  # 'INDX' + 13 header words (fields through nctoc)


def ncx_entry(label: str, *, pos: int | None, length: int = 0, hlvl: int = 0) -> bytes:
    """One IDXT entry: [1-byte label length][UTF-8 label][control byte][values in
    tag-table order]. Only tags whose bit is set in the control byte are stored."""
    text = label.encode("utf-8")
    ctrl = 0
    values = b""
    if pos is not None:
        ctrl |= 0x01
        values += vwi_encode(pos)
    if length:
        ctrl |= 0x02
        values += vwi_encode(length)
    if hlvl:
        ctrl |= 0x04
        values += vwi_encode(hlvl)
    return bytes([len(text)]) + text + bytes([ctrl]) + values


def mobi_index_record(*, main: bool, entries: list[bytes] | None = None, nidxt: int = 0, total: int = 0) -> bytes:
    """INDX record. The main record stores the TAGX table at the offset named by its
    first header word ('len'); IDXT entry records store their IDXT area at 'start',
    with the 4-byte preamble + u16 offset table readers skip (KindleUnpack parity)."""
    rec = bytearray(INDX_HEADER)
    rec[0:4] = b"INDX"
    if main:
        struct.pack_into(">L", rec, 0x04, INDX_HEADER)  # len: TAGX offset
        struct.pack_into(">L", rec, 0x0C, 2)  # type: NCX
        struct.pack_into(">L", rec, 0x18, nidxt)  # IDXT records following
        struct.pack_into(">L", rec, 0x20, 9)  # lng: en
        struct.pack_into(">L", rec, 0x24, total)  # total entries
        tagx = struct.pack(">4sLL", b"TAGX", 12 + 4 * len(NCX_TAG_TABLE), 1)
        for tag in NCX_TAG_TABLE:
            tagx += bytes(tag)
        rec += tagx
        if len(rec) < 0xC0:  # ORDT fields live at 0xA4: keep the record big enough
            rec += b"\x00" * (0xC0 - len(rec))
        return bytes(rec)
    entries = entries or []
    struct.pack_into(">L", rec, 0x14, INDX_HEADER)  # start: IDXT area offset
    struct.pack_into(">L", rec, 0x18, len(entries))  # entries in this record
    body = bytearray(struct.pack(">L", len(entries)))  # IDXT preamble (skipped by readers)
    off = INDX_HEADER + 4 + 2 * len(entries)
    for e in entries:
        body += struct.pack(">H", off)
        off += len(e)
    for e in entries:
        body += e
    rec += body
    if len(rec) < 0xC0:  # ORDT fields live at 0xA4 (real kindlegen entry records are this big)
        rec += b"\x00" * (0xC0 - len(rec))
    return bytes(rec)


def read_mobi_ncx(records: list[bytes], ncx_offset: int) -> list[tuple[str | None, int | None]]:
    """KindleUnpack-mirrored NCX reader used to self-check the generated INDX
    fixtures: returns (label, pos) per navPoint entry (pos None when tag 1 is
    absent) in stored order."""
    main = records[ncx_offset]
    tagx_off, = struct.unpack_from(">L", main, 0x04)
    first_entry, ctrl_count = struct.unpack_from(">LL", main, tagx_off + 4)
    tag_table = [tuple(main[tagx_off + i : tagx_off + i + 4]) for i in range(12, first_entry, 4)]
    nidxt, = struct.unpack_from(">L", main, 0x18)
    out: list[tuple[str | None, int | None]] = []

    def read_vwi(data: bytes, p: int) -> tuple[int, int]:
        value = 0
        while True:
            v = data[p]
            p += 1
            value = (value << 7) | (v & 0x7F)
            if v & 0x80:
                return p, value

    for k in range(nidxt):
        rec = records[ncx_offset + 1 + k]
        idxt, count = struct.unpack_from(">LL", rec, 0x14)
        starts = [struct.unpack_from(">H", rec, idxt + 4 + 2 * j)[0] for j in range(count)]
        for s in starts:
            tlen = rec[s]
            label = rec[s + 1 : s + 1 + tlen].decode("utf-8")
            cs = s + 1 + tlen
            pending = []
            data_start = cs + ctrl_count
            control_index = 0
            for tag, vpe, mask, endflag in tag_table:
                if endflag == 1:
                    control_index += 1
                    continue
                cbyte = rec[cs + control_index] & mask
                if cbyte == 0:
                    continue
                if cbyte == mask and bin(mask).count("1") > 1:
                    data_start, vlen = read_vwi(rec, data_start)
                    pending.append((tag, None, vlen, vpe))
                elif cbyte == mask:
                    pending.append((tag, 1, 0, vpe))
                else:
                    while mask & 1 == 0:
                        mask >>= 1
                        cbyte >>= 1
                    pending.append((tag, cbyte, 0, vpe))
            tag_map: dict[int, list[int]] = {}
            for tag, vcount, vbytes, vpe in pending:
                values = []
                if vcount is not None:
                    for _ in range(vcount * vpe):
                        data_start, val = read_vwi(rec, data_start)
                        values.append(val)
                else:
                    start_before = data_start
                    while data_start - start_before < vbytes:
                        data_start, val = read_vwi(rec, data_start)
                        values.append(val)
                tag_map.setdefault(tag, []).extend(values)
            out.append((label, tag_map.get(1, [None])[0]))
    return out


MOBI7_NCX_BODY = (
    "<html><head><title>Fixture</title></head><body>"
    "<h2>Chapter 1</h2>"
    "<p>First paragraph of the first chapter with caf\u00e9 and &rsquo;quotes&rsquo;.</p>"
    "<p>Second paragraph of the first chapter.</p>"
    "<h2>Chapter 2</h2>"
    "<p>Only paragraph of the second chapter.</p>"
    "<h2>Chapter 3</h2>"
    "<p>First paragraph of the third chapter.</p>"
    "<p>Second paragraph of the third chapter.</p>"
    "</body></html>"
)

# UTF-8 variant: multi-byte sequences before every chapter boundary exercise the
# parser's byte→char offset mapping (byte offsets != char offsets).
MOBI7_NCX_UTF8_BODY = (
    "<html><head><title>Fixture</title></head><body>"
    "<h2>Chapter 1</h2>"
    "<p>Z\u00fcrich caf\u00e9 \u20acuro first chapter.</p>"
    "<p>Second paragraph \u00e9\u00e8\u00ea of the first chapter.</p>"
    "<h2>Chapter 2</h2>"
    "<p>Only paragraph \u2014 of the second chapter.</p>"
    "<h2>Chapter 3</h2>"
    "<p>Third chapter with caf\u00e9 and \u20ac.</p>"
    "</body></html>"
)

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

    # --- MOBI7 with an NCX index (chapter splitting via filepos navPoints) ---
    ncx_body = MOBI7_NCX_BODY.encode("cp1252")
    p1 = ncx_body.index(b"<h2>Chapter 1</h2>")
    p2 = ncx_body.index(b"<h2>Chapter 2</h2>")
    p3 = ncx_body.index(b"<h2>Chapter 3</h2>")
    entries = [
        ncx_entry("First Chapter", pos=p1),
        ncx_entry("Second Chapter", pos=p2, length=24, hlvl=1),
        ncx_entry("Third &amp; Final Chapter", pos=p3),
    ]
    records = [
        mobi_record0(compression=1, text_len=len(ncx_body), text_records=1, title="Pride and Prejudice", ncx_index=2),
        ncx_body,
        mobi_index_record(main=True, nidxt=2, total=len(entries)),
        mobi_index_record(main=False, entries=entries[:2]),
        mobi_index_record(main=False, entries=entries[2:]),
    ]
    assert read_mobi_ncx(records, 2) == [
        ("First Chapter", p1), ("Second Chapter", p2), ("Third &amp; Final Chapter", p3),
    ]
    (OUT / "mobi7_ncx.mobi").write_bytes(pdb("Pride and Prejudice", records))
    print("mobi7_ncx.mobi", "navPoints at", [p1, p2, p3])

    # --- MOBI7 with a malformed NCX: out-of-range, duplicate and target-less entries ---
    bad_entries = [
        ncx_entry("First Chapter", pos=p1),
        ncx_entry("Broken Out-of-Range", pos=0x7FFFFFFF),
        ncx_entry("Chapter Two", pos=p2),
        ncx_entry("Duplicate Chapter", pos=p2),
        ncx_entry("No-Target Chapter", pos=None),
        ncx_entry("Final Chapter", pos=p3),
    ]
    bad_records = [
        mobi_record0(compression=1, text_len=len(ncx_body), text_records=1, title="Pride and Prejudice", ncx_index=2),
        ncx_body,
        mobi_index_record(main=True, nidxt=2, total=len(bad_entries)),
        mobi_index_record(main=False, entries=bad_entries[:3]),
        mobi_index_record(main=False, entries=bad_entries[3:]),
    ]
    assert read_mobi_ncx(bad_records, 2) == [
        ("First Chapter", p1),
        ("Broken Out-of-Range", 0x7FFFFFFF),
        ("Chapter Two", p2),
        ("Duplicate Chapter", p2),
        ("No-Target Chapter", None),
        ("Final Chapter", p3),
    ]
    (OUT / "mobi7_ncx_malformed.mobi").write_bytes(pdb("Pride and Prejudice", bad_records))
    print("mobi7_ncx_malformed.mobi")

    # --- MOBI7 with an NCX, UTF-8 codepage (multi-byte chapter text) ---
    utf8_body = MOBI7_NCX_UTF8_BODY.encode("utf-8")
    u1 = utf8_body.index(b"<h2>Chapter 1</h2>")
    u2 = utf8_body.index(b"<h2>Chapter 2</h2>")
    u3 = utf8_body.index(b"<h2>Chapter 3</h2>")
    uentries = [
        ncx_entry("First Chapter", pos=u1),
        ncx_entry("Second Chapter", pos=u2),
        ncx_entry("Third &amp; Final Chapter", pos=u3),
    ]
    urecords = [
        mobi_record0(compression=1, text_len=len(utf8_body), text_records=1, title="Pride and Prejudice", codepage=65001, ncx_index=2),
        utf8_body,
        mobi_index_record(main=True, nidxt=1, total=len(uentries)),
        mobi_index_record(main=False, entries=uentries),
    ]
    assert read_mobi_ncx(urecords, 2) == [
        ("First Chapter", u1), ("Second Chapter", u2), ("Third &amp; Final Chapter", u3),
    ]
    (OUT / "mobi7_ncx_utf8.mobi").write_bytes(pdb("Pride and Prejudice", urecords))
    print("mobi7_ncx_utf8.mobi", "navPoints at", [u1, u2, u3])

    print("all fixtures self-checked and written to", OUT)


if __name__ == "__main__":
    main()
