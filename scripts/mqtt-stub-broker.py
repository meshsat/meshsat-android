#!/usr/bin/env python3
"""A scripted MQTT 3.1.1 broker for the Hub reporter's subscription tests (MESHSAT-1584, PH-R2).

It answers CONNECT, SUBSCRIBE, PUBLISH (QoS 0/1), PINGREQ and DISCONNECT over plain TCP and routes
nothing. What it does with a client's subscriptions depends on a word in the client id:

  refuse1  every connection: the first SUBSCRIBE gets its last filter refused (0x80), later ones granted
  reconn1  connection 1 granted, its socket closed 3 s after that SUBACK; later connections as refuse1
  dropset  connection 1 refuses every filter and is closed after its second SUBACK; later ones granted
  anything else is granted

One JSON line per event on stdout (no payloads). Usage: mqtt-stub-broker.py [host] [port] [seconds]
"""
import asyncio
import json
import sys
import time

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 18883
SECONDS = float(sys.argv[3]) if len(sys.argv) > 3 else 900

connections = {}  # client id -> connections so far


def log(**kv):
    kv["t"] = round(time.time(), 3)
    print(json.dumps(kv), flush=True)


async def read_packet(r):
    b1 = (await r.readexactly(1))[0]
    mult, length = 1, 0
    while True:
        d = (await r.readexactly(1))[0]
        length += (d & 0x7F) * mult
        if d & 0x80 == 0:
            break
        mult *= 128
    return b1, await r.readexactly(length) if length else b""


def packet(b1, body):
    n, out = len(body), bytearray([b1])
    while True:
        d, n = n % 128, n // 128
        out.append(d | (0x80 if n else 0))
        if not n:
            break
    return bytes(out) + body


def string(b, i):
    n = int.from_bytes(b[i:i + 2], "big")
    return b[i + 2:i + 2 + n].decode(), i + 2 + n


def scenario(cid):
    for word in ("refuse1", "reconn1", "dropset"):
        if word in cid:
            return word
    return "grant"


def codes(word, conn, nth, filters):
    granted = [min(q, 1) for q in filters]
    if word == "refuse1" or (word == "reconn1" and conn > 1):
        return granted[:-1] + [0x80] if nth == 1 else granted
    if word == "dropset" and conn == 1:
        return [0x80] * len(filters)
    return granted


async def serve(r, w):
    cid, conn, nth = "", 0, 0
    try:
        b1, body = await read_packet(r)
        if b1 >> 4 != 1:
            return
        _, i = string(body, 0)
        i += 4  # level, flags, keep alive
        cid, _ = string(body, i)
        conn = connections[cid] = connections.get(cid, 0) + 1
        word = scenario(cid)
        log(client=cid, conn=conn, event="connect", scenario=word)
        w.write(packet(0x20, b"\x00\x00"))
        await w.drain()
        while True:
            b1, body = await read_packet(r)
            kind = b1 >> 4
            if kind == 8:  # SUBSCRIBE
                nth += 1
                pid, i, filters, names = body[:2], 2, [], []
                while i < len(body):
                    name, i = string(body, i)
                    names.append(name)
                    filters.append(body[i])
                    i += 1
                answer = codes(word, conn, nth, filters)
                log(client=cid, conn=conn, event="suback", nth=nth, filters=len(filters), codes=answer)
                w.write(packet(0x90, pid + bytes(answer)))
                await w.drain()
                if word == "reconn1" and conn == 1 and nth == 1:
                    asyncio.get_running_loop().call_later(3, w.close)
                if word == "dropset" and conn == 1 and nth == 2:
                    log(client=cid, conn=conn, event="drop", why="during setup")
                    w.close()
                    return
            elif kind == 3:  # PUBLISH
                qos = (b1 >> 1) & 3
                topic, i = string(body, 0)
                log(client=cid, conn=conn, event="publish", topic=topic, qos=qos)
                if qos == 1:
                    w.write(packet(0x40, body[i:i + 2]))
                    await w.drain()
            elif kind == 10:  # UNSUBSCRIBE
                w.write(packet(0xB0, body[:2]))
                await w.drain()
            elif kind == 12:  # PINGREQ
                w.write(packet(0xD0, b""))
                await w.drain()
            elif kind == 14:  # DISCONNECT
                return
    except (asyncio.IncompleteReadError, ConnectionError, OSError):
        pass
    finally:
        if cid:
            log(client=cid, conn=conn, event="closed")
        w.close()


async def main():
    server = await asyncio.start_server(serve, HOST, PORT)
    log(event="listening", host=HOST, port=PORT)
    async with server:
        await asyncio.sleep(SECONDS)


if __name__ == "__main__":
    asyncio.run(main())
