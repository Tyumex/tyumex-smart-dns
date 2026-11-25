"""Exercise the published APK on an Android emulator. A local DNS fixture
lets us verify the full packet path without relying on public resolvers."""
import os, socket, struct, subprocess, threading, time, xml.etree.ElementTree as ET, re
def adb(*args,check=True):
    return subprocess.run(["adb",*args],text=True,capture_output=True,check=check).stdout.strip()
def shell(*args,check=True): return adb("shell",*args,check=check)
def tap(text):
    for _ in range(14):
        shell("uiautomator","dump","/sdcard/dns-ui.xml",check=False)
        raw=shell("cat","/sdcard/dns-ui.xml",check=False)
        if raw.startswith("<?xml"):
            for n in ET.fromstring(raw).iter("node"):
                if text.lower() in n.get("text","").lower():
                    x,y,r,b=map(int,re.findall(r"\d+",n.get("bounds")))
                    shell("input","tap",str((x+r)//2),str((y+b)//2));print("Tapped",n.get("text"),x,y,r,b,flush=True);return
        time.sleep(.5)
    raise RuntimeError("Button missing: "+text+"\n"+raw[-3000:])
def answer(q):
    p=12
    while q[p]:p+=1+q[p]
    p+=5
    typ=q[p-4:p-2]
    data=b"\x7f\x00\x00\x01"
    good=typ==b"\x00\x01"
    return q[:2]+struct.pack("!HHHHH",0x8180,1,int(good),0,0)+q[12:p]+(b"\xc0\x0c"+struct.pack("!HHIH",1,1,15,4)+data if good else b"")
def udp():
    s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.bind(("127.0.0.1",15353))
    while True:
        q,p=s.recvfrom(4096);s.sendto(answer(q),p)
def tcp():
    s=socket.socket();s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);s.bind(("127.0.0.1",15353));s.listen()
    while True:
        c,_=s.accept()
        with c,c.makefile("rb") as f:
            h=f.read(2)
            if h:
                a=answer(f.read(int.from_bytes(h,"big")));c.sendall(len(a).to_bytes(2,"big")+a)
for fn in (udp,tcp):threading.Thread(target=fn,daemon=True).start()
adb("root");adb("wait-for-device");adb("install","-r","Tyumex-Smart-DNS.apk")
pkg="app.tyumex.smartdns"
shell("settings","put","global","private_dns_mode","off")
for proto in ("udp","tcp"):
    shell("iptables","-t","nat","-A","OUTPUT","-d","217.144.190.216","-p",proto,"--dport","53","-j","DNAT","--to-destination","10.0.2.2:15353")
adb("unroot");adb("wait-for-device")
shell("appops","set",pkg,"ACTIVATE_VPN","allow")
shell("am","start","-W","-n",pkg+"/.Main");tap("Провайдер");tap("MagicDNS");tap("Включить DNS");tap_check=False
for attempt in range(25):
    result=shell("ping","-c","1","-W","1",f"dns-check-{attempt}.example.com",check=False)
    if "127.0.0.1" in result: tap_check=True;break
    time.sleep(.5)
if not tap_check:
    print(shell("ip","addr","show",check=False),flush=True)
    shell("uiautomator","dump","/sdcard/dns-ui.xml",check=False)
    raw=shell("cat","/sdcard/dns-ui.xml",check=False)
    print([n.get("text") for n in ET.fromstring(raw).iter("node") if n.get("text")],flush=True)
    raise AssertionError(result+"\n"+adb("logcat","-d","-s","AndroidRuntime:E","Go:E","Vpn:I","VpnManagerService:I")[-4500:])
# Direct TCP DNS on the tunnel endpoint (unprivileged shell identity).
q=bytes.fromhex("01000100000100000000000001610000010001")
cmd="printf '"+ "".join("\\%03o"%b for b in (len(q).to_bytes(2,"big")+q)) +"' | toybox nc -w 3 192.0.2.53 53 | toybox od -An -tx1"
wire=shell(cmd,check=False).replace(" ","").replace("\n",""); assert "7f000001" in wire,wire
tap("Отключить DNS")
for _ in range(16):
    if "192.0.2.1/" not in shell("ip","addr","show","tun0",check=False): break
    time.sleep(.5)
assert "192.0.2.1/" not in shell("ip","addr","show","tun0",check=False)
print("PASS: APK launch, provider selection, VPN, UDP and TCP DNS, disconnect")
