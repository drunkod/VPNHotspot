#!/usr/bin/env python3
"""Authenticated SOCKS5 UDP ASSOCIATE probe for rooted-device validation."""

from __future__ import annotations

import argparse
import ipaddress
import secrets
import socket
import struct
import sys
from dataclasses import dataclass

SOCKS_VERSION = 5
AUTH_VERSION = 1
METHOD_USERNAME_PASSWORD = 2
COMMAND_UDP_ASSOCIATE = 3
ADDRESS_IPV4 = 1
ADDRESS_DOMAIN = 3
ADDRESS_IPV6 = 4


@dataclass(frozen=True)
class Endpoint:
    host: str
    port: int


def read_exact(stream: socket.socket, size: int) -> bytes:
    chunks: list[bytes] = []
    remaining = size
    while remaining:
        chunk = stream.recv(remaining)
        if not chunk:
            raise EOFError(f"SOCKS5 control connection closed with {remaining} bytes missing")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def read_endpoint(stream: socket.socket, atyp: int) -> Endpoint:
    if atyp == ADDRESS_IPV4:
        host = socket.inet_ntop(socket.AF_INET, read_exact(stream, 4))
    elif atyp == ADDRESS_IPV6:
        host = socket.inet_ntop(socket.AF_INET6, read_exact(stream, 16))
    elif atyp == ADDRESS_DOMAIN:
        length = read_exact(stream, 1)[0]
        host = read_exact(stream, length).decode("ascii")
    else:
        raise ValueError(f"unsupported SOCKS5 address type {atyp}")
    port = struct.unpack("!H", read_exact(stream, 2))[0]
    return Endpoint(host, port)


def authenticate(stream: socket.socket, username: str, password: str) -> None:
    user = username.encode("utf-8")
    secret = password.encode("utf-8")
    if not 1 <= len(user) <= 255 or not 1 <= len(secret) <= 255:
        raise ValueError("username and password must encode to 1..255 bytes")

    stream.sendall(bytes((SOCKS_VERSION, 1, METHOD_USERNAME_PASSWORD)))
    version, method = read_exact(stream, 2)
    if version != SOCKS_VERSION or method != METHOD_USERNAME_PASSWORD:
        raise RuntimeError(f"proxy rejected username/password method: version={version} method={method}")

    stream.sendall(bytes((AUTH_VERSION, len(user))) + user + bytes((len(secret),)) + secret)
    auth_version, status = read_exact(stream, 2)
    if auth_version != AUTH_VERSION or status != 0:
        raise RuntimeError(f"SOCKS5 authentication failed: version={auth_version} status={status}")


def request_udp_associate(stream: socket.socket, proxy_host: str) -> Endpoint:
    request = bytes((SOCKS_VERSION, COMMAND_UDP_ASSOCIATE, 0, ADDRESS_IPV4)) + b"\x00" * 6
    stream.sendall(request)
    version, reply, reserved, atyp = read_exact(stream, 4)
    if version != SOCKS_VERSION or reserved != 0:
        raise RuntimeError(f"invalid UDP ASSOCIATE reply header: version={version} reserved={reserved}")
    endpoint = read_endpoint(stream, atyp)
    if reply != 0:
        raise RuntimeError(f"UDP ASSOCIATE failed with SOCKS5 reply code {reply}")
    if endpoint.host == "0.0.0.0":
        endpoint = Endpoint(proxy_host, endpoint.port)
    return endpoint


def encode_dns_name(name: str) -> bytes:
    labels = name.rstrip(".").split(".")
    encoded = bytearray()
    for label in labels:
        raw = label.encode("idna")
        if not raw or len(raw) > 63:
            raise ValueError(f"invalid DNS label {label!r}")
        encoded.append(len(raw))
        encoded.extend(raw)
    encoded.append(0)
    return bytes(encoded)


def build_dns_query(name: str) -> tuple[int, bytes]:
    transaction_id = secrets.randbits(16)
    header = struct.pack("!HHHHHH", transaction_id, 0x0100, 1, 0, 0, 0)
    question = encode_dns_name(name) + struct.pack("!HH", 1, 1)
    return transaction_id, header + question


def wrap_udp_request(target: Endpoint, payload: bytes) -> bytes:
    target_address = ipaddress.ip_address(target.host)
    if target_address.version != 4:
        raise ValueError("this MVP probe requires an IPv4 UDP target")
    return (
        b"\x00\x00\x00"
        + bytes((ADDRESS_IPV4,))
        + target_address.packed
        + struct.pack("!H", target.port)
        + payload
    )


def unwrap_udp_response(packet: bytes) -> tuple[Endpoint, bytes]:
    if len(packet) < 4 or packet[:2] != b"\x00\x00" or packet[2] != 0:
        raise RuntimeError("invalid SOCKS5 UDP response header")
    atyp = packet[3]
    offset = 4
    if atyp == ADDRESS_IPV4:
        if len(packet) < offset + 6:
            raise RuntimeError("truncated IPv4 SOCKS5 UDP response")
        host = socket.inet_ntop(socket.AF_INET, packet[offset : offset + 4])
        offset += 4
    elif atyp == ADDRESS_IPV6:
        if len(packet) < offset + 18:
            raise RuntimeError("truncated IPv6 SOCKS5 UDP response")
        host = socket.inet_ntop(socket.AF_INET6, packet[offset : offset + 16])
        offset += 16
    elif atyp == ADDRESS_DOMAIN:
        if len(packet) < offset + 1:
            raise RuntimeError("truncated domain SOCKS5 UDP response")
        length = packet[offset]
        offset += 1
        if len(packet) < offset + length + 2:
            raise RuntimeError("truncated domain SOCKS5 UDP response")
        host = packet[offset : offset + length].decode("ascii")
        offset += length
    else:
        raise RuntimeError(f"unsupported SOCKS5 UDP response address type {atyp}")
    port = struct.unpack("!H", packet[offset : offset + 2])[0]
    offset += 2
    return Endpoint(host, port), packet[offset:]


def validate_dns_response(payload: bytes, transaction_id: int) -> int:
    if len(payload) < 12:
        raise RuntimeError("truncated DNS response")
    response_id, flags, _, answer_count, _, _ = struct.unpack("!HHHHHH", payload[:12])
    if response_id != transaction_id:
        raise RuntimeError(f"DNS transaction ID mismatch: expected {transaction_id}, got {response_id}")
    if not flags & 0x8000:
        raise RuntimeError("DNS payload is not marked as a response")
    rcode = flags & 0x000F
    if rcode != 0:
        raise RuntimeError(f"DNS response returned RCODE {rcode}")
    return answer_count


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proxy-host", required=True, help="phone tethering IPv4 address")
    parser.add_argument("--proxy-port", required=True, type=int, help="SOCKS5 TCP listener port")
    parser.add_argument("--username", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--dns-name", default="example.com")
    parser.add_argument("--dns-server", default="1.1.1.1")
    parser.add_argument("--dns-port", default=53, type=int)
    parser.add_argument("--timeout", default=8.0, type=float)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    target = Endpoint(args.dns_server, args.dns_port)
    with socket.create_connection((args.proxy_host, args.proxy_port), timeout=args.timeout) as control:
        control.settimeout(args.timeout)
        authenticate(control, args.username, args.password)
        relay = request_udp_associate(control, args.proxy_host)
        print(f"UDP relay: {relay.host}:{relay.port}")

        transaction_id, query = build_dns_query(args.dns_name)
        request = wrap_udp_request(target, query)
        family = socket.AF_INET6 if ":" in relay.host else socket.AF_INET
        with socket.socket(family, socket.SOCK_DGRAM) as datagram:
            datagram.settimeout(args.timeout)
            datagram.sendto(request, (relay.host, relay.port))
            response, _ = datagram.recvfrom(65_535)

        remote, dns_payload = unwrap_udp_response(response)
        answer_count = validate_dns_response(dns_payload, transaction_id)
        print(
            f"PASS: UDP ASSOCIATE returned a valid DNS response from "
            f"{remote.host}:{remote.port} with {answer_count} answer(s)"
        )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, EOFError, RuntimeError, ValueError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
