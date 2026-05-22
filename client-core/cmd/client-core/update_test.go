package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestVersionGreater(t *testing.T) {
	if !versionGreater("0.2.1", "0.2.0") {
		t.Fatal("patch upgrade not detected")
	}
	if !versionGreater("v1.0.0", "0.9.9") {
		t.Fatal("major upgrade not detected")
	}
	if versionGreater("0.2.0", "0.2.0") || versionGreater("0.1.9", "0.2.0") {
		t.Fatal("non-upgrade detected")
	}
}

func TestCheckUpdateReadsManifestComponent(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"client-pc":{"version":"0.3.0","url":"https://cdn.example/client.zip","sha256":"abc"}}`))
	}))
	defer server.Close()

	view, err := checkUpdate(context.Background(), "0.2.0", server.URL, "client-pc")
	if err != nil {
		t.Fatalf("check update: %v", err)
	}
	if !view.Configured || !view.Update || view.Latest != "0.3.0" || view.SHA256 != "abc" {
		t.Fatalf("unexpected update view: %+v", view)
	}
}
