package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"sync"
	"time"

	"gohome/shared/protocol"
	"gohome/shared/security"
	"gohome/shared/tunnel"
)

type punchClient struct {
	conn     net.PacketConn
	deviceID string
	offer    protocol.HolePunchOffer
	key      []byte
	hello    []byte

	mu      sync.Mutex
	peer    net.Addr
	sendSeq uint64
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
	peer, err := net.ResolveUDPAddr("udp", offer.Server.Endpoint)
	if err != nil {
		return nil, err
	}
	return &punchClient{
		conn:      conn,
		deviceID:  deviceID,
		offer:     offer,
		key:       key,
		hello:     hello,
		peer:      peer,
		ready:     make(chan tunnel.Ready, 1),
		pong:      make(chan []byte, 1),
		packets:   make(chan []byte, 64),
		punchStop: make(chan struct{}),
	}, nil
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
	for attempt := 0; ; attempt++ {
		if err := c.sendHello(); err != nil {
			log.Printf("send UDP hello: %v", err)
		}
		wait := time.Duration(attempt+1) * 120 * time.Millisecond
		if wait > time.Second {
			wait = time.Second
		}
		select {
		case <-ctx.Done():
			return
		case <-c.punchStop:
			return
		case <-time.After(wait):
		}
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
