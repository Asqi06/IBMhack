import asyncio, os, json, httpx
from dotenv import load_dotenv
load_dotenv(override=True)
import sys
sys.path.insert(0,'backend')
from agents.watsonx_client import _get_iam_token

regions = [
    "https://us-south.ml.cloud.ibm.com",
    "https://eu-de.ml.cloud.ibm.com",
    "https://eu-gb.ml.cloud.ibm.com",
    "https://jp-tok.ml.cloud.ibm.com",
    "https://us-east.ml.cloud.ibm.com",
]
project_id = "8fedc372-2bf6-4818-bd19-1e2442fe1b05"
alt_project = "9a21b9d8-8ae8-48b6-974c-a8ab90f1485d"

async def try_chat(region, pid):
    async with httpx.AsyncClient() as client:
        tok = await _get_iam_token(client)
        url = f"{region}/ml/v1/text/chat?version=2023-05-29"
        payload = {
            "model_id": "ibm/granite-4-h-small",
            "project_id": pid,
            "messages": [{"role":"user","content":"Reply ONLY {\"risk\":\"low\",\"category\":\"none\",\"reason\":\"test\"}"}],
            "max_tokens": 50,
            "temperature": 0,
        }
        headers = {"Authorization": f"Bearer {tok}", "Content-Type":"application/json"}
        resp = await client.post(url, json=payload, headers=headers, timeout=30.0)
        print(f"region {region} pid {pid[:8]} => {resp.status_code}")
        txt = resp.text[:800]
        print(txt[:500])
        print("---")

async def try_dataplatform():
    async with httpx.AsyncClient() as client:
        tok = await _get_iam_token(client)
        # Try data platform project get
        for base in ["https://api.dataplatform.cloud.ibm.com", "https://eu-de.dataplatform.cloud.ibm.com", "https://jp-tok.dataplatform.cloud.ibm.com"]:
            url = f"{base}/v2/projects/{project_id}"
            headers = {"Authorization": f"Bearer {tok}"}
            resp = await client.get(url, headers=headers, timeout=15.0)
            print(f"dataplatform {base} => {resp.status_code} {resp.text[:600]}")

async def main():
    await try_dataplatform()
    print("\n=== try chat regions ===")
    for r in regions:
        await try_chat(r, project_id)
        await try_chat(r, alt_project)

import asyncio
asyncio.run(main())
