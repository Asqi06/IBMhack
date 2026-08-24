import asyncio, os, sys, pathlib
sys.path.insert(0, 'backend')
# Ensure env reload
from dotenv import load_dotenv
load_dotenv(override=True)
print("ENV check:", os.getenv("WATSONX_PROJECT_ID"), os.getenv("WATSONX_MODEL_ID"), os.getenv("MOCK_GRANITE"))
# clear ledger
p=pathlib.Path("backend/ledger.db")
if p.exists():
    p.unlink()
    print("cleared ledger.db")

# need to reload modules after env
for mod in list(sys.modules.keys()):
    if mod.startswith("agents"):
        del sys.modules[mod]
    if mod.startswith("orchestrator"):
        del sys.modules[mod]

import orchestrator
from agents.ledger_agent import report_scam

async def main():
    # seed ledger with one report for later check
    await report_scam("+919876543210", "OTP scam")
    await report_scam("+919876543210", "OTP scam")
    await asyncio.sleep(1)
    tests = [
        ("OTP - should be high", "Your account will be blocked, share OTP now. Call +919876543210"),
        ("Normal - should be low", "Hey are we still meeting for lunch tomorrow?"),
        ("KYC phishing - should be high with url", "Dear customer your SBI KYC expired, click http://sbi-kyc-update.com to avoid block"),
        ("Amazon spoof - high", "Your parcel delayed, track at https://arnaz0n-kyc.in/login"),
        ("Ledger check - high via number", "Call me at 9876543210"),
    ]
    for label, text in tests:
        print(f"\n=== {label} ===")
        print(f"Input: {text}")
        try:
            res = await orchestrator.analyze(text)
            print(f"overallRisk: {res['overallRisk']}")
            import json
            print(json.dumps(res['details'], indent=2)[:1200])
        except Exception as e:
            print(f"ERROR: {e}")
            import traceback; traceback.print_exc()
        await asyncio.sleep(4)  # spacing to avoid 429 free limit

asyncio.run(main())
