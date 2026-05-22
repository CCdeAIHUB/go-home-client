//go:build darwin

package main

import (
	"fmt"
	"os/exec"
)

func configureVirtualLink(name, address, route string, mtu int) error {
	if err := exec.Command("ifconfig", name, "inet", address, address, "mtu", fmt.Sprintf("%d", mtu), "up").Run(); err != nil {
		return fmt.Errorf("configure Darwin TUN address: %w", err)
	}
	if err := exec.Command("route", "-n", "add", "-net", route, "-interface", name).Run(); err != nil {
		return fmt.Errorf("install Darwin TUN route: %w", err)
	}
	return nil
}
