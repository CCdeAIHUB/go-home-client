package main

import (
	"context"
	"fmt"
	"net/netip"
	"sync"

	"golang.zx2c4.com/wireguard/tun"
)

const tunnelMTU = 1380

type virtualLink struct {
	device tun.Device
	cancel context.CancelFunc
	once   sync.Once
}

func newVirtualLink(parent context.Context, client *punchClient, mode, homeIP, lanCIDR, virtualCIDR string) (*virtualLink, error) {
	address, route, err := clientLinkAddress(mode, homeIP, lanCIDR, virtualCIDR)
	if err != nil {
		return nil, err
	}
	device, err := tun.CreateTUN("GoHome", tunnelMTU)
	if err != nil {
		return nil, fmt.Errorf("create virtual adapter: %w", err)
	}
	name, err := device.Name()
	if err != nil {
		_ = device.Close()
		return nil, fmt.Errorf("read virtual adapter name: %w", err)
	}
	if err := configureVirtualLink(name, address, route, tunnelMTU); err != nil {
		_ = device.Close()
		return nil, err
	}

	ctx, cancel := context.WithCancel(parent)
	link := &virtualLink{device: device, cancel: cancel}
	go link.readLoop(ctx, client)
	go link.writeLoop(ctx, client)
	return link, nil
}

func (l *virtualLink) Close() error {
	var err error
	l.once.Do(func() {
		l.cancel()
		err = l.device.Close()
	})
	return err
}

func (l *virtualLink) readLoop(ctx context.Context, client *punchClient) {
	batch := l.device.BatchSize()
	bufs := make([][]byte, batch)
	sizes := make([]int, batch)
	for i := range bufs {
		bufs[i] = make([]byte, 64*1024)
	}
	for {
		n, err := l.device.Read(bufs, sizes, 0)
		if err != nil {
			return
		}
		for i := 0; i < n; i++ {
			if sizes[i] <= 0 {
				continue
			}
			select {
			case <-ctx.Done():
				return
			default:
				_ = client.SendIPv4(bufs[i][:sizes[i]])
			}
		}
	}
}

func (l *virtualLink) writeLoop(ctx context.Context, client *punchClient) {
	for {
		select {
		case <-ctx.Done():
			return
		case packet := <-client.Packets():
			if len(packet) == 0 {
				continue
			}
			_, _ = l.device.Write([][]byte{packet}, 0)
		}
	}
}

func clientLinkAddress(mode, homeIP, lanCIDR, virtualCIDR string) (string, string, error) {
	ip, err := netip.ParseAddr(homeIP)
	if err != nil || !ip.Is4() {
		return "", "", fmt.Errorf("invalid home-side client IP %q", homeIP)
	}
	if mode == "mapped" {
		virtual, err := mappedIPv4(ip, lanCIDR, virtualCIDR)
		if err != nil {
			return "", "", err
		}
		return virtual.String(), virtualCIDR, nil
	}
	return ip.String(), lanCIDR, nil
}

func mappedIPv4(ip netip.Addr, realCIDR, virtualCIDR string) (netip.Addr, error) {
	real, err := netip.ParsePrefix(realCIDR)
	if err != nil || !real.Contains(ip) || real.Bits() != 24 {
		return netip.Addr{}, fmt.Errorf("mapped real CIDR must be an IPv4 /24")
	}
	virtual, err := netip.ParsePrefix(virtualCIDR)
	if err != nil || !virtual.Addr().Is4() || virtual.Bits() != 24 {
		return netip.Addr{}, fmt.Errorf("mapped virtual CIDR must be an IPv4 /24")
	}
	bytes := virtual.Masked().Addr().As4()
	bytes[3] = ip.As4()[3]
	return netip.AddrFrom4(bytes), nil
}
