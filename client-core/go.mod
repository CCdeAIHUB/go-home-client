module gohome/client-core

go 1.25.1

require (
	github.com/gorilla/websocket v1.5.3
	gohome/shared v0.0.0
	golang.zx2c4.com/wireguard v0.0.0-20250521234502-f333402bd9cb
)

require (
	github.com/tjfoc/gmsm v1.4.1 // indirect
	golang.org/x/crypto v0.48.0 // indirect
	golang.org/x/net v0.50.0 // indirect
	golang.org/x/sys v0.41.0 // indirect
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
)

replace gohome/shared => ../shared/go
