package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"sync"
	"syscall"
	"time"

	"github.com/gorilla/websocket"

	"gohome/shared/protocol"
	"gohome/shared/security"
)

func main() {
	serverURL := flag.String("server", "ws://127.0.0.1:8080/ws", "public server websocket URL")
	authCode := flag.String("auth-code", "GOHOME-CHANGE-ME", "server authorization code")
	familyID := flag.Int64("family", 0, "family id to connect; omit to list accessible families")
	udpPort := flag.Int("udp-port", 47778, "local UDP port for P2P hole punching")
	mode := flag.String("mode", "real", "preferred network mode: real or mapped")
	virtualCIDR := flag.String("virtual-cidr", "", "mapped mode client-side virtual CIDR")
	virtualMAC := flag.String("virtual-mac", "", "client virtual MAC for the home lease")
	identityFile := flag.String("identity-file", defaultIdentityFile(), "SM2 identity persistence file")
	timeout := flag.Duration("timeout", 20*time.Second, "direct UDP handshake timeout")
	once := flag.Bool("once", false, "exit after direct UDP handshake and encrypted ping verification")
	controlAddr := flag.String("control-addr", "", "local HTTP control address for UI shells")
	uiDir := flag.String("ui-dir", "", "built client UI directory served by the local control address")
	flag.Parse()

	if *controlAddr != "" {
		if err := runControl(*controlAddr, *uiDir, *udpPort, *identityFile); err != nil {
			log.Fatal(err)
		}
		return
	}

	identity, err := security.LoadOrCreateIdentity(*identityFile)
	if err != nil {
		log.Fatalf("identity: %v", err)
	}
	deviceID := identity.DeviceID("client")

	udpConn, err := net.ListenPacket("udp", fmt.Sprintf(":%d", *udpPort))
	if err != nil {
		log.Fatalf("udp listen: %v", err)
	}
	defer udpConn.Close()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	rpc, err := dialRPC(ctx, *serverURL)
	if err != nil {
		log.Fatalf("connect public server: %v", err)
	}
	defer rpc.Close()
	rpc.HandleEvents(func(env protocol.Envelope) {
		answerLatencyProbe(rpc, env)
	})

	now := time.Now()
	if _, err := rpc.Call(ctx, protocol.ActionDeviceAuth, protocol.DeviceAuthParams{
		DeviceID:   deviceID,
		DeviceType: protocol.DeviceTypeClient,
		AuthCode:   *authCode,
		PublicKey:  identity.PublicPEM,
		TimeKey:    security.GenerateTimeKey(*authCode, now),
		Timestamp:  now.Unix(),
		UDPPort:    *udpPort,
	}); err != nil {
		log.Fatalf("device auth: %v", err)
	}

	families, err := listFamilies(ctx, rpc)
	if err != nil {
		log.Fatalf("list families: %v", err)
	}
	if *familyID == 0 {
		printFamilies(families)
		return
	}

	offer, err := requestOffer(ctx, rpc, *familyID, *udpPort, *mode, *virtualCIDR, *virtualMAC)
	if err != nil {
		log.Fatalf("request hole punch: %v", err)
	}
	client, err := newPunchClient(udpConn, deviceID, offer)
	if err != nil {
		log.Fatalf("prepare punch client: %v", err)
	}

	handshakeCtx, cancel := context.WithTimeout(ctx, *timeout)
	defer cancel()
	ready, err := client.Connect(handshakeCtx, ctx)
	if err != nil {
		log.Fatalf("direct UDP handshake: %v", err)
	}
	log.Printf("direct UDP tunnel ready: session=%s home=%s lan=%s", offer.SessionID, ready.HomeDeviceID, ready.LANCIDR)

	if err := client.VerifyPing(handshakeCtx); err != nil {
		log.Fatalf("encrypted tunnel ping: %v", err)
	}
	log.Printf("encrypted tunnel ping confirmed")
	if *once {
		return
	}
	client.KeepAlive(ctx)
}

func listFamilies(ctx context.Context, rpc *rpcClient) ([]protocol.Family, error) {
	raw, err := rpc.Call(ctx, protocol.ActionClientFamilyList, map[string]any{})
	if err != nil {
		return nil, err
	}
	var families []protocol.Family
	if err := json.Unmarshal(raw, &families); err != nil {
		return nil, err
	}
	return families, nil
}

func printFamilies(families []protocol.Family) {
	if len(families) == 0 {
		fmt.Println("no accessible families")
		return
	}
	for _, family := range families {
		state := "offline"
		if family.HomeServerOnline {
			state = "online"
		}
		fmt.Printf("%d\t%s\t%s\t%s\t%s\n", family.ID, family.Name, family.Visibility, state, family.LANCIDR)
	}
}

func requestOffer(ctx context.Context, rpc *rpcClient, familyID int64, udpPort int, mode, virtualCIDR, virtualMAC string) (protocol.HolePunchOffer, error) {
	raw, err := rpc.Call(ctx, protocol.ActionP2PHolePunchReq, protocol.HolePunchRequestParams{
		FamilyID:         familyID,
		ClientUDPPort:    udpPort,
		PreferredMode:    mode,
		VirtualCIDR:      virtualCIDR,
		ClientVirtualMAC: virtualMAC,
	})
	if err != nil {
		return protocol.HolePunchOffer{}, err
	}
	var offer protocol.HolePunchOffer
	if err := json.Unmarshal(raw, &offer); err != nil {
		return protocol.HolePunchOffer{}, err
	}
	if offer.SessionID == "" || offer.Server.Endpoint == "" || offer.Server.PublicKey == "" {
		return protocol.HolePunchOffer{}, errors.New("hole punch offer is incomplete")
	}
	return offer, nil
}

func answerLatencyProbe(rpc *rpcClient, env protocol.Envelope) {
	if env.Action != protocol.EventDeviceLatencyProbe {
		return
	}
	var params struct {
		ProbeID string `json:"probe_id"`
	}
	if err := json.Unmarshal(env.Params, &params); err != nil {
		log.Printf("latency probe decode: %v", err)
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if _, err := rpc.Call(ctx, protocol.ActionStatsLatencyPong, protocol.LatencyPongParams{ProbeID: params.ProbeID}); err != nil {
			log.Printf("latency pong: %v", err)
		}
	}()
}

func defaultIdentityFile() string {
	dir, err := os.UserConfigDir()
	if err != nil || dir == "" {
		return ".go-home-client-sm2.pem"
	}
	return filepath.Join(dir, "go-home", "client-sm2.pem")
}

type rpcClient struct {
	conn *websocket.Conn

	writeMu sync.Mutex
	mu      sync.Mutex
	pending map[string]chan protocol.Envelope
	onEvent func(protocol.Envelope)
	seq     uint64
	closed  chan struct{}
}

func dialRPC(ctx context.Context, serverURL string) (*rpcClient, error) {
	conn, _, err := websocket.DefaultDialer.DialContext(ctx, serverURL, nil)
	if err != nil {
		return nil, err
	}
	client := &rpcClient{
		conn:    conn,
		pending: map[string]chan protocol.Envelope{},
		closed:  make(chan struct{}),
	}
	go client.readLoop()
	return client, nil
}

func (c *rpcClient) HandleEvents(fn func(protocol.Envelope)) {
	c.mu.Lock()
	c.onEvent = fn
	c.mu.Unlock()
}

func (c *rpcClient) Close() error {
	return c.conn.Close()
}

func (c *rpcClient) Call(ctx context.Context, action string, params any) (json.RawMessage, error) {
	id := c.nextID()
	env, err := protocol.Request(id, action, params)
	if err != nil {
		return nil, err
	}
	reply := make(chan protocol.Envelope, 1)
	c.mu.Lock()
	c.pending[id] = reply
	c.mu.Unlock()
	defer func() {
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
	}()

	c.writeMu.Lock()
	err = c.conn.WriteJSON(env)
	c.writeMu.Unlock()
	if err != nil {
		return nil, err
	}

	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-c.closed:
		return nil, errors.New("websocket closed")
	case env := <-reply:
		if env.Error != nil {
			return nil, fmt.Errorf("%s: %s", env.Error.Code, env.Error.Message)
		}
		return json.Marshal(env.Result)
	}
}

func (c *rpcClient) readLoop() {
	defer close(c.closed)
	for {
		var env protocol.Envelope
		if err := c.conn.ReadJSON(&env); err != nil {
			return
		}
		if env.ID != "" {
			c.mu.Lock()
			reply := c.pending[env.ID]
			c.mu.Unlock()
			if reply != nil {
				reply <- env
			}
			continue
		}
		c.mu.Lock()
		handler := c.onEvent
		c.mu.Unlock()
		if handler != nil {
			handler(env)
		}
	}
}

func (c *rpcClient) nextID() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.seq++
	return fmt.Sprintf("client-%d", c.seq)
}
