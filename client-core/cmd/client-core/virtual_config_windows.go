//go:build windows

package main

import (
	"fmt"
	"net/netip"
	"os/exec"
)

func configureVirtualLink(name, address, route string, mtu int) error {
	mask, err := routeMask(route)
	if err != nil {
		return err
	}
	if err := exec.Command("netsh", "interface", "ipv4", "set", "address", "name="+name, "static", address, mask).Run(); err != nil {
		return fmt.Errorf("configure Wintun address: %w", err)
	}
	if err := exec.Command("netsh", "interface", "ipv4", "set", "subinterface", name, fmt.Sprintf("mtu=%d", mtu), "store=active").Run(); err != nil {
		return fmt.Errorf("configure Wintun MTU: %w", err)
	}
	prefix, err := netip.ParsePrefix(route)
	if err != nil {
		return fmt.Errorf("invalid tunnel route: %w", err)
	}
	if err := exec.Command("route", "ADD", prefix.Masked().Addr().String(), "MASK", mask, address, "METRIC", "5").Run(); err != nil {
		return fmt.Errorf("install Wintun route: %w", err)
	}
	return nil
}

func routeMask(route string) (string, error) {
	prefix, err := netip.ParsePrefix(route)
	if err != nil || !prefix.Addr().Is4() {
		return "", fmt.Errorf("invalid IPv4 route %q", route)
	}
	mask := [4]byte{}
	for bit := 0; bit < prefix.Bits(); bit++ {
		mask[bit/8] |= 1 << uint(7-bit%8)
	}
	return netip.AddrFrom4(mask).String(), nil
}
