//go:build !windows && !linux && !darwin

package main

import "fmt"

func configureVirtualLink(_, _, _ string, _ int) error {
	return fmt.Errorf("virtual adapter configuration is not implemented for this platform")
}
