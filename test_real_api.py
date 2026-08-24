import sys, pathlib
sys.path.insert(0, 'backend')
from fastapi.testclient import TestClient
import json, time, os
# Ensure env is loaded via file-relative in backend (already fixed)
# Clear ledger
p=pathlib.Path("backend/ledger.db")
if p.exists():
    p.unlink()
    print("cleared ledger")
from main import app
client = TestClient(app)
def pp(r):
    print(f"STATUS {r.status_code}")
    try:
        print(json.dumps(r.json(), indent=2)[:1500])
    except:
        print(r.text[:800])

print("=== GET / ==="); pp(client.get("/"))
print("\n=== POST /analyze OTP ===")
pp(client.post("/analyze", json={"text": "Your account will be blocked, share OTP now. Call +919876543210"}))
time.sleep(3)
print("\n=== POST /analyze normal ===")
pp(client.post("/analyze", json={"text": "Hey are we still meeting for lunch tomorrow?"}))
time.sleep(3)
print("\n=== POST /scan-url spoof ===")
pp(client.post("/scan-url", json={"url": "https://arnaz0n-kyc.in/login"}))
time.sleep(3)
print("\n=== POST /report + check ===")
pp(client.post("/report", json={"numberOrUrl": "+919999999999", "category": "phishing link"}))
pp(client.get("/check/+919999999999"))
print("\n=== /health ===")
pp(client.get("/health"))
