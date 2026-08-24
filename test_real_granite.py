import asyncio, os, sys
sys.path.insert(0, 'backend')
# force reload env after writing .env
from dotenv import load_dotenv
load_dotenv(override=True)
print("WATSONX_URL", os.getenv("WATSONX_URL"))
print("WATSONX_MODEL_ID", os.getenv("WATSONX_MODEL_ID"))
print("WATSONX_PROJECT_ID", os.getenv("WATSONX_PROJECT_ID"))
print("MOCK", os.getenv("MOCK_GRANITE"))
# ensure fresh import
if 'agents.watsonx_client' in sys.modules:
    del sys.modules['agents.watsonx_client']
if 'agents.scam_text_agent' in sys.modules:
    del sys.modules['agents.scam_text_agent']

from agents.watsonx_client import generate, _get_iam_token
import httpx
from agents.scam_text_agent import classify_text
from agents.url_risk_agent import check_url

async def test_iam():
    print("\n=== IAM token ===")
    async with httpx.AsyncClient() as client:
        tok = await _get_iam_token(client)
        print("token len", len(tok), "prefix", tok[:20])

async def test_generate():
    print("\n=== raw generate (chat) ===")
    prompt = 'You are fraud assistant. Reply ONLY JSON {"risk":"low","category":"none","reason":"test"} . Message: "hello"'
    txt = await generate(prompt)
    print("raw:", txt[:800])

async def test_classify():
    cases = [
        ("OTP scam", "Your account will be blocked, share OTP now. Call +919876543210"),
        ("KYC scam", "Dear customer your SBI KYC expired, click http://sbi-kyc-update.com to avoid block"),
        ("Normal", "Hey are we still meeting for lunch tomorrow?"),
        ("Phishing", "Congrats you won refund visit https://arnaz0n-kyc.in/login"),
    ]
    for label, msg in cases:
        r = await classify_text(msg)
        print(f"{label}: {msg[:50]!r} -> {r}")

async def test_url():
    for u in ["https://arnaz0n-kyc.in/login", "https://www.google.com"]:
        r = await check_url(u)
        print(f"URL {u} -> {r}")

async def main():
    await test_iam()
    await test_generate()
    await test_classify()
    await test_url()

asyncio.run(main())
