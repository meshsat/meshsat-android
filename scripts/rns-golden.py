#!/usr/bin/env python3
"""Golden vectors for the app's Reticulum and LXMF wire code (MESHSAT-1553).

Run from the pinned upstream venv (RNS 1.5.5 + LXMF 1.2.0, the stack CrossTalk
2.6.0 ships; see app/src/test/java/net/meshsat/android/interop/PyEnv.kt):

    ~/.cache/meshsat/rns-venv-1.5.5/bin/python3 scripts/rns-golden.py \
        > app/src/test/resources/rns/golden.properties

Every value is produced by upstream RNS/LXMF itself, so GoldenVectorTest proves
the Kotlin side reads what Python writes without needing Python at test time.
The other direction (Python reads what Kotlin writes) is OracleTest, which runs
the live venv when MESHSAT_INTEROP=1.
"""
import hashlib
import os
import struct
import sys
import time

import RNS
import LXMF
from LXMF import LXStamper
from RNS.Cryptography import Token, hkdf
from RNS.Link import Link
import RNS.vendor.umsgpack as um

RNS.loglevel = 0
LXStamper.job_linux = LXStamper.job_simple
LXStamper.job_linux_managed = LXStamper.job_simple

out = []


def put(k, v):
    if isinstance(v, bytes):
        v = v.hex()
    elif isinstance(v, bool):
        v = "true" if v else "false"
    out.append(f"{k}={v}")


put("rns.version", RNS.__version__)
put("lxmf.version", LXMF.__version__)

# --- Token (both key sizes) -------------------------------------------------
plain = b"token golden test payload, 40 bytes long!"
for name, size in (("token256", 64), ("token128", 32)):
    key = os.urandom(size)
    put(f"{name}.key", key)
    put(f"{name}.plain", plain)
    put(f"{name}.ct", Token(key).encrypt(plain))

# --- HKDF ------------------------------------------------------------------
ikm = b"input keying material"
salt = b"0123456789abcdef"
put("hkdf.ikm", ikm)
put("hkdf.salt", salt)
put("hkdf.a", hkdf(length=64, derive_from=ikm, salt=salt))
put("hkdf.b", hkdf(length=48, derive_from=ikm, salt=None))

# --- Identity, single-packet encryption, ratchets ---------------------------
ident = RNS.Identity()
pub = RNS.Identity(create_keys=False)
pub.load_public_key(ident.get_public_key())
put("identity.prv", ident.get_private_key())
put("identity.pub", ident.get_public_key())
put("identity.hash", ident.hash)
put("identity.lxmf_dest", RNS.Destination.hash(ident, "lxmf", "delivery"))
put("identity.name_hash", RNS.Identity.full_hash("lxmf.delivery".encode("utf-8"))[:10])
sp = b"single packet plaintext for lxmf.delivery"
put("identity.plain", sp)
put("identity.ct", pub.encrypt(sp))
sig_data = b"signed by python"
put("identity.sig_data", sig_data)
put("identity.sig", ident.sign(sig_data))
ratchet = RNS.Identity._generate_ratchet()
rpub = RNS.Identity._ratchet_public_bytes(ratchet)
put("ratchet.prv", ratchet)
put("ratchet.pub", rpub)
put("ratchet.plain", b"ratcheted")
put("ratchet.ct", pub.encrypt(b"ratcheted", ratchet=rpub))

# --- Packet hashes ----------------------------------------------------------
dest = RNS.Destination.hash(ident, "lxmf", "delivery")
raw = struct.pack("!BB", 0x00, 3) + dest + b"\x00" + b"payload bytes"   # DATA SINGLE HEADER_1, hops 3


def py_hash(raw):
    p = RNS.Packet(None, None)
    p.raw = raw
    assert p.unpack(), "unpack failed"
    return p


p = py_hash(raw)
put("packet.raw", raw)
put("packet.hash", p.packet_hash)
put("packet.trunc", p.truncated_packet_hash)
tid = os.urandom(16)
raw2 = struct.pack("!BB", 0x50, 3) + tid + dest + b"\x00" + b"payload bytes"   # HEADER_2 TRANSPORT
p2 = py_hash(raw2)
put("packet.raw2", raw2)
put("packet.hash2", p2.packet_hash)
assert p2.packet_hash == p.packet_hash

# --- Link request, link id, signalling, key ---------------------------------
eph = RNS.Cryptography.X25519PrivateKey.generate()
esig = RNS.Cryptography.Ed25519PrivateKey.generate()
lr_data = eph.public_key().public_bytes() + esig.public_key().public_bytes() + Link.signalling_bytes(500, Link.MODE_AES256_CBC)
lr_raw = struct.pack("!BB", 0x02, 0) + dest + b"\x00" + lr_data
lp = py_hash(lr_raw)
put("link.request_raw", lr_raw)
put("link.id", Link.link_id_from_lr_packet(lp))
put("link.signalling_500_1", Link.signalling_bytes(500, Link.MODE_AES256_CBC))
put("link.signalling_262144_1", Link.signalling_bytes(262144, Link.MODE_AES256_CBC))
put("link.mtu", Link.mtu_from_lr_packet(lp))
put("link.mode", Link.mode_from_lr_packet(lp))
shared = os.urandom(32)
put("link.shared", shared)
put("link.key", hkdf(length=64, derive_from=shared, salt=Link.link_id_from_lr_packet(lp)))
# The same request without signalling bytes has the same link id.
lr_raw_plain = struct.pack("!BB", 0x02, 0) + dest + b"\x00" + lr_data[:64]
put("link.request_raw_unsignalled", lr_raw_plain)
assert Link.link_id_from_lr_packet(py_hash(lr_raw_plain)) == Link.link_id_from_lr_packet(lp)

# --- Link proof signed by the responder identity ----------------------------
lid = os.urandom(16)
reph = RNS.Cryptography.X25519PrivateKey.generate()
reph_pub = reph.public_key().public_bytes()
SIG = RNS.Identity.SIGLENGTH // 8
EC = Link.ECPUBSIZE // 2
signed = lid + reph_pub + ident.get_public_key()[EC:] + Link.signalling_bytes(500, Link.MODE_AES256_CBC)
sig = ident.sign(signed)
put("linkproof.link_id", lid)
put("linkproof.data", sig + reph_pub + Link.signalling_bytes(500, Link.MODE_AES256_CBC))
signed_old = lid + reph_pub + ident.get_public_key()[EC:]
put("linkproof.data_unsignalled", ident.sign(signed_old) + reph_pub)

# --- RTT msgpack ------------------------------------------------------------
put("rtt.packed_0_734", um.packb(0.734))
put("rtt.packed_1_25", um.packb(1.25))

# --- Packet proofs ----------------------------------------------------------
praw = struct.pack("!BB", 0x00, 0) + dest + b"\x00" + b"x"
pp = py_hash(praw)
put("proof.raw", praw)
put("proof.hash", pp.packet_hash)
put("proof.impl", ident.sign(pp.packet_hash))
put("proof.expl", pp.packet_hash + ident.sign(pp.packet_hash))
put("proof.dest", pp.truncated_packet_hash)

# --- Announces: plain, with ratchet, as a path response in transport ---------
now = int(time.time())
name_hash = RNS.Identity.full_hash(b"lxmf.delivery")[:10]
app_data = um.packb([b"Python peer", 12, [0]])


def announce(ratchet_pub, app, header2=None, context=0x00, hops=0):
    random_hash = os.urandom(5) + now.to_bytes(5, "big")
    r = ratchet_pub if ratchet_pub else b""
    signed = dest + ident.get_public_key() + name_hash + random_hash + r + app
    s = ident.sign(signed)
    data = ident.get_public_key() + name_hash + random_hash + r + s + app
    cf = 0x20 if ratchet_pub else 0x00
    if header2 is None:
        flags = cf | 0x01
        return struct.pack("!BB", flags, hops) + dest + bytes([context]) + data
    flags = 0x40 | cf | 0x10 | 0x01
    return struct.pack("!BB", flags, hops) + header2 + dest + bytes([context]) + data


a1 = announce(None, b"")
a2 = announce(rpub, app_data)
a3 = announce(rpub, app_data, header2=tid, context=0x0B, hops=2)
for i, a in enumerate((a1, a2, a3), 1):
    pa = py_hash(a)
    ok = RNS.Identity.validate_announce(pa, only_validate_signature=True)
    assert ok, f"python rejects announce {i}"
    put(f"announce{i}.raw", a)
put("announce.ts", now)
put("announce.app_data", app_data)
put("announce.display_name", "Python peer")
put("announce.stamp_cost", 12)
# An announce whose ratchet was replaced is invalid.
bad = bytearray(a2)
bad[2 + 16 + 1 + 64 + 10 + 10] ^= 0x01
assert not RNS.Identity.validate_announce(py_hash(bytes(bad)), only_validate_signature=True)
put("announce.bad", bytes(bad))

# --- Plain destinations ----------------------------------------------------
put("plain.path_request_dest", RNS.Destination.hash(None, "rnstransport", "path", "request"))

# --- IFAC -------------------------------------------------------------------
class Iface:
    def ifac_violation(self, *a):
        pass


i = Iface()
origin = RNS.Identity.full_hash(b"meshsat-test") + RNS.Identity.full_hash(b"hunter2")
i.ifac_key = hkdf(length=64, derive_from=RNS.Identity.full_hash(origin), salt=RNS.Reticulum.IFAC_SALT, context=None)
i.ifac_identity = RNS.Identity.from_bytes(i.ifac_key)
i.ifac_size = 16
put("ifac.netname", "meshsat-test")
put("ifac.netkey", "hunter2")
put("ifac.key", i.ifac_key)
put("ifac.clean", a1)
put("ifac.wrapped", RNS.Transport.handle_outgoing_ifac(i, a1))
i2 = Iface()
origin2 = RNS.Identity.full_hash(b"other")
i2.ifac_key = hkdf(length=64, derive_from=RNS.Identity.full_hash(origin2), salt=RNS.Reticulum.IFAC_SALT, context=None)
i2.ifac_identity = RNS.Identity.from_bytes(i2.ifac_key)
i2.ifac_size = 16
put("ifac.wrapped_other", RNS.Transport.handle_outgoing_ifac(i2, a1))

# --- msgpack ----------------------------------------------------------------
complex_value = [1758814000.123, b"title", b"x" * 300, {1: "hello", 16: [-5, 200, 70000, -200, True, None]},
                 5000000000, -5000000000, "s" * 40, 2 ** 64 - 1, -2 ** 40, 255, 256, 65535, 65536, -33, -129, -32769]
put("msgpack.complex", um.packb(complex_value))
put("msgpack.float32", b"\xca" + struct.pack(">f", 1.5))   # decodes to 1.5, re-encodes as float64
put("msgpack.float32_reencoded", um.packb(um.unpackb(b"\xca" + struct.pack(">f", 1.5))))

# --- LXMF: a message packed by Python, from dst to src ----------------------
src = RNS.Identity()
dst = RNS.Identity()
for ii in (src, dst):
    RNS.Identity.known_destinations[RNS.Destination.hash_from_name_and_identity("lxmf.delivery", ii)] = [0, None, ii.get_public_key(), None]
d_src = RNS.Destination(src, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
d_dst = RNS.Destination(dst, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
put("lxmf.src_prv", src.get_private_key())
put("lxmf.dst_prv", dst.get_private_key())
put("lxmf.src_dest", d_src.hash)
put("lxmf.dst_dest", d_dst.hash)
m = LXMF.LXMessage(d_src, d_dst, b"reply from python", title=b"re", desired_method=LXMF.LXMessage.OPPORTUNISTIC)
m.pack()
put("lxmf.packed", m.packed)
put("lxmf.hash", m.hash)
put("lxmf.content", b"reply from python")
put("lxmf.title", b"re")
put("lxmf.timestamp", repr(m.timestamp))
# With fields (Sideband-style numeric keys, one unknown) and a stamp at cost 8.
fields = {1: b"\x01\x02", 0x0B: {"ty": 1, "tx": b"\x00"}, 0xAB: "unknown field"}
m2 = LXMF.LXMessage(d_src, d_dst, b"stamped", title=b"", fields=fields, desired_method=LXMF.LXMessage.OPPORTUNISTIC)
m2.stamp_cost = 8
m2.defer_stamp = False   # LXMF 1.2.0 defers stamps to the router unless told otherwise
m2.pack()
assert m2.stamp is not None and len(m2.stamp) == 32
put("lxmf2.packed", m2.packed)
put("lxmf2.hash", m2.hash)
put("lxmf2.stamp", m2.stamp)
put("lxmf2.stamp_cost", 8)
put("lxmf2.fields_packed", um.packb(fields))
put("lxmf2.workblock_sha256", hashlib.sha256(LXStamper.stamp_workblock(m2.hash)).digest())
put("lxmf2.stamp_value", LXStamper.stamp_value(LXStamper.stamp_workblock(m2.hash), m2.stamp))
put("lxmf.app_data", um.packb([b"MeshSat kit", 8, [0]]))
put("lxmf.app_data_noname", um.packb([None, None, [0]]))
put("lxmf.app_data_legacy", b"Old name")
put("lxmf.display_name_legacy", LXMF.display_name_from_app_data(b"Old name"))
put("lxmf.content_size", m.packed_size if hasattr(m, "packed_size") else 0)

print("\n".join(out))
