import socket, re, time, struct

LOCAL = "192.168.1.61"
found = {}
for st in ("ssdp:all","urn:schemas-upnp-org:device:MediaRenderer:1","upnp:rootdevice"):
    msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
           'MAN: "ssdp:discover"\r\nMX: 2\r\n' f"ST: {st}\r\n\r\n").encode()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try: s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    except Exception: pass
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 4)
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(LOCAL))
    s.bind((LOCAL, 0))
    s.settimeout(3)
    try:
        s.sendto(msg, ("239.255.255.250", 1900))
        t0=time.time()
        while time.time()-t0 < 4:
            try: data, addr = s.recvfrom(65535)
            except socket.timeout: break
            txt = data.decode(errors="replace")
            loc = re.search(r"(?im)^LOCATION:\s*(.+)$", txt)
            srv = re.search(r"(?im)^SERVER:\s*(.+)$", txt)
            k=(addr[0], loc.group(1).strip() if loc else "")
            found.setdefault(k,set())
            if srv: found[k].add(srv.group(1).strip())
    except Exception as e:
        print("send error", st, e)
    finally: s.close()

print("SSDP RESPONDERS:")
for (ip,loc),srv in sorted(found.items()):
    print(f"  {ip}  {loc}")
    for x in srv: print(f"      SERVER: {x}")
if not found: print("  (none)")
