# Client Core

`client-core` is the first real client-side tunnel backend in the workspace.
It authenticates as a client over the public server WebSocket, requests a P2P
session for an accessible family, punches UDP directly to the bound home
server, encrypts the session key for the home server SM2 identity, and verifies
the SM4 tunnel with an encrypted ping/pong frame.

List families:

```powershell
go run ./cmd/client-core -server ws://127.0.0.1:8080/ws -auth-code GOHOME-CHANGE-ME
```

Connect one family and keep the direct tunnel alive:

```powershell
go run ./cmd/client-core -server ws://127.0.0.1:8080/ws -auth-code GOHOME-CHANGE-ME -family 1
```

Run a direct handshake verification and exit:

```powershell
go run ./cmd/client-core -server ws://127.0.0.1:8080/ws -auth-code GOHOME-CHANGE-ME -family 1 -once
```

This backend is now shipped into the Windows artifact as
`go-home-client-core.exe`. The UI shells still need to attach JSAPI,
virtual-adapter, Android `VpnService`, DHCP proxy, ARP proxy, LAN scan, and
packet forwarding work to this live tunnel core.
