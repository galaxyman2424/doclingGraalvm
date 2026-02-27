import sys
import json

for line in sys.stdin:
    req = json.loads(line)
    print(json.dumps({"echo": req["path"]}), flush=True)