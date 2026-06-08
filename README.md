# Go Home Client

This repository contains the Go Home client projects:

- `client-ui`: shared Vue 3 UI
- `client-android`: Android WebView + native VPN/UDP tunnel implementation
- `client-pc`: Windows desktop shell
- `client-core`: Go core used by desktop/client experiments
- `client-ios` and `client-harmony`: reserved mobile platform projects

The client connects to a public Go Home server, lists visible families, selects one family, and then establishes a pure UDP direct tunnel to the bound home server. It never uses relay traffic.

## Build Android Locally

```bash
cd client-ui
npm ci
npm run build
cd ..
rm -rf client-android/app/src/main/assets/ui
mkdir -p client-android/app/src/main/assets/ui
cp -R client-ui/dist/. client-android/app/src/main/assets/ui/
gradle --project-dir client-android :app:assembleDebug
```

The APK is generated under:

```text
client-android/app/build/outputs/apk/debug/
```

## Build Windows Locally

```bash
cd client-ui
npm ci
npm run build
cd ..
dotnet publish client-pc/GoHome.Pc.csproj -c Release -r win-x64 --self-contained true -o artifacts/windows
mkdir -p artifacts/windows/ui
cp -R client-ui/dist/. artifacts/windows/ui/
```

## GitHub Actions Artifacts

Every push to `main` builds and uploads:

- `go-home-android-debug-apk`
- `go-home-windows-win-x64`

Open the repository Actions page, choose the latest successful CI run, and download the artifact you need.

## Network Modes

- Real LAN mode uses the home LAN CIDR directly.
- Virtual mapped mode maps a spare `/24` client-side CIDR to the real home LAN `/24`; for example `192.168.6.5` maps to `192.168.3.5`.
- LAN-only route policy sends only home LAN traffic through the tunnel.
- Full-home route policy sends all client traffic through the home network and uses the home router DNS path so router-side proxy policies can apply.

## Privacy Note

The UI must not ship with a personal server address, password, or authorization code. Recent servers are stored only in the local device storage after the user enters them.
