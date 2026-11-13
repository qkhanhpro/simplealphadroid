# Decode PTP command containers (bulk OUT) + responses from a USBPcap capture via tshark.
import subprocess, sys, datetime, struct
pcap = sys.argv[1]
skip_lv = True
cmd = [r"C:\Program Files\Wireshark\tshark.exe", "-r", pcap, "-T", "fields", "-E", "separator=|",
       "-e", "frame.time_epoch", "-e", "usb.endpoint_address", "-e", "usb.capdata",
       "-Y", "usb.transfer_type==3 && usb.capdata"]
p = subprocess.run(cmd, capture_output=True, text=True)
pending = {}  # tid -> (op, params, t)
last_op = None
for line in p.stdout.splitlines():
    try:
        t, ep, data = line.split("|")
    except ValueError:
        continue
    b = bytes.fromhex(data.replace(":", ""))
    if len(b) < 12:
        continue
    ln, typ, code, tid = struct.unpack_from("<IHHI", b, 0)
    ts = datetime.datetime.fromtimestamp(float(t)).strftime("%H:%M:%S.%f")[:-3]
    epi = int(ep, 16)
    if typ == 1 and not (epi & 0x80):
        params = [struct.unpack_from("<I", b, 12 + 4 * i)[0] for i in range((min(ln, len(b)) - 12) // 4)]
        last_op = (code, params)
        if skip_lv and code == 0x1009 and params[:1] == [0xFFFFC002]:
            continue
        print(f"{ts} CMD  {code:#06x} tid={tid} p=[{', '.join(hex(x) for x in params)}]")
    elif typ == 2 and not (epi & 0x80):
        if last_op and last_op[0] in (0x9205, 0x9207):
            print(f"{ts}  out {b[12:12+16].hex(' ')}")
    elif typ == 3 and (epi & 0x80):
        if last_op and skip_lv and last_op[0] == 0x1009 and last_op[1][:1] == [0xFFFFC002]:
            continue
        if last_op and last_op[0] == 0x9209:
            continue
        print(f"{ts}  RSP {code:#06x}")
    elif typ == 2 and (epi & 0x80):
        if last_op and last_op[0] in (0x1008, 0x9203) :
            print(f"{ts}  in({ln}) {b[12:12+48].hex(' ')}")
