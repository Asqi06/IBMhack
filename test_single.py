import asyncio, httpx, os, json, time
from dotenv import load_dotenv
load_dotenv(override=True)
import sys
sys.path.insert(0,'backend')
# override project id to alt which worked
os.environ["WATSONX_PROJECT_ID"]="9a21b9d8-8ae8-48b6-974c-a8ab90f1485d"
os.environ["WATSONX_MODEL_ID"]="ibm/granite-4-h-small"
os.environ["WATSONX_URL"]="https://us-south.ml.cloud.ibm.com"
os.environ["MOCK_GRANITE"]="false"
# need to clear cached env in watsonx_client
import importlib
if 'agents.watsonx_client' in sys.modules:
    del sys.modules['agents.watsonx_client']
import agents.watsonx_client as wc
# force reload env values inside module
wc.WATSONX_PROJECT_ID = os.getenv("WATSONX_PROJECT_ID")
wc.WATSONX_MODEL_ID = os.getenv("WATSONX_MODEL_ID")
wc.WATSONX_URL = os.getenv("WATSONX_URL")
wc.MOCK_GRANITE = False
wc._iam_token = None

async def test_one():
    await asyncio.sleep(3)
    prompt = """You are a fraud-detection assistant protecting users in India from scams.
Given a message, classify it and respond ONLY in this exact JSON format,
nothing else, no explanation outside the JSON:

{
  "risk": "high" | "medium" | "low",
  "category": "OTP scam" | "fake refund/KYC" | "phishing link" | "impersonation" | "none",
  "reason": "one short sentence explaining why"
}

Message to classify:
"Your account will be blocked, share OTP now"
"""
    print("calling generate...")
    try:
        txt = await wc.generate(prompt)
        print("SUCCESS:", txt[:1000])
        import json
        print("parsed:", json.loads(txt))
    except Exception as e:
        print("FAIL:", e)
        if hasattr(e, 'response'):
            try:
                print(e.response.text[:1000])
            except: pass

asyncio.run(test_one())
