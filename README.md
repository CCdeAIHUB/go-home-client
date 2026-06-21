# Go Home Client

This repository contains the Go Home client projects:

- `client-ui`: shared Vue 3 UI
- `client-android`: Android WebView + native VPN/UDP tunnel implementation
- `client-pc`: Windows desktop shell
- `client-core`: Go tunnel core used by desktop builds
- `client-ios` and `client-harmony`: reserved platform projects

The client connects to a public Go Home server, lists visible families, selects one family, and establishes a pure UDP direct tunnel to the bound home server. It never uses relay traffic.

## What You Need

- A running Go Home public server.
- A visible family with one online home server.
- The public server address and authorization code.
- Android VPN permission for Android clients.

## Install Android

1. Open the repository Actions page.
2. Choose the latest successful `CI` run.
3. Download `go-home-android-debug-apk`.
4. Install `app-debug.apk` on the phone.
5. Open Go Home, enter your public server address and authorization code, then select a family.

Example server address:

```text
http://YOUR_PUBLIC_SERVER:8080/
```

Android will ask for VPN permission before creating the local virtual network path.

## Install Windows

1. Open the repository Actions page.
2. Choose the latest successful `CI` run.
3. Download `go-home-windows-win-x64`.
4. Extract the artifact.
5. Run `GoHome.Pc.exe`.

If you have GitHub CLI installed, Windows users can download the latest successful artifacts with:

```powershell
.\scripts\download-latest-artifacts.ps1
```

The script writes files under `artifacts\latest`.

## Network Modes

- Real LAN mode uses the real home LAN CIDR directly.
- Virtual mapped mode maps a spare `/24` client-side CIDR to the real home LAN `/24`; for example `192.168.6.5` maps to `192.168.3.5`.
- LAN-only route policy sends only home LAN traffic through the tunnel.
- Full-home route policy sends all client traffic through the home network and uses the home router DNS path so router-side proxy policies can apply.

On Android, Go Home excludes its own control traffic from the VPN route. This keeps WebSocket signaling and UDP tunnel maintenance outside the VPN while other app traffic can use the home network path.

## Build Android Locally

Requirements:

- Node.js 24
- JDK 17
- Android SDK platform/build-tools 35
- Gradle 8.9 or newer

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

Requirements:

- Node.js 24
- Go 1.25
- .NET 10 SDK

```bash
cd client-ui
npm ci
npm run build
cd ..
dotnet publish client-pc/GoHome.Pc.csproj -c Release -r win-x64 --self-contained true -o artifacts/windows
mkdir -p artifacts/windows/ui
cp -R client-ui/dist/. artifacts/windows/ui/
```

## Troubleshooting

Android app opens but cannot connect to the public server:

- Confirm the server address includes the correct port.
- Confirm the authorization code is correct.
- Confirm the public server TCP port is reachable from the phone network.

Android direct tunnel times out:

- Confirm the home server is online in the family list.
- Try again from mobile data and Wi-Fi to compare NAT behavior.
- Keep the app in foreground during the first connection if the phone vendor has aggressive background restrictions.
- Check Android logs with `adb logcat | grep GoHome`.

Android page overlaps status or navigation bars:

- Install the latest APK. The native shell syncs status and navigation bar colors with the app theme.

Full-home mode cannot access internet:

- Confirm the home server is updated.
- Confirm the home router has normal internet access.
- If using router-side proxy rules such as Passwall, confirm those rules apply to LAN-originated traffic.

Windows client cannot start:

- Extract the whole artifact directory, not only `GoHome.Pc.exe`.
- Keep `ui`, `wintun.dll`, and the runtime files next to the executable.

## CI Artifacts

Every push to `main` runs GitHub Actions. The workflow:

- runs Go core tests on Linux, macOS, and Windows;
- builds the shared Vue UI;
- builds the Android debug APK;
- publishes the Windows x64 client artifact;
- publishes tagged releases for `v*` tags.

## Privacy Note

The UI must not ship with a personal server address, password, authorization code, SSH credential, or private key. Recent servers are stored only in local device storage after the user enters them.
