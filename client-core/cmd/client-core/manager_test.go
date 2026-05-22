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
