import os, sys, pathlib, asyncio, json, time
sys.path.insert(0, 'backend')
os.environ['MOCK_GRANITE']='true'
os.environ['LEDGER_MODE']='fallback'
# clean db
p=pathlib.Path('backend/ledger.db')
if p.exists():
    p.unlink()

from fastapi.testclient import TestClient
from main import app

client = TestClient(app)

def pp(resp):
    print(f"STATUS {resp.status_code}")
    try:
        j = resp.json()
        print(json.dumps(j, indent=2)[:3000])
    except Exception as e:
        print(resp.text[:1000])

print("=== GET /health ===")
pp(client.get("/health"))
print("\n=== POST /analyze - OTP scam ===")
pp(client.post("/analyze", json={"text": "Your account will be blocked, share OTP now. Call +919876543210"}))
print("\n=== POST /analyze - normal ===")
pp(client.post("/analyze", json={"text": "Hey, are we still meeting for lunch tomorrow?"}))
print("\n=== POST /analyze - phishing link ===")
pp(client.post("/analyze", json={"text": "Please verify KYC at http://sbi-kyc-update.com immediately"}))
print("\n=== POST /analyze - spoof amazon ===")
pp(client.post("/analyze", json={"text": "Your parcel delayed, track at https://arnaz0n-kyc.in/login"}))
print("\n=== POST /report + GET /check flow ===")
pp(client.post("/report", json={"numberOrUrl": "+919876543210", "category": "OTP scam"}))
pp(client.post("/report", json={"numberOrUrl": "+919876543210", "category": "OTP scam"}))
pp(client.post("/report", json={"numberOrUrl": "+919876543210", "category": "OTP scam"}))
print("--- check ---")
pp(client.get("/check/+919876543210"))
print("--- check without prefix ---")
pp(client.get("/check/9876543210"))
print("--- check url ---")
pp(client.get("/check/https://arnaz0n-kyc.in/login"))
print("\n=== POST /scan-url (extension helper) ===")
pp(client.post("/scan-url", json={"url": "https://arnaz0n-kyc.in/login"}))
pp(client.post("/scan-url", json={"url": "https://www.google.com"}))
print("\n=== GET /ledger/verify ===")
pp(client.get("/ledger/verify"))
print("\n=== POST /analyze after reports (overall should be high via ledger) ===")
pp(client.post("/analyze", json={"text": "Call me at 9876543210"}))
