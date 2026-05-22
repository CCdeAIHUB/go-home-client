package main

import (
	"testing"
	"time"

	"gohome/shared/protocol"
)

func TestGraceSecondsRoundsUp(t *testing.T) {
	now := time.Unix(100, 0)
	if got := graceSeconds(now.Add(29001*time.Millisecond), now); got != 30 {
		t.Fatalf("grace seconds got %d want 30", got)
	}
	if got := graceSeconds(now.Add(-time.Millisecond), now); got != 0 {
		t.Fatalf("expired grace seconds got %d want 0", got)
	}
}

func TestCounterDeltaHandlesReset(t *testing.T) {
	if got := counterDelta(40, 10); got != 30 {
		t.Fatalf("counter delta got %d want 30", got)
	}
	if got := counterDelta(4, 40); got != 4 {
		t.Fatalf("reset counter delta got %d want 4", got)
	}
}

func TestFamilyCIDRUsesCachedFamilies(t *testing.T) {
	manager := &clientManager{families: []protocol.Family{{ID: 7, LANCIDR: "192.168.3.0/24"}}}
	if got := manager.familyCIDR(7); got != "192.168.3.0/24" {
		t.Fatalf("family CIDR got %q", got)
	}
}

func TestApplyLANChangeRefreshesCachedFamily(t *testing.T) {
	manager := &clientManager{families: []protocol.Family{{ID: 7, LANCIDR: "192.168.3.0/24"}}}
	manager.applyLANChange(7, "192.168.8.0/24")
	if got := manager.familyCIDR(7); got != "192.168.8.0/24" {
		t.Fatalf("changed family CIDR got %q", got)
	}
}
