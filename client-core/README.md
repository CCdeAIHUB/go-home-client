# Client Core

`client-core` is the Go tunnel core used by desktop/client builds. It authenticates as a client over the public server WebSocket, requests a P2P session for an accessible family, punches UDP directly to the bound home server, and verifies the encrypted tunnel.

## Local Test

List visible families:

```powershell
go run ./cmd/client-core -server ws://YOUR_PUBLIC_SERVER:8080/ws -auth-code GOHOME-CHANGE-ME
```

Connect one family and keep the direct tunnel alive:

```powershell
go run ./cmd/client-core -server ws://YOUR_PUBLIC_SERVER:8080/ws -auth-code GOHOME-CHANGE-ME -family 1
```

Run one direct handshake verification and exit:

```powershell
go run ./cmd/client-core -server ws://YOUR_PUBLIC_SERVER:8080/ws -auth-code GOHOME-CHANGE-ME -family 1 -once
```

## Common Errors

No visible families:

- Confirm the public server has a public family, or that this device is authorized for a private family.
- Confirm the home server is bound to that family.

Handshake times out:

- Confirm the home server is online.
- Confirm the public server UDP discovery port is open.
- Try from a different client network to compare NAT behavior.

## Security

Use placeholders in docs and tests. Do not commit real server addresses, authorization codes, passwords, SSH credentials, or private keys.
