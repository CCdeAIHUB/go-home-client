package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"gohome/shared/protocol"
	"gohome/shared/security"
	"gohome/shared/tunnel"
)

const (
	candidatePortPredictionWindow  = 16
	aggressivePortPredictionWindow = 512
	maxPunchCandidatesPerAttempt   = 192
)

type punchClient struct {
	conn     net.PacketConn
	deviceID string
	offer    protocol.HolePunchOffer
	key      []byte
	hello    []byte

	mu      sync.Mutex
	peer    net.Addr
	peers   []net.Addr // 候选端点列表，用于多路径打洞
	sendSeq uint64
	replay  tunnel.ReplayWindow
	ready   chan tunnel.Ready
	pong    chan []byte
	packets chan []byte

	punchStop chan struct{}
	punchOnce sync.Once

	statsMu   sync.Mutex
	up        uint64
	down      uint64
	pings     uint64
	pongs     uint64
	lastRTTMS int64
}

type punchStats struct {
	Up    uint64
	Down  uint64
	Loss  float64
	RTTMS int64
}

func newPunchClient(conn net.PacketConn, deviceID string, offer protocol.HolePunchOffer) (*punchClient, error) {
	key, err := tunnel.NewSessionKey()
	if err != nil {
		return nil, err
	}
	encryptedKey, err := security.EncryptForPublicKey(offer.Server.PublicKey, key)
	if err != nil {
		return nil, err
	}
	hello, err := tunnel.MarshalHello(tunnel.Hello{
		SessionID:           offer.SessionID,
		ClientDeviceID:      deviceID,
		EncryptedSessionKey: encryptedKey,
	})
	if err != nil {
		return nil, err
	}
	peers, err := resolvePeerEndpointList(peerBaseCandidateEndpoints(offer.Server))
	if err != nil {
		return nil, err
	}
	peer := peers[0]
	log.Printf("UDP punch base candidates for session %s: %v", offer.SessionID, peers)
	return &punchClient{
		conn:      conn,
		deviceID:  deviceID,
		offer:     offer,
		key:       key,
		hello:     hello,
		peer:      peer,
		peers:     peers,
		ready:     make(chan tunnel.Ready, 1),
		pong:      make(chan []byte, 1),
		packets:   make(chan []byte, 64),
		punchStop: make(chan struct{}),
	}, nil
}

func resolvePeerCandidates(peer protocol.PeerCandidate) ([]net.Addr, error) {
	return resolvePeerEndpointList(peerCandidateEndpoints(peer))
}

func resolvePeerEndpointList(endpoints []string) ([]net.Addr, error) {
	if len(endpoints) == 0 {
		return nil, errors.New("peer has no usable IPv4 UDP candidate")
	}
	var out []net.Addr
	var lastErr error
	seen := map[string]bool{}
	for _, endpoint := range endpoints {
		addr, err := net.ResolveUDPAddr("udp4", endpoint)
		if err != nil {
			lastErr = err
			continue
		}
		key := addr.String()
		if seen[key] {
			continue
		}
		seen[key] = true
		out = append(out, addr)
	}
	if len(out) == 0 {
		if lastErr != nil {
			return nil, lastErr
		}
		return nil, errors.New("peer candidates could not be resolved")
	}
	return out, nil
}

func punchAddrBatch(base []net.Addr, attempt int, maxBatch int) []net.Addr {
	window := punchPredictionWindow(attempt)
	candidates := expandAddrCandidates(base, window)
	if maxBatch <= 0 || len(candidates) <= maxBatch {
		return candidates
	}
	baseCount := len(base)
	if baseCount > maxBatch {
		baseCount = maxBatch
	}
	out := append([]net.Addr(nil), candidates[:baseCount]...)
	room := maxBatch - len(out)
	rotating := candidates[baseCount:]
	if room <= 0 || len(rotating) == 0 {
		return out
	}
	offset := (attempt * room) % len(rotating)
	for i := 0; i < room; i++ {
		out = append(out, rotating[(offset+i)%len(rotating)])
	}
	return out
}

func punchPredictionWindow(attempt int) int {
	switch {
	case attempt < 12:
		return candidatePortPredictionWindow
	case attempt < 32:
		return 64
	case attempt < 60:
		return 256
	default:
		return aggressivePortPredictionWindow
	}
}

func expandAddrCandidates(base []net.Addr, window int) []net.Addr {
	var out []net.Addr
	seen := map[string]bool{}
	add := func(addr net.Addr) {
		udpAddr, ok := addr.(*net.UDPAddr)
		if !ok || udpAddr.IP == nil || udpAddr.IP.To4() == nil || udpAddr.Port < 1 || udpAddr.Port > 65535 {
			return
		}
		normalized := &net.UDPAddr{IP: udpAddr.IP.To4(), Port: udpAddr.Port}
		key := normalized.String()
		if seen[key] {
			return
		}
		seen[key] = true
		out = append(out, normalized)
	}
	for _, addr := range base {
		add(addr)
	}
	for _, addr := range append([]net.Addr(nil), out...) {
		udpAddr := addr.(*net.UDPAddr)
		for delta := 1; delta <= window; delta++ {
			if udpAddr.Port+delta <= 65535 {
				add(&net.UDPAddr{IP: udpAddr.IP, Port: udpAddr.Port + delta})
			}
			if udpAddr.Port-delta >= 1 {
				add(&net.UDPAddr{IP: udpAddr.IP, Port: udpAddr.Port - delta})
			}
		}
	}
	return out
}

func peerCandidateEndpoints(peer protocol.PeerCandidate) []string {
	return peerCandidateEndpointsWithWindow(peer, candidatePortPredictionWindow)
}

func peerCandidateEndpointsWithWindow(peer protocol.PeerCandidate, window int) []string {
	out := peerBaseCandidateEndpoints(peer)
	if window <= 0 {
		return out
	}
	seen := map[string]bool{}
	for _, endpoint := range out {
		seen[endpoint] = true
	}
	add := func(endpoint string) {
		normalized, ok := normalizeIPv4Endpoint(endpoint)
		if !ok || seen[normalized] {
			return
		}
		seen[normalized] = true
		out = append(out, normalized)
	}
	base := append([]string(nil), out...)
	for _, endpoint := range base {
		addPortPredictionWindow(add, endpoint, window)
	}
	return out
}

func peerBaseCandidateEndpoints(peer protocol.PeerCandidate) []string {
	var out []string
	seen := map[string]bool{}
	add := func(endpoint string) {
		normalized, ok := normalizeIPv4Endpoint(endpoint)
		if !ok || seen[normalized] {
			return
		}
		seen[normalized] = true
		out = append(out, normalized)
	}
	for _, endpoint := range peer.Candidates {
		add(endpoint)
	}
	add(peer.ObservedEndpoint)
	add(peer.Endpoint)
	if peer.UDPPort > 0 {
		for _, endpoint := range []string{peer.ObservedEndpoint, peer.Endpoint, peer.RemoteAddr} {
			if host, ok := endpointHost(endpoint); ok {
				add(net.JoinHostPort(host, strconv.Itoa(peer.UDPPort)))
			}
		}
	}
	return out
}

func addPortPredictionWindow(add func(string), endpoint string, window int) {
	host, port, ok := endpointParts(endpoint)
	if !ok {
		return
	}
	for delta := 1; delta <= window; delta++ {
		if port+delta <= 65535 {
			add(net.JoinHostPort(host, strconv.Itoa(port+delta)))
		}
		if port-delta >= 1 {
			add(net.JoinHostPort(host, strconv.Itoa(port-delta)))
		}
	}
}

func endpointHost(endpoint string) (string, bool) {
	host, _, ok := endpointParts(endpoint)
	return host, ok
}

func endpointParts(endpoint string) (string, int, bool) {
	endpoint = strings.TrimSpace(endpoint)
	if endpoint == "" {
		return "", 0, false
	}
	host, portText, err := net.SplitHostPort(endpoint)
	if err != nil {
		return "", 0, false
	}
	ip := net.ParseIP(strings.Trim(host, "[]"))
	if ip == nil || ip.To4() == nil {
		return "", 0, false
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 {
		return "", 0, false
	}
	return ip.To4().String(), port, true
}

func normalizeIPv4Endpoint(endpoint string) (string, bool) {
	host, port, ok := endpointParts(endpoint)
	if !ok {
		return "", false
	}
	return net.JoinHostPort(host, strconv.Itoa(port)), true
}

func (c *punchClient) Connect(waitCtx, runCtx context.Context) (tunnel.Ready, error) {
	errs := make(chan error, 1)
	go c.readLoop(runCtx, errs)
	go c.punchLoop(runCtx)

	select {
	case <-waitCtx.Done():
		return tunnel.Ready{}, waitCtx.Err()
	case err := <-errs:
		return tunnel.Ready{}, err
	case ready := <-c.ready:
		return ready, nil
	}
}

func (c *punchClient) VerifyPing(ctx context.Context) error {
	payload := []byte(fmt.Sprintf("ping-%d", time.Now().UnixNano()))
	started := time.Now()
	c.statsMu.Lock()
	c.pings++
	c.statsMu.Unlock()
	if err := c.sendFrame(tunnel.FramePing, payload); err != nil {
		return err
	}
	select {
	case <-ctx.Done():
		return ctx.Err()
	case pong := <-c.pong:
		if string(pong) != string(payload) {
			return fmt.Errorf("pong payload mismatch: %q", pong)
		}
		c.statsMu.Lock()
		c.pongs++
		c.lastRTTMS = time.Since(started).Milliseconds()
		c.statsMu.Unlock()
		return nil
	}
}

func (c *punchClient) KeepAlive(ctx context.Context) {
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := c.sendFrame(tunnel.FrameKeepalive, []byte("keepalive")); err != nil {
				log.Printf("keepalive: %v", err)
			}
		}
	}
}

func (c *punchClient) punchLoop(ctx context.Context) {
	lastWindow := -1
	for attempt := 0; ; attempt++ {
		// 向所有候选端点发送 Hello
		c.mu.Lock()
		basePeers := append([]net.Addr(nil), c.peers...)
		c.mu.Unlock()
		peers := punchAddrBatch(basePeers, attempt, maxPunchCandidatesPerAttempt)
		window := punchPredictionWindow(attempt)
		if window != lastWindow {
			log.Printf("UDP punch stage for session %s: attempt=%d window=+/-%d total_candidates=%d batch=%d", c.offer.SessionID, attempt, window, len(expandAddrCandidates(basePeers, window)), len(peers))
			lastWindow = window
		}
		for _, p := range peers {
			if _, err := c.conn.WriteTo(c.hello, p); err != nil {
				log.Printf("send UDP hello to %s: %v", p, err)
			} else {
				c.statsMu.Lock()
				c.up += uint64(len(c.hello))
				c.statsMu.Unlock()
			}
		}
		wait := punchInterval(attempt)
		select {
		case <-ctx.Done():
			return
		case <-c.punchStop:
			return
		case <-time.After(wait):
		}
	}
}

func punchInterval(attempt int) time.Duration {
	switch {
	case attempt < 24:
		return 35 * time.Millisecond
	case attempt < 64:
		return 100 * time.Millisecond
	case attempt < 100:
		return 250 * time.Millisecond
	default:
		return 500 * time.Millisecond
	}
}

func (c *punchClient) readLoop(ctx context.Context, errs chan<- error) {
	buf := make([]byte, 64*1024)
	for {
		if deadline, ok := ctx.Deadline(); ok {
			_ = c.conn.SetReadDeadline(deadline)
		}
		n, addr, err := c.conn.ReadFrom(buf)
		if err != nil {
			select {
			case <-ctx.Done():
				return
			default:
				errs <- err
				return
			}
		}
		packet := append([]byte(nil), buf[:n]...)
		c.statsMu.Lock()
		c.down += uint64(n)
		c.statsMu.Unlock()
		if err := c.handlePacket(packet, addr); err != nil {
			log.Printf("udp packet rejected from %s: %v", addr.String(), err)
		}
	}
}

func (c *punchClient) handlePacket(packet []byte, addr net.Addr) error {
	kind, err := tunnel.PacketKind(packet)
	if err != nil {
		return err
	}
	switch kind {
	case tunnel.PacketProbe:
		var probe tunnel.Probe
		if err := tunnel.UnmarshalControl(packet, &probe); err != nil {
			return err
		}
		if probe.SessionID != c.offer.SessionID {
			return fmt.Errorf("probe belongs to session %s", probe.SessionID)
		}
		c.setPeer(addr)
		return c.sendHello()
	case tunnel.PacketFrame:
		frame, err := tunnel.Open(c.key, packet)
		if err != nil {
			return err
		}
		if frame.SessionID != c.offer.SessionID {
			return fmt.Errorf("frame belongs to session %s", frame.SessionID)
		}
		if !c.replay.Accept(frame.Sequence) {
			return fmt.Errorf("secure frame sequence %d was already seen", frame.Sequence)
		}
		c.setPeer(addr)
		switch frame.Type {
		case tunnel.FrameReady:
			var ready tunnel.Ready
			if err := json.Unmarshal(frame.Payload, &ready); err != nil {
				return err
			}
			c.stopPunching()
			select {
			case c.ready <- ready:
			default:
			}
			return nil
		case tunnel.FramePong:
			select {
			case c.pong <- frame.Payload:
			default:
			}
			return nil
		case tunnel.FrameIPv4:
			select {
			case c.packets <- append([]byte(nil), frame.Payload...):
			default:
			}
			return nil
		default:
			return fmt.Errorf("unsupported secure frame type %d", frame.Type)
		}
	default:
		return fmt.Errorf("unsupported UDP packet kind %d", kind)
	}
}

func (c *punchClient) sendHello() error {
	c.mu.Lock()
	peer := c.peer
	c.mu.Unlock()
	if peer == nil {
		return errors.New("home peer is unavailable")
	}
	_, err := c.conn.WriteTo(c.hello, peer)
	if err == nil {
		c.statsMu.Lock()
		c.up += uint64(len(c.hello))
		c.statsMu.Unlock()
	}
	return err
}

func (c *punchClient) sendFrame(frameType byte, payload []byte) error {
	c.mu.Lock()
	c.sendSeq++
	sequence := c.sendSeq
	peer := c.peer
	c.mu.Unlock()
	if peer == nil {
		return errors.New("home peer is unavailable")
	}
	packet, err := tunnel.Seal(c.key, c.offer.SessionID, sequence, frameType, payload)
	if err != nil {
		return err
	}
	_, err = c.conn.WriteTo(packet, peer)
	if err == nil {
		c.statsMu.Lock()
		c.up += uint64(len(packet))
		c.statsMu.Unlock()
	}
	return err
}

func (c *punchClient) setPeer(peer net.Addr) {
	c.mu.Lock()
	key := peer.String()
	for _, existing := range c.peers {
		if existing.String() == key {
			c.peer = peer
			c.mu.Unlock()
			return
		}
	}
	c.peers = append(c.peers, peer)
	c.peer = peer
	c.mu.Unlock()
}

func (c *punchClient) stopPunching() {
	c.punchOnce.Do(func() {
		close(c.punchStop)
	})
}

func (c *punchClient) Stats() punchStats {
	c.statsMu.Lock()
	defer c.statsMu.Unlock()
	loss := 0.0
	if c.pings > 0 && c.pongs < c.pings {
		loss = float64(c.pings-c.pongs) / float64(c.pings) * 100
	}
	return punchStats{
		Up:    c.up,
		Down:  c.down,
		Loss:  loss,
		RTTMS: c.lastRTTMS,
	}
}

func (c *punchClient) SendIPv4(packet []byte) error {
	return c.sendFrame(tunnel.FrameIPv4, packet)
}

func (c *punchClient) Packets() <-chan []byte {
	return c.packets
}
