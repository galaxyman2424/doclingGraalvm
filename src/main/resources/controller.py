import subprocess
import json

# start CPython worker once
worker = subprocess.Popen(
    ["python", "docling_worker.py"],
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    text=True
)

def process(path):
    worker.stdin.write(json.dumps({"path": path}) + "\n")
    worker.stdin.flush()
    return worker.stdout.readline()