"""Builds the offline lookup tables in app/src/main/assets from public sources (see README):
  data/oui.csv                  IEEE MA-L registry  https://standards-oui.ieee.org/oui/oui.csv
  data/company_identifiers.yaml Bluetooth SIG assigned numbers (bitbucket.org/bluetooth-SIG/public)
  data/service_uuids.yaml, data/member_uuids.yaml
Output: oui.dat (gzip, "AABBCC\tVendor"), bt_companies.txt ("0x004C\tApple, Inc."), bt_uuids.txt ("180F\tBattery")."""
import csv, gzip, re, sys, os
here = os.path.dirname(os.path.abspath(__file__))
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "..", "app", "src", "main", "assets")
d = os.path.join(here, "data")
rows = {}
with open(os.path.join(d, "oui.csv"), encoding="utf-8") as f:
    for r in csv.DictReader(f):
        name = re.sub(r"\s+", " ", r["Organization Name"]).strip()
        rows[r["Assignment"].upper()] = name
with gzip.open(os.path.join(out, "oui.dat"), "wt", encoding="utf-8", compresslevel=9) as f:
    for k in sorted(rows): f.write(f"{k}\t{rows[k]}\n")
def yaml_pairs(path, key):
    txt = open(path, encoding="utf-8").read()
    for m in re.finditer(r"-\s*" + key + r":\s*0x([0-9A-Fa-f]+)\s*\n\s*name:\s*(.+)", txt):
        v = m.group(2).strip()
        if v[:1] in "'\"": v = v[1:-1].replace("''", "'")
        yield m.group(1).upper(), v
comp = dict(yaml_pairs(os.path.join(d, "company_identifiers.yaml"), "value"))
with open(os.path.join(out, "bt_companies.txt"), "w", encoding="utf-8") as f:
    for k in sorted(comp, key=lambda x: int(x, 16)): f.write(f"{k.zfill(4)}\t{comp[k]}\n")
uu = {}
for fn in ("member_uuids.yaml", "service_uuids.yaml"):
    uu.update(dict(yaml_pairs(os.path.join(d, fn), "uuid")))
with open(os.path.join(out, "bt_uuids.txt"), "w", encoding="utf-8") as f:
    for k in sorted(uu, key=lambda x: int(x, 16)): f.write(f"{k.zfill(4)}\t{uu[k]}\n")
print(len(rows), "OUIs,", len(comp), "companies,", len(uu), "UUIDs")
