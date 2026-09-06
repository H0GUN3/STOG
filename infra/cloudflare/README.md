# STOG Cloudflare Tunnel

Use Cloudflare Tunnel to expose the local STOG backend to a physical phone
over LTE/5G and Wi-Fi. The Android app sends social-login requests to
`POST /auth/social` through the public HTTPS hostname.

## Quick Tunnel

For a temporary test tunnel, no named tunnel or credentials file is needed:

```bat
winget install --id Cloudflare.cloudflared --exact
set SPRING_PROFILES_ACTIVE=prod,gcs-write
gradlew.bat -p backend bootRun
cloudflared tunnel --protocol http2 --url http://127.0.0.1:8080 --no-autoupdate
```

Use the printed `https://*.trycloudflare.com` URL as the phone APK endpoint.
The URL changes when the process restarts, so rebuild the APK whenever it
changes and update the ignored local `STOG_API_BASE_URL` value before
building. Keep the backend and `cloudflared` processes running while testing.
This workstation cannot use outbound QUIC/UDP, so `--protocol http2` is
required here. A named tunnel is preferred for repeatable login testing.

## Named Tunnel

1. Install `cloudflared` and authenticate it with the Cloudflare account.
2. Create a named tunnel and DNS hostname.
3. Copy `config.example.yml` to `config.yml`.
4. Replace the tunnel ID, credentials path, and API hostname.
5. Start the backend:

```bat
set SPRING_PROFILES_ACTIVE=prod,gcs-write
gradlew.bat -p backend bootRun
```

6. Start the tunnel:

```bat
cloudflared tunnel --protocol http2 ^
  --config infra/cloudflare/config.yml run
```

7. Build the phone APK with the public HTTPS hostname:

```bat
gradlew.bat :app:assembleDebug ^
  -PstogApiTarget=phone ^
  -PstogApiBaseUrl=https://REPLACE_WITH_STOG_API_HOSTNAME
```

The APK must use the hostname, not `10.0.2.2`, `localhost`, or a local LAN
address. The phone build rejects every non-HTTPS endpoint, including the
ADB reverse-development path.

## Verification

Check the public backend path before building the APK:

```bat
curl -i https://REPLACE_WITH_STOG_API_HOSTNAME/feed
```

The endpoint must return a response from STOG, not a Cloudflare `530`.
An invalid social token should return `401`; a real Kakao or Google token
still requires the corresponding provider-console and backend environment
configuration. Never put provider secrets, tunnel credentials, or a temporary
Quick Tunnel URL in Git.
