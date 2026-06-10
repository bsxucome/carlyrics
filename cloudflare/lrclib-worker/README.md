# CarLyrics LRCLIB Worker

Restricted Cloudflare Worker proxy for the LRCLIB API.

Supported routes:

- `GET /health`
- `GET /api/get`
- `GET /api/search`

Deploy from this directory:

```powershell
$env:CLOUDFLARE_API_TOKEN = [Environment]::GetEnvironmentVariable(
    "CLOUDFLARE_API_TOKEN",
    "User"
)
$env:CLOUDFLARE_ACCOUNT_ID = [Environment]::GetEnvironmentVariable(
    "CLOUDFLARE_ACCOUNT_ID",
    "User"
)
npx --yes wrangler deploy
```
