package main

import (
	"fmt"
	"net"
)

type networkConflict struct {
	Conflict bool     `json:"conflict"`
	LANCIDR  string   `json:"lan_cidr"`
	Overlaps []string `json:"overlaps,omitempty"`
}

func CheckNetworkConflict(cidr string) (networkConflict, error) {
	result := networkConflict{LANCIDR: cidr}
	if cidr == "" {
		return result, nil
	}
	_, home, err := net.ParseCIDR(cidr)
	if err != nil {
		return result, fmt.Errorf("invalid family LAN CIDR: %w", err)
	}
	ifaces, err := net.Interfaces()
	if err != nil {
		return result, err
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			_, local, ok := addrToIPNet(addr)
			if !ok || !overlap(home, local) {
				continue
			}
			result.Conflict = true
			result.Overlaps = append(result.Overlaps, iface.Name+" "+local.String())
		}
	}
	return result, nil
}

func addrToIPNet(addr net.Addr) (net.IP, *net.IPNet, bool) {
	network, ok := addr.(*net.IPNet)
	if !ok {
		return nil, nil, false
	}
	ip := network.IP.To4()
	if ip == nil {
		return nil, nil, false
	}
	return ip, &net.IPNet{IP: ip.Mask(network.Mask), Mask: network.Mask}, true
}

func overlap(a, b *net.IPNet) bool {
	return a.Contains(b.IP) || b.Contains(a.IP)
}
