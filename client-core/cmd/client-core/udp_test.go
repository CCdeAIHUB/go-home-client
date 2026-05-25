package main

import (
	"testing"

	"gohome/shared/protocol"
)

func TestPeerCandidateEndpointsUsesServerListFirst(t *testing.T) {
	peer := protocol.PeerCandidate{
		Candidates:       []string{"203.0.113.4:50001", "203.0.113.4:50001", "[2001:db8::1]:50001"},
		ObservedEndpoint: "203.0.113.4:50002",
		Endpoint:         "198.51.100.9:47777",
		RemoteAddr:       "198.51.100.10:44321",
		UDPPort:          47777,
	}
	got := peerCandidateEndpoints(peer)
	wantPrefix := []string{
		"203.0.113.4:50001",
		"203.0.113.4:50002",
		"198.51.100.9:47777",
		"203.0.113.4:47777",
		"198.51.100.10:47777",
	}
	if len(got) < len(wantPrefix) {
		t.Fatalf("peerCandidateEndpoints got too few endpoints: %#v", got)
	}
	for i, want := range wantPrefix {
		if got[i] != want {
			t.Fatalf("peerCandidateEndpoints[%d] got %q want %q; all=%#v", i, got[i], want, got)
		}
	}
	if !containsEndpoint(got, "203.0.113.4:50003") || !containsEndpoint(got, "203.0.113.4:50000") {
		t.Fatalf("peerCandidateEndpoints did not include predicted adjacent ports: %#v", got)
	}
}

func containsEndpoint(items []string, want string) bool {
	for _, item := range items {
		if item == want {
			return true
		}
	}
	return false
}
