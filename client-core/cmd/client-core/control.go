package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func runControl(addr, uiDir string, udpPort int, identityFile, updateManifest, updateComponent string) error {
	manager, err := newClientManager(identityFile, udpPort)
	if err != nil {
		return err
	}
	defer manager.Close()

	mux := http.NewServeMux()
	mux.HandleFunc("/api/connect", method(http.MethodPost, func(w http.ResponseWriter, r *http.Request) error {
		var params struct {
			Server   string `json:"server"`
			AuthCode string `json:"auth_code"`
		}
		if err := readJSON(r, &params); err != nil {
			return err
		}
		ctx, cancel := context.WithTimeout(r.Context(), 12*time.Second)
		defer cancel()
		if err := manager.ConnectServer(ctx, params.Server, params.AuthCode); err != nil {
			return err
		}
		writeJSON(w, map[string]bool{"ok": true})
		return nil
	}))
	mux.HandleFunc("/api/families", method(http.MethodGet, func(w http.ResponseWriter, r *http.Request) error {
		ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
		defer cancel()
		families, err := manager.ListFamilies(ctx)
		if err != nil {
			return err
		}
		writeJSON(w, families)
		return nil
	}))
	mux.HandleFunc("/api/conflict", method(http.MethodPost, func(w http.ResponseWriter, r *http.Request) error {
		var params struct {
			LANCIDR string `json:"lan_cidr"`
		}
		if err := readJSON(r, &params); err != nil {
			return err
		}
		conflict, err := CheckNetworkConflict(params.LANCIDR)
		if err != nil {
			return err
		}
		writeJSON(w, conflict)
		return nil
	}))
	mux.HandleFunc("/api/tunnel/connect", method(http.MethodPost, func(w http.ResponseWriter, r *http.Request) error {
		var params struct {
			FamilyID int64 `json:"family_id"`
			tunnelOptions
		}
		if err := readJSON(r, &params); err != nil {
			return err
		}
		ctx, cancel := context.WithTimeout(r.Context(), 25*time.Second)
		defer cancel()
		view, err := manager.ConnectFamily(ctx, params.FamilyID, params.tunnelOptions)
		if err != nil {
			return err
		}
		writeJSON(w, view)
		return nil
	}))
	mux.HandleFunc("/api/tunnel", method(http.MethodGet, func(w http.ResponseWriter, _ *http.Request) error {
		if view, ok := manager.ActiveTunnel(); ok {
			writeJSON(w, view)
			return nil
		}
		return errors.New("tunnel is not connected")
	}))
	mux.HandleFunc("/api/status", method(http.MethodGet, func(w http.ResponseWriter, _ *http.Request) error {
		writeJSON(w, manager.TunnelStatus())
		return nil
	}))
	mux.HandleFunc("/api/stats", method(http.MethodGet, func(w http.ResponseWriter, r *http.Request) error {
		ctx, cancel := context.WithTimeout(r.Context(), 4*time.Second)
		defer cancel()
		writeJSON(w, manager.Traffic(ctx))
		return nil
	}))
	mux.HandleFunc("/api/disconnect", method(http.MethodPost, func(w http.ResponseWriter, _ *http.Request) error {
		manager.Disconnect()
		writeJSON(w, map[string]bool{"ok": true})
		return nil
	}))
	mux.HandleFunc("/api/update", method(http.MethodGet, func(w http.ResponseWriter, r *http.Request) error {
		update, err := checkUpdate(r.Context(), currentClientVersion, updateManifest, updateComponent)
		if err != nil {
			return err
		}
		writeJSON(w, update)
		return nil
	}))

	if uiDir != "" {
		mux.Handle("/", spaFiles(uiDir))
	} else {
		mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) {
			_, _ = w.Write([]byte("Go Home client control service is running.\n"))
		})
	}

	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	log.Printf("Go Home client UI control listening on http://%s", listener.Addr().String())
	return http.Serve(listener, mux)
}

type handlerFunc func(http.ResponseWriter, *http.Request) error

func method(name string, next handlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != name {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		if err := next(w, r); err != nil {
			writeError(w, err)
		}
	}
}

func readJSON(r *http.Request, value any) error {
	defer r.Body.Close()
	return json.NewDecoder(r.Body).Decode(value)
}

func writeJSON(w http.ResponseWriter, value any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, err error) {
	status := http.StatusBadRequest
	if strings.Contains(err.Error(), "not connected") {
		status = http.StatusConflict
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
}

func spaFiles(root string) http.Handler {
	fs := http.FileServer(http.Dir(root))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/") {
			http.NotFound(w, r)
			return
		}
		candidate := filepath.Join(root, filepath.Clean(r.URL.Path))
		if info, err := os.Stat(candidate); err == nil && !info.IsDir() {
			fs.ServeHTTP(w, r)
			return
		}
		http.ServeFile(w, r, filepath.Join(root, "index.html"))
	})
}

func controlURL(addr string) string {
	if strings.HasPrefix(addr, "http://") || strings.HasPrefix(addr, "https://") {
		return addr
	}
	return fmt.Sprintf("http://%s", addr)
}
