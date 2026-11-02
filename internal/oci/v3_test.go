// SPDX-License-Identifier: MIT

package oci

import (
	"encoding/json"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/lmctl-ai/lmcloud/hostkit"
	"github.com/lmctl-ai/lmcloud/hostkit/cloudhost"
	"github.com/lmctl-ai/lmcloud/hostkit/httpguard"
)

// tokenServer models OCI create semantics with freeformTags: it remembers the
// create token written in the CreateBucket/CreateQueue body and returns it on
// GET. failFirst makes the first create commit server-side but answer 500
// (a lost reply).
type tokenServer struct {
	mu        sync.Mutex
	failFirst bool
	creates   int
	bucket    map[string]string // name -> token ("" = pre-existing, no token)
	queue     map[string]string
}

func (s *tokenServer) handle(w http.ResponseWriter, r *http.Request) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	var body struct {
		Name         string            `json:"name"`
		DisplayName  string            `json:"displayName"`
		FreeformTags map[string]string `json:"freeformTags"`
	}
	switch {
	case r.Method == http.MethodPost && r.URL.Path == "/n/floci-local/b/":
		_ = json.NewDecoder(r.Body).Decode(&body)
		s.creates++
		if _, exists := s.bucket[body.Name]; exists {
			writeJSON(w, 409, map[string]string{"code": "BucketAlreadyExists", "message": "exists"})
			return true
		}
		s.bucket[body.Name] = body.FreeformTags[createTokenTag]
		if s.failFirst && s.creates == 1 {
			writeJSON(w, 500, map[string]string{"code": "InternalError", "message": "reply lost"})
			return true
		}
		writeJSON(w, 200, map[string]string{"name": body.Name})
		return true
	case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/n/floci-local/b/") && r.URL.Path != "/n/floci-local/b/":
		name := strings.TrimPrefix(r.URL.Path, "/n/floci-local/b/")
		tok, ok := s.bucket[name]
		if !ok {
			writeJSON(w, 404, map[string]string{"code": "BucketNotFound", "message": "no"})
			return true
		}
		writeJSON(w, 200, map[string]any{"name": name, "freeformTags": map[string]string{createTokenTag: tok}})
		return true
	case r.Method == http.MethodGet && r.URL.Path == "/20210201/queues":
		items := []any{}
		for n := range s.queue {
			if n == r.URL.Query().Get("displayName") || r.URL.Query().Get("displayName") == "" {
				items = append(items, queueItem("ocid1.queue."+n, n, "ACTIVE"))
			}
		}
		writeJSON(w, 200, map[string]any{"items": items})
		return true
	case r.Method == http.MethodPost && r.URL.Path == "/20210201/queues":
		_ = json.NewDecoder(r.Body).Decode(&body)
		s.creates++
		s.queue[body.DisplayName] = body.FreeformTags[createTokenTag]
		if s.failFirst && s.creates == 1 {
			writeJSON(w, 500, map[string]string{"code": "InternalError", "message": "reply lost"})
			return true
		}
		w.Header().Set("opc-work-request-id", "wr-ok")
		w.WriteHeader(202)
		return true
	case r.URL.Path == "/20210201/workRequests/wr-ok":
		writeJSON(w, 200, map[string]string{"status": "SUCCEEDED"})
		return true
	case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/20210201/queues/ocid1.queue."):
		n := strings.TrimPrefix(r.URL.Path, "/20210201/queues/ocid1.queue.")
		writeJSON(w, 200, map[string]any{"id": "ocid1.queue." + n, "freeformTags": map[string]string{createTokenTag: s.queue[n]}})
		return true
	}
	return false
}

// hostkit v3 create ownership on OCI: freeformTags carry the per-call token.
func TestCreateTokenOwnership(t *testing.T) {
	cases := []struct {
		name       string
		failFirst  bool
		preexist   bool
		wantBucket bool
		wantQueue  bool
	}{
		{"plain create", false, false, true, true},
		{"pre-existing resource without our token", false, true, false, false},
		{"lost reply: server created it with our token", true, false, true, true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			s := &tokenServer{failFirst: c.failFirst, bucket: map[string]string{}, queue: map[string]string{}}
			if c.preexist {
				s.bucket["tok-bkt"] = ""
				s.queue["tok-q"] = ""
			}
			h, m := newHost(t, s.handle)
			got, _ := h.BucketCreate(false, "tok-bkt")
			if got != c.wantBucket || h.LastError().Code != hostkit.OK {
				t.Fatalf("bucket_create = %v last=%+v requests=%v", got, h.LastError(), m.requests)
			}
			if c.failFirst && m.count("POST /n/floci-local/b/") != 2 {
				t.Fatalf("token-carrying bucket POST must be retried once after the lost reply: %v", m.requests)
			}
			got, _ = h.QueueCreate(false, "tok-q")
			if got != c.wantQueue || h.LastError().Code != hostkit.OK {
				t.Fatalf("queue_create = %v last=%+v requests=%v", got, h.LastError(), m.requests)
			}
			if m.count("POST /20210201/queues") > 1 {
				t.Fatal("queue POST must never be retried (OCI allows duplicate names)")
			}
		})
	}
}

// hostkit v3 per-route caps: the offline fake short-polls (the host waits),
// the ocisim route long-polls (one GET carrying timeoutInSeconds).
func TestQueueCapsPerRoute(t *testing.T) {
	var receives []string
	var mu sync.Mutex
	h, _ := newHost(t, func(w http.ResponseWriter, r *http.Request) bool {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/20210201/queues":
			writeJSON(w, 200, map[string]any{"items": []any{queueItem("ocid1.queue.q", "q-one", "ACTIVE")}})
		case r.Method == http.MethodGet && r.URL.Path == "/20210201/queues/ocid1.queue.q/messages":
			mu.Lock()
			receives = append(receives, r.URL.Query().Get("timeoutInSeconds"))
			mu.Unlock()
			writeJSON(w, 200, map[string]any{"messages": []any{}})
		default:
			return false
		}
		return true
	})
	if msgs, err := h.QueueReceive(false, "q-one", 1, 1, 30); err != nil || len(msgs) != 0 {
		t.Fatalf("sim receive: %v %v", msgs, err)
	}
	if len(receives) != 1 || receives[0] != "1" {
		t.Fatalf("sim route must long-poll once with timeoutInSeconds=1, got %v", receives)
	}
	t.Setenv("LMOCI_ENDPOINT", "")
	if _, err := h.QueueCreate(false, "offline-q"); err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	if msgs, err := h.QueueReceive(false, "offline-q", 1, 1, 30); err != nil || len(msgs) != 0 {
		t.Fatalf("offline receive: %v %v", msgs, err)
	}
	if d := time.Since(start); d < 900*time.Millisecond || d > 3*time.Second {
		t.Fatalf("offline wait=1 took %v, want ~1s (host wait loop over the fake's own caps)", d)
	}
}

// httpguard-based transport checks on the real adapter path.
func guardHost(t *testing.T, srv *httpguard.Server) *cloudhost.Host {
	t.Helper()
	t.Setenv("LMOCI_ENDPOINT", srv.URL)
	t.Setenv("LMOCI_LIVE", "")
	a := New(nil)
	a.remote.retry = fastRetry()
	h := cloudhost.New(cloudhost.Config{Provider: "oci", Store: a, Queue: a, Endpoints: Endpoints()})
	h.SetAbortOnError(false)
	return h
}

func TestGuard503RetriedThreeTimes(t *testing.T) {
	srv := httpguard.New(t)
	srv.Script(httpguard.Status(503, `{"code":"ServiceUnavailable","message":"busy"}`))
	h := guardHost(t, srv)
	if ex, _ := h.BucketExists(false, "bkt"); ex || h.LastError().Code != hostkit.Internal || h.LastError().HTTPStatus != 503 {
		t.Fatalf("last=%+v", h.LastError())
	}
	httpguard.AssertCount(t, srv, "GET", 3)
}

func TestGuardOversizeObjectRejected(t *testing.T) {
	srv := httpguard.New(t)
	srv.Script(httpguard.Status(200, `"floci-local"`), httpguard.Oversize(hostkit.MaxObjectBytes+1))
	h := guardHost(t, srv)
	if body, _ := h.ObjectGet(false, "bkt", "big"); body != "" || h.LastError().Code != hostkit.Internal || h.LastError().HTTPStatus != 200 {
		t.Fatalf("len=%d last=%+v", len(body), h.LastError())
	}
}

func TestGuardRedirectNeverForwardsSignature(t *testing.T) {
	foreign := httpguard.New(t)
	srv := httpguard.New(t)
	srv.Script(httpguard.RedirectTo(foreign.URL + "/n/"))
	h := guardHost(t, srv)
	if _, _ = h.BucketExists(false, "bkt"); h.LastError().Code != hostkit.Internal {
		t.Fatalf("cross-origin redirect must fail: %+v", h.LastError())
	}
	httpguard.AssertCount(t, foreign, "", 0)
	httpguard.AssertNoCredentials(t, foreign)
}

// A message send whose request reached the server and then lost the
// connection is not retried (possible duplicate message).
func TestGuardSendHangUpNotRetried(t *testing.T) {
	srv := httpguard.New(t)
	srv.Script(httpguard.Status(200, `{"items":[{"id":"ocid1.queue.q","displayName":"q-one","lifecycleState":"ACTIVE"}]}`), httpguard.HangUp())
	h := guardHost(t, srv)
	if id, _ := h.QueueSend(false, "q-one", "x"); id != "" || h.LastError().Code != hostkit.Internal {
		t.Fatalf("id=%q last=%+v", id, h.LastError())
	}
	httpguard.AssertCount(t, srv, "POST", 1)
}
