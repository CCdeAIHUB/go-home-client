package main

import (
	"net/netip"
	"testing"
)

func TestClientLinkAddressMapped(t *testing.T) {
	address, route, err := clientLinkAddress("mapped", "lan", "192.168.3.200", "192.168.3.0/24", "192.168.6.0/24")
	if err != nil {
		t.Fatalf("client link address: %v", err)
	}
	if address != "192.168.6.200" || route != "192.168.6.0/24" {
		t.Fatalf("unexpected mapped address %s route %s", address, route)
	}
}

func TestClientLinkAddressFullRoute(t *testing.T) {
	address, route, err := clientLinkAddress("mapped", "full", "192.168.3.200", "192.168.3.0/24", "192.168.6.0/24")
	if err != nil {
		t.Fatalf("client link address: %v", err)
	}
	if address != "192.168.6.200" || route != "0.0.0.0/0" {
		t.Fatalf("unexpected full-route address %s route %s", address, route)
	}
}

func TestMappedIPv4RejectsMismatchedPrefix(t *testing.T) {
	ip := netip.MustParseAddr("192.168.4.10")
	if _, err := mappedIPv4(ip, "192.168.3.0/24", "192.168.6.0/24"); err == nil {
		t.Fatal("expected mismatched real prefix to fail")
	}
}
