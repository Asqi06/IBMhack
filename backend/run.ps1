param([int]$Port = 8000)
# Quick launcher — ensures MOCK mode if no keys, then starts uvicorn
if (-not (Test-Path -LiteralPath ".env")) {
  Copy-Item -LiteralPath ".env.example" -Destination ".env"
  Write-Host "Created .env from .env.example — fill WATSONX keys or keep MOCK_GRANITE=true for dev"
}
# Use venv python if available
$py = if (Get-Command python -ErrorAction SilentlyContinue) { "python" } else { "python3" }
& $py -m uvicorn main:app --host 0.0.0.0 --port $Port --reload
