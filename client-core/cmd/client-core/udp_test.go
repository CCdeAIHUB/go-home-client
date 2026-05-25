package main

import (
	"reflect"
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
	want := []string{
		"203.0.113.4:50001",
		"203.0.113.4:50002",
		"198.51.100.9:47777",
		"203.0.113.4:47777",
		"198.51.100.10:47777",
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("peerCandidateEndpoints got %#v want %#v", got, want)
	}
}
