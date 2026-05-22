//go:build linux

package main

import (
	"fmt"
	"os/exec"
)

func configureVirtualLink(name, address, route string, mtu int) error {
	if err := exec.Command("ip", "link", "set", "dev", name, "mtu", fmt.Sprintf("%d", mtu), "up").Run(); err != nil {
		return fmt.Errorf("bring TUN link up: %w", err)
	}
	if err := exec.Command("ip", "addr", "replace", address+"/32", "dev", name).Run(); err != nil {
		return fmt.Errorf("configure TUN address: %w", err)
	}
	if err := exec.Command("ip", "route", "replace", route, "dev", name).Run(); err != nil {
		return fmt.Errorf("install TUN route: %w", err)
	}
	return nil
}
