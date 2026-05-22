package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"path"
	"strings"
	"sync"
	"time"

	"gohome/shared/protocol"
	"gohome/shared/security"
	"gohome/shared/tunnel"
)

type clientManager struct {
	identity *security.Identity
	deviceID string
	udp      net.PacketConn
	udpPort  int

	mu           sync.RWMutex
	rpc          *rpcClient
	server       string
	authCode     string
	families     []protocol.Family
	tunnel       *managedTunnel
	publicRTTMS  int64
	lastRPCError string
	graceUntil   time.Time
	reconnect    context.CancelFunc
}

const websocketGracePeriod = 30 * time.Second

type managedTunnel struct {
	client    *punchClient
	offer     protocol.HolePunchOffer
	ready     tunnel.Ready
	mode      string
	virtual   string
	link      *virtualLink
	cancel    context.CancelFunc
	connected time.Time
	reported  punchStats
}

type tunnelOptions struct {
	Mode             string `json:"mode"`
	VirtualCIDR      string `json:"virtual_cidr"`
	ClientVirtualMAC string `json:"client_virtual_mac"`
}

type tunnelView struct {
	SessionID    string             `json:"session_id"`
	FamilyID     int64              `json:"family_id"`
	Mode         string             `json:"mode"`
	ClientHomeIP string             `json:"client_home_ip,omitempty"`
	VirtualCIDR  string             `json:"virtual_cidr,omitempty"`
	LANCIDR      string             `json:"lan_cidr,omitempty"`
	Devices      []tunnel.DeviceMap `json:"devices,omitempty"`
}

type tunnelStatus struct {
	WebSocket    string `json:"websocket"`
	UDP          string `json:"udp"`
	GraceSeconds int    `json:"grace_seconds"`
	LastError    string `json:"last_error,omitempty"`
}

type trafficView struct {
	Up        uint64  `json:"up"`
	Down      uint64  `json:"down"`
	Loss      float64 `json:"loss"`
	LatencyMS int64   `json:"latency_ms"`
	TunnelRTT int64   `json:"tunnel_rtt_ms"`
}

func newClientManager(identityFile string, udpPort int) (*clientManager, error) {
	identity, err := security.LoadOrCreateIdentity(identityFile)
	if err != nil {
		return nil, err
	}
	udpConn, err := net.ListenPacket("udp", fmt.Sprintf(":%d", udpPort))
	if err != nil {
		return nil, err
	}
	return &clientManager{
		identity: identity,
		deviceID: identity.DeviceID("client"),
		udp:      udpConn,
		udpPort:  localUDPPort(udpConn, udpPort),
	}, nil
}

func (m *clientManager) Close() error {
	m.Disconnect()
	return m.udp.Close()
}

func (m *clientManager) ConnectServer(ctx context.Context, server, authCode string) error {
	if strings.TrimSpace(server) == "" || strings.TrimSpace(authCode) == "" {
		return errors.New("server and authorization code are required")
	}
	m.Disconnect()

	rpc, err := m.dialAndAuth(ctx, server, authCode)
	if err != nil {
		m.setRPCError(err)
		return err
	}
	m.mu.Lock()
	m.rpc = rpc
	m.server = server
	m.authCode = authCode
	m.lastRPCError = ""
	m.graceUntil = time.Time{}
	m.mu.Unlock()
	m.watchRPC(rpc)

	_, _ = m.RefreshPublicLatency(ctx)
	return nil
}

func (m *clientManager) dialAndAuth(ctx context.Context, server, authCode string) (*rpcClient, error) {
	rpc, err := dialRPC(ctx, websocketURL(server))
	if err != nil {
		return nil, err
	}
	rpc.HandleEvents(func(env protocol.Envelope) {
		answerLatencyProbe(rpc, env)
	})

	now := time.Now()
	if _, err := rpc.Call(ctx, protocol.ActionDeviceAuth, protocol.DeviceAuthParams{
		DeviceID:   m.deviceID,
		DeviceType: protocol.DeviceTypeClient,
		AuthCode:   authCode,
		PublicKey:  m.identity.PublicPEM,
		TimeKey:    security.GenerateTimeKey(authCode, now),
		Timestamp:  now.Unix(),
		UDPPort:    m.udpPort,
	}); err != nil {
		_ = rpc.Close()
		return nil, err
	}
	return rpc, nil
}

func (m *clientManager) Disconnect() {
	m.mu.Lock()
	currentTunnel := m.tunnel
	m.tunnel = nil
	rpc := m.rpc
	m.rpc = nil
	m.families = nil
	m.graceUntil = time.Time{}
	reconnect := m.reconnect
	m.reconnect = nil
	m.mu.Unlock()

	if reconnect != nil {
		reconnect()
	}
	if currentTunnel != nil {
		if currentTunnel.link != nil {
			_ = currentTunnel.link.Close()
		}
		currentTunnel.cancel()
	}
	if rpc != nil {
		_ = rpc.Close()
	}
}

func (m *clientManager) ListFamilies(ctx context.Context) ([]protocol.Family, error) {
	rpc, err := m.rpcClient()
	if err != nil {
		return nil, err
	}
	families, err := listFamilies(ctx, rpc)
	if err != nil {
		m.setRPCError(err)
		return nil, err
	}
	m.mu.Lock()
	m.families = append([]protocol.Family(nil), families...)
	m.mu.Unlock()
	return families, nil
}

func (m *clientManager) ConnectFamily(ctx context.Context, familyID int64, options tunnelOptions) (tunnelView, error) {
	rpc, err := m.rpcClient()
	if err != nil {
		return tunnelView{}, err
	}
	if familyID <= 0 {
		return tunnelView{}, errors.New("family id is required")
	}
	mode := options.Mode
	if mode == "" {
		mode = "real"
	}
	if mode != "real" && mode != "mapped" {
		return tunnelView{}, fmt.Errorf("unsupported network mode %q", mode)
	}
	if mode == "mapped" && options.VirtualCIDR == "" {
		return tunnelView{}, errors.New("virtual CIDR is required for mapped mode")
	}
	if mode == "mapped" {
		if err := rejectCIDRConflict("virtual CIDR", options.VirtualCIDR); err != nil {
			return tunnelView{}, err
		}
	} else if cidr := m.familyCIDR(familyID); cidr != "" {
		if err := rejectCIDRConflict("family LAN CIDR", cidr); err != nil {
			return tunnelView{}, err
		}
	}
	if options.ClientVirtualMAC == "" {
		options.ClientVirtualMAC = virtualMAC(m.deviceID)
	}

	m.stopTunnel()
	offer, err := requestOffer(ctx, rpc, familyID, m.udpPort, mode, options.VirtualCIDR, options.ClientVirtualMAC)
	if err != nil {
		m.setRPCError(err)
		return tunnelView{}, err
	}
	if mode == "real" {
		if err := rejectCIDRConflict("family LAN CIDR", offer.Server.LANCIDR); err != nil {
			return tunnelView{}, err
		}
	}
	client, err := newPunchClient(m.udp, m.deviceID, offer)
	if err != nil {
		return tunnelView{}, err
	}
	runCtx, cancel := context.WithCancel(context.Background())
	ready, err := client.Connect(ctx, runCtx)
	if err != nil {
		cancel()
		return tunnelView{}, err
	}
	if err := client.VerifyPing(ctx); err != nil {
		cancel()
		return tunnelView{}, err
	}

	active := &managedTunnel{
		client:    client,
		offer:     offer,
		ready:     ready,
		mode:      mode,
		virtual:   options.VirtualCIDR,
		cancel:    cancel,
		connected: time.Now(),
	}
	if ready.ClientHomeIP != "" {
		link, err := newVirtualLink(runCtx, client, mode, ready.ClientHomeIP, ready.LANCIDR, options.VirtualCIDR)
		if err != nil {
			cancel()
			return tunnelView{}, err
		}
		active.link = link
	}
	m.mu.Lock()
	m.tunnel = active
	m.mu.Unlock()
	go client.KeepAlive(runCtx)
	return active.view(), nil
}

func (m *clientManager) TunnelStatus() tunnelStatus {
	m.mu.RLock()
	defer m.mu.RUnlock()
	status := tunnelStatus{WebSocket: "idle", UDP: "idle", LastError: m.lastRPCError}
	if m.rpc != nil {
		status.WebSocket = "connected"
	} else if m.reconnect != nil {
		status.WebSocket = "reconnecting"
	}
	if !m.graceUntil.IsZero() {
		status.GraceSeconds = graceSeconds(m.graceUntil, time.Now())
		if status.GraceSeconds > 0 {
			status.WebSocket = "grace"
		}
	}
	if m.tunnel != nil {
		status.UDP = "connected"
	}
	return status
}

func (m *clientManager) Traffic(ctx context.Context) trafficView {
	_, _ = m.RefreshPublicLatency(ctx)
	m.mu.RLock()
	active := m.tunnel
	latency := m.publicRTTMS
	m.mu.RUnlock()
	if active == nil {
		return trafficView{LatencyMS: latency}
	}
	stats := active.client.Stats()
	return trafficView{
		Up:        stats.Up,
		Down:      stats.Down,
		Loss:      stats.Loss,
		LatencyMS: latency,
		TunnelRTT: stats.RTTMS,
	}
}

func (m *clientManager) ActiveTunnel() (tunnelView, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.tunnel == nil {
		return tunnelView{}, false
	}
	return m.tunnel.view(), true
}

func (m *clientManager) RefreshPublicLatency(ctx context.Context) (int64, error) {
	rpc, err := m.rpcClient()
	if err != nil {
		return 0, err
	}
	m.mu.RLock()
	authCode := m.authCode
	m.mu.RUnlock()
	now := time.Now()
	raw, err := rpc.Call(ctx, protocol.ActionPing, protocol.HeartbeatParams{
		TimeKey:   security.GenerateTimeKey(authCode, now),
		Timestamp: now.Unix(),
	})
	if err != nil {
		m.setRPCError(err)
		return 0, err
	}
	var result struct {
		LatencyMS int64 `json:"latency_ms"`
	}
	if err := json.Unmarshal(raw, &result); err != nil {
		return 0, err
	}
	m.mu.Lock()
	m.publicRTTMS = result.LatencyMS
	m.mu.Unlock()
	return result.LatencyMS, nil
}

func (m *clientManager) rpcClient() (*rpcClient, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.rpc == nil {
		return nil, errors.New("public server is not connected")
	}
	return m.rpc, nil
}

func (m *clientManager) stopTunnel() {
	m.mu.Lock()
	active := m.tunnel
	m.tunnel = nil
	m.mu.Unlock()
	if active != nil {
		if active.link != nil {
			_ = active.link.Close()
		}
		active.cancel()
	}
}

func (m *clientManager) setRPCError(err error) {
	m.mu.Lock()
	m.lastRPCError = err.Error()
	m.mu.Unlock()
}

func (m *clientManager) familyCIDR(familyID int64) string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	for _, family := range m.families {
		if family.ID == familyID {
			return family.LANCIDR
		}
	}
	return ""
}

func rejectCIDRConflict(label, cidr string) error {
	conflict, err := CheckNetworkConflict(cidr)
	if err != nil {
		return err
	}
	if conflict.Conflict {
		return fmt.Errorf("%s %s overlaps local network: %s", label, cidr, strings.Join(conflict.Overlaps, ", "))
	}
	return nil
}

func (m *clientManager) watchRPC(rpc *rpcClient) {
	go func() {
		<-rpc.Done()
		m.rpcClosed(rpc)
	}()
	go m.heartbeatLoop(rpc)
}

func (m *clientManager) rpcClosed(rpc *rpcClient) {
	m.mu.Lock()
	if m.rpc != rpc {
		m.mu.Unlock()
		return
	}
	m.rpc = nil
	m.lastRPCError = "public server websocket disconnected"
	server := m.server
	authCode := m.authCode
	deadline := time.Now().Add(websocketGracePeriod)
	if m.tunnel != nil {
		m.graceUntil = deadline
	} else {
		m.graceUntil = time.Time{}
	}
	if m.reconnect != nil {
		m.reconnect()
	}
	ctx, cancel := context.WithCancel(context.Background())
	m.reconnect = cancel
	m.mu.Unlock()

	go m.reconnectLoop(ctx, server, authCode, deadline)
}

func (m *clientManager) reconnectLoop(ctx context.Context, server, authCode string, deadline time.Time) {
	for time.Now().Before(deadline) {
		attemptCtx, cancel := context.WithTimeout(ctx, 8*time.Second)
		rpc, err := m.dialAndAuth(attemptCtx, server, authCode)
		cancel()
		if err == nil {
			m.mu.Lock()
			if ctx.Err() != nil {
				m.mu.Unlock()
				_ = rpc.Close()
				return
			}
			m.rpc = rpc
			m.lastRPCError = ""
			m.graceUntil = time.Time{}
			m.reconnect = nil
			m.mu.Unlock()
			m.watchRPC(rpc)
			return
		}
		m.setRPCError(err)

		select {
		case <-ctx.Done():
			return
		case <-time.After(2 * time.Second):
		}
	}

	m.mu.Lock()
	active := m.tunnel
	m.tunnel = nil
	m.graceUntil = time.Time{}
	m.lastRPCError = "public server websocket reconnect grace period expired"
	m.mu.Unlock()
	if active != nil {
		if active.link != nil {
			_ = active.link.Close()
		}
		active.cancel()
	}
}

func (m *clientManager) heartbeatLoop(rpc *rpcClient) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for range ticker.C {
		m.mu.RLock()
		current := m.rpc
		authCode := m.authCode
		m.mu.RUnlock()
		if current != rpc {
			return
		}
		now := time.Now()
		ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
		_, err := rpc.Call(ctx, protocol.ActionPing, protocol.HeartbeatParams{
			TimeKey:   security.GenerateTimeKey(authCode, now),
			Timestamp: now.Unix(),
		})
		cancel()
		if err != nil {
			_ = rpc.Close()
			return
		}
		if err := m.reportTraffic(rpc); err != nil {
			_ = rpc.Close()
			return
		}
	}
}

func (m *clientManager) reportTraffic(rpc *rpcClient) error {
	active, stats, upDelta, downDelta := m.pendingTraffic()
	for _, report := range []struct {
		direction string
		bytes     uint64
	}{
		{direction: "up", bytes: upDelta},
		{direction: "down", bytes: downDelta},
	} {
		if report.bytes == 0 {
			continue
		}
		ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
		_, err := rpc.Call(ctx, protocol.ActionStatsTraffic, protocol.TrafficReportParams{
			Direction: report.direction,
			Bytes:     int64(report.bytes),
		})
		cancel()
		if err != nil {
			return err
		}
	}
	m.markTrafficReported(active, stats)
	return nil
}

func (m *clientManager) pendingTraffic() (*managedTunnel, punchStats, uint64, uint64) {
	m.mu.RLock()
	active := m.tunnel
	previous := punchStats{}
	if active != nil {
		previous = active.reported
	}
	m.mu.RUnlock()
	if active == nil {
		return nil, punchStats{}, 0, 0
	}
	stats := active.client.Stats()
	return active, stats, counterDelta(stats.Up, previous.Up), counterDelta(stats.Down, previous.Down)
}

func (m *clientManager) markTrafficReported(active *managedTunnel, stats punchStats) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if active != nil && m.tunnel == active {
		active.reported.Up = stats.Up
		active.reported.Down = stats.Down
	}
}

func counterDelta(current, previous uint64) uint64 {
	if current < previous {
		return current
	}
	return current - previous
}

func graceSeconds(deadline, now time.Time) int {
	remaining := deadline.Sub(now)
	if remaining <= 0 {
		return 0
	}
	return int((remaining + time.Second - 1) / time.Second)
}

func (t *managedTunnel) view() tunnelView {
	return tunnelView{
		SessionID:    t.offer.SessionID,
		FamilyID:     t.offer.FamilyID,
		Mode:         t.mode,
		ClientHomeIP: t.ready.ClientHomeIP,
		VirtualCIDR:  t.virtual,
		LANCIDR:      t.ready.LANCIDR,
		Devices:      append([]tunnel.DeviceMap(nil), t.ready.Devices...),
	}
}

func websocketURL(server string) string {
	value := strings.TrimSpace(server)
	if !strings.Contains(value, "://") {
		value = "ws://" + value
	}
	parsed, err := url.Parse(value)
	if err != nil {
		return value
	}
	if parsed.Scheme == "http" {
		parsed.Scheme = "ws"
	}
	if parsed.Scheme == "https" {
		parsed.Scheme = "wss"
	}
	if parsed.Path == "" || parsed.Path == "/" {
		parsed.Path = path.Join(parsed.Path, "/ws")
	}
	return parsed.String()
}

func localUDPPort(conn net.PacketConn, fallback int) int {
	if addr, ok := conn.LocalAddr().(*net.UDPAddr); ok {
		return addr.Port
	}
	return fallback
}

func virtualMAC(deviceID string) string {
	sum := []byte(deviceID)
	mac := []byte{0x02, 0x47, 0x48, 0, 0, 0}
	for i := range sum {
		mac[3+i%3] ^= sum[i]
	}
	return net.HardwareAddr(mac).String()
}
