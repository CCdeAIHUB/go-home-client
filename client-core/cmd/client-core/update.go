package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const currentClientVersion = "0.2.0"

type manifestComponent struct {
	Version string `json:"version"`
	URL     string `json:"url"`
	SHA256  string `json:"sha256"`
}

type updateView struct {
	Current    string `json:"current"`
	Latest     string `json:"latest"`
	Update     bool   `json:"update"`
	Configured bool   `json:"configured"`
	URL        string `json:"url,omitempty"`
	SHA256     string `json:"sha256,omitempty"`
}

func checkUpdate(ctx context.Context, current, manifestURL, component string) (updateView, error) {
	view := updateView{Current: current, Latest: current}
	if strings.TrimSpace(manifestURL) == "" {
		return view, nil
	}
	ctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, manifestURL, nil)
	if err != nil {
		return updateView{}, err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return updateView{}, fmt.Errorf("read update manifest: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return updateView{}, fmt.Errorf("update manifest returned HTTP %d", resp.StatusCode)
	}

	var manifest map[string]manifestComponent
	if err := json.NewDecoder(resp.Body).Decode(&manifest); err != nil {
		return updateView{}, fmt.Errorf("decode update manifest: %w", err)
	}
	entry, ok := manifest[component]
	if !ok || strings.TrimSpace(entry.Version) == "" {
		return updateView{}, fmt.Errorf("update manifest has no version for %s", component)
	}
	view.Latest = entry.Version
	view.Configured = true
	view.URL = entry.URL
	view.SHA256 = entry.SHA256
	view.Update = versionGreater(entry.Version, current)
	return view, nil
}

func versionGreater(left, right string) bool {
	lparts := versionParts(left)
	rparts := versionParts(right)
	length := len(lparts)
	if len(rparts) > length {
		length = len(rparts)
	}
	for i := 0; i < length; i++ {
		lpart, rpart := 0, 0
		if i < len(lparts) {
			lpart = lparts[i]
		}
		if i < len(rparts) {
			rpart = rparts[i]
		}
		if lpart != rpart {
			return lpart > rpart
		}
	}
	return false
}

func versionParts(value string) []int {
	value = strings.TrimPrefix(strings.TrimSpace(value), "v")
	parts := strings.Split(value, ".")
	out := make([]int, 0, len(parts))
	for _, part := range parts {
		digits := part
		for i, r := range part {
			if r < '0' || r > '9' {
				digits = part[:i]
				break
			}
		}
		number, _ := strconv.Atoi(digits)
		out = append(out, number)
	}
	return out
}
