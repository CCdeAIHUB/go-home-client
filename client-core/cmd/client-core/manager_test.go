package main

import (
	"testing"
	"time"
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
