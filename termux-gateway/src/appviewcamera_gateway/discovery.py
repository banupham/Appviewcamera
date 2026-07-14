from __future__ import annotations

import asyncio
import ipaddress
import re
import socket
import time
import uuid
from urllib.parse import urlsplit

from .config import GatewaySettings


ONVIF_MULTICAST = ("239.255.255.250", 3702)
XADDRS_PATTERN = re.compile(r"<[^>]*XAddrs[^>]*>(.*?)</[^>]*XAddrs>", re.DOTALL)


def detect_ipv4_address() -> str | None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("1.1.1.1", 53))
        address = sock.getsockname()[0]
        if address.startswith("127."):
            return None
        return address
    except OSError:
        return None
    finally:
        sock.close()


def detect_ipv4_subnet() -> str | None:
    address = detect_ipv4_address()
    return str(ipaddress.ip_network(f"{address}/24", strict=False)) if address else None


def local_ipv4_addresses() -> set[str]:
    addresses = {"127.0.0.1"}
    detected = detect_ipv4_address()
    if detected:
        addresses.add(detected)
    try:
        for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            addresses.add(item[4][0])
    except OSError:
        pass
    return addresses


def _candidate_from_url(url: str) -> dict | None:
    try:
        parsed = urlsplit(url)
        if not parsed.hostname:
            return None
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        return {"host": parsed.hostname, "port": port, "source": "onvif", "service_url": url}
    except ValueError:
        return None


def discover_onvif(timeout_seconds: float = 2.0) -> list[dict]:
    message_id = uuid.uuid4()
    probe = f"""<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
 xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing"
 xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
 xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
 <e:Header><w:MessageID>uuid:{message_id}</w:MessageID>
 <w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
 <w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>
 <e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body>
</e:Envelope>""".encode()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    sock.settimeout(min(0.5, timeout_seconds))
    found: dict[tuple[str, int], dict] = {}
    try:
        sock.sendto(probe, ONVIF_MULTICAST)
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            try:
                payload, sender = sock.recvfrom(65535)
            except socket.timeout:
                continue
            text = payload.decode("utf-8", errors="ignore")
            matches = XADDRS_PATTERN.findall(text)
            for group in matches:
                for url in group.split():
                    candidate = _candidate_from_url(url)
                    if candidate:
                        found[(candidate["host"], candidate["port"])] = candidate
            if not matches:
                found[(sender[0], 80)] = {
                    "host": sender[0], "port": 80, "source": "onvif", "service_url": None
                }
    except OSError:
        # Một số thiết bị Android chặn multicast. Quét subnet vẫn tiếp tục.
        return []
    finally:
        sock.close()
    return list(found.values())


def normalize_subnets(configured: tuple[str, ...], max_hosts: int) -> list[ipaddress.IPv4Network]:
    values = list(configured)
    if not values:
        detected = detect_ipv4_subnet()
        if detected:
            values.append(detected)
    networks: list[ipaddress.IPv4Network] = []
    for value in values:
        network = ipaddress.ip_network(value, strict=False)
        if not isinstance(network, ipaddress.IPv4Network):
            raise ValueError("chỉ hỗ trợ subnet IPv4")
        host_count = max(0, network.num_addresses - 2)
        if host_count > max_hosts:
            raise ValueError(f"subnet {network} có {host_count} host, vượt giới hạn {max_hosts}")
        networks.append(network)
    return networks


async def scan_subnets(
    networks: list[ipaddress.IPv4Network],
    ports: tuple[int, ...],
    timeout_seconds: float,
    concurrency: int = 64,
    excluded_hosts: set[str] | None = None,
) -> list[dict]:
    semaphore = asyncio.Semaphore(concurrency)
    found: list[dict] = []

    async def probe(host: str, port: int) -> None:
        async with semaphore:
            writer = None
            try:
                _, writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout_seconds)
                found.append({"host": host, "port": port, "source": "tcp_scan", "service_url": None})
            except (OSError, asyncio.TimeoutError):
                return
            finally:
                if writer is not None:
                    writer.close()
                    await writer.wait_closed()

    excluded = excluded_hosts or set()
    tasks = [
        asyncio.create_task(probe(str(host), port))
        for network in networks
        for host in network.hosts()
        if str(host) not in excluded
        for port in ports
    ]
    if tasks:
        await asyncio.gather(*tasks)
    return found


async def discover_cameras(settings: GatewaySettings) -> list[dict]:
    local_addresses = local_ipv4_addresses()
    onvif = await asyncio.to_thread(discover_onvif, min(3.0, settings.discovery_timeout_seconds * 4))
    networks = normalize_subnets(settings.discovery_subnets, settings.discovery_max_hosts)
    scanned = await scan_subnets(
        networks,
        settings.discovery_ports,
        settings.discovery_timeout_seconds,
        excluded_hosts=local_addresses,
    )
    merged: dict[tuple[str, int], dict] = {}
    for candidate in scanned + onvif:
        if candidate["host"] in local_addresses:
            continue
        merged[(candidate["host"], candidate["port"])] = candidate
    def sort_key(item: dict) -> tuple:
        try:
            return (0, int(ipaddress.ip_address(item["host"])), item["port"])
        except ValueError:
            return (1, item["host"], item["port"])

    return sorted(merged.values(), key=sort_key)
