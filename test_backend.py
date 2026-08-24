import asyncio, os, sys, pathlib
sys.path.insert(0, 'backend')
os.environ['MOCK_GRANITE']='true'
os.environ['LEDGER_MODE']='fallback'
p=pathlib.Path('backend/ledger.db')
if p.exists():
    p.unlink()
from agents.scam_text_agent import classify_text
from agents.url_risk_agent import check_url
from agents.ledger_agent import report_scam, check_number_reputation, verify_chain
import orchestrator

async def test():
    print('=== scam_text_agent ===')
    for msg in [
        'Your account will be blocked, share OTP now',
        'Dear customer your SBI KYC expired, click http://sbi-kyc-update.com to refund',
        'Hey, are we still meeting for lunch tomorrow?',
        'Congratulations! You won refund of 5000, visit http://arnaz0n-kyc.in',
    ]:
        r=await classify_text(msg)
        print(f"  in: {msg[:50]!r} -> {r}")
    print('=== url_risk_agent ===')
    for u in ['https://arnaz0n-kyc.in/login','https://www.google.com','http://sbi-kyc-update.com']:
        r=await check_url(u)
        print(f"  {u} -> {r}")
    print('=== ledger fallback ===')
    await report_scam('+919876543210','OTP scam')
    await report_scam('+919876543210','OTP scam')
    await report_scam('+919876543210','OTP scam')
    c=await check_number_reputation('+919876543210')
    print(f"  check 9876543210 -> {c}")
    c2=await check_number_reputation('+919999999999')
    print(f"  check 9999999999 -> {c2}")
    print('  verify:', verify_chain())
    print('=== orchestrator ===')
    texts=[
        'Your account will be blocked, act now! Share OTP 9876543210. Visit http://arnaz0n-kyc.in',
        'Hey normal message',
        'Call me at +919876543210',
    ]
    for t in texts:
        r=await orchestrator.analyze(t)
        print(f"  text: {t[:60]!r} -> overall={r['overallRisk']} details={r['details']}")

asyncio.run(test())
