// SPDX-License-Identifier: MIT

package oci

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/lmctl-ai/lmcloud/hostkit"
	"github.com/lmctl-ai/lmcloud/hostkit/cloudhost"
)

func TestClassifyTable(t *testing.T) {
	body := func(code, msg string) []byte {
		b, _ := json.Marshal(map[string]string{"code": code, "message": msg})
		return b
	}
	cases := []struct {
		name   string
		status int
		code   string
		msg    string
		header string
		sc     scope
		want   hostkit.Code
		absent hostkit.Absence
	}{
		{"401", 401, "NotAuthenticated", "x", "", scopeNone, hostkit.Unauthenticated, ""},
		{"403 ocisim denial", 403, "NotAuthorizedOrNotFound", "x", "", scopeBucket, hostkit.PermissionDenied, ""},
		{"404 live-OCI denial (H1)", 404, "NotAuthorizedOrNotFound", "Authorization failed", "", scopeBucket, hostkit.PermissionDenied, ""},
		{"404 denial via header (HEAD)", 404, "", "", "NotAuthorizedOrNotFound", scopeObject, hostkit.PermissionDenied, ""},
		{"404 bucket", 404, "BucketNotFound", "x", "", scopeObject, hostkit.NotFound, hostkit.AbsentBucket},
		{"404 object", 404, "ObjectNotFound", "x", "", scopeObject, hostkit.NotFound, hostkit.AbsentObject},
		{"404 queue route", 404, "", "", "", scopeQueue, hostkit.NotFound, hostkit.AbsentQueue},
		{"404 vague", 404, "NamespaceNotFound", "x", "", scopeNone, hostkit.NotFound, ""},
		{"receipt quirk on message op", 404, "NotAuthorizedOrNotFound", "No in-flight message for receipt: abc", "", scopeMessage, hostkit.NotFound, hostkit.AbsentMessage},
		{"receipt text on non-message op stays denial", 404, "NotAuthorizedOrNotFound", "No in-flight message for receipt: abc", "", scopeQueue, hostkit.PermissionDenied, ""},
		{"real denial on message op", 404, "NotAuthorizedOrNotFound", "Authorization failed or requested resource not found: queue x", "", scopeMessage, hostkit.PermissionDenied, ""},
		{"409 exists", 409, "BucketAlreadyExists", "x", "", scopeBucket, hostkit.Conflict, ""},
		{"409 not empty", 409, "Conflict", "not empty", "", scopeBucket, hostkit.Conflict, ""},
		{"412", 412, "IfMatchFailed", "x", "", scopeObject, hostkit.PreconditionFailed, ""},
		{"400", 400, "InvalidParameter", "x", "", scopeNone, hostkit.InvalidArgument, ""},
		{"429", 429, "TooManyRequests", "x", "", scopeNone, hostkit.Internal, ""},
		{"503", 503, "ServiceUnavailable", "x", "", scopeNone, hostkit.Internal, ""},
		{"418", 418, "", "", "", scopeNone, hostkit.Internal, ""},
	}
	for _, c := range cases {
		resp := &response{Status: c.status, Header: http.Header{}}
		if c.code != "" {
			resp.Body = body(c.code, c.msg)
		}
		if c.header != "" {
			resp.Header.Set("opc-error-code", c.header)
		}
		e := classify(resp, c.sc)
		if e.Code != c.want || e.Absent != c.absent || e.HTTPStatus != c.status || !e.Code.Valid() {
			t.Errorf("%s: got %s/%q http=%d, want %s/%q", c.name, e.Code, e.Absent, e.HTTPStatus, c.want, c.absent)
		}
	}
}

// mock is a minimal OCI endpoint; handlers are keyed by "METHOD path-prefix".
type mock struct {
	t        *testing.T
	mu       sync.Mutex
	requests []string
	handle   func(w http.ResponseWriter, r *http.Request) bool
}

func (m *mock) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	m.mu.Lock()
	m.requests = append(m.requests, r.Method+" "+r.URL.Path)
	m.mu.Unlock()
	if m.handle != nil && m.handle(w, r) {
		return
	}
	if r.URL.Path == "/n/" {
		writeJSON(w, 200, "floci-local")
		return
	}
	writeJSON(w, 404, map[string]string{"code": "BucketNotFound", "message": "mock"})
}

func (m *mock) count(prefix string) int {
	m.mu.Lock()
	defer m.mu.Unlock()
	n := 0
	for _, r := range m.requests {
		if strings.HasPrefix(r, prefix) {
			n++
		}
	}
	return n
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func newHost(t *testing.T, handle func(w http.ResponseWriter, r *http.Request) bool) (*cloudhost.Host, *mock) {
	t.Helper()
	m := &mock{t: t, handle: handle}
	srv := httptest.NewServer(m)
	t.Cleanup(srv.Close)
	t.Setenv("LMOCI_ENDPOINT", srv.URL)
	t.Setenv("LMOCI_LIVE", "")
	a := New(nil)
	a.remote.retry = fastRetry()
	h := cloudhost.New(cloudhost.Config{Provider: "oci", Store: a, Queue: a, Endpoints: Endpoints()})
	h.SetAbortOnError(false)
	return h, m
}

func fastRetry() hostkit.RetryPolicy {
	p := hostkit.DefaultRetryPolicy()
	p.Jitter = func(time.Duration) time.Duration { return 0 } // no waiting in tests
	return p
}

func code(h *cloudhost.Host) hostkit.Code { return h.LastError().Code }

// Sol finding 3 on the real adapter: 503 is retried three times, then INTERNAL with http=503.
func TestServiceUnavailableRetriedThenInternal(t *testing.T) {
	h, m := newHost(t, func(w http.ResponseWriter, r *http.Request) bool {
		writeJSON(w, 503, map[string]string{"code": "ServiceUnavailable", "message": "busy"})
		return true
	})
	if ex, _ := h.BucketExists(false, "bkt"); ex || code(h) != hostkit.Internal || h.LastError().HTTPStatus != 503 || h.LastError().ProviderCode != "ServiceUnavailable" {
		t.Fatalf("last=%+v", h.LastError())
	}
	if n := m.count("GET /n/"); n != 3 {
		t.Fatalf("GET /n/ attempts = %d, want 3", n)
	}
}

// Sol finding 4 on the real adapter: a 33,562,624-byte object body is rejected, not truncated.
func TestOversizeObjectRejected(t *testing.T) {
	h, _ := newHost(t, func(w http.ResponseWriter, r *http.Request) bool {
		if strings.HasPrefix(r.URL.Path, "/n/floci-local/b/bkt/o/") && r.Method == http.MethodGet {
			w.WriteHeader(200)
			_, _ = io.CopyN(w, zeroReader{}, 33_562_624)
			return true
		}
		return false
	})
	if body, _ := h.ObjectGet(false, "bkt", "big.bin"); body != "" || code(h) != hostkit.Internal {
		t.Fatalf("len=%d last=%+v", len(body), h.LastError())
	}
}

type zeroReader struct{}

func (zeroReader) Read(p []byte) (int, error) {
	for i := range p {
		p[i] = 'x'
	}
	return len(p), nil
}

// Sol finding 5: HEAD 404 NotAuthorizedOrNotFound must never become exists()==false.
func TestHeadObjectDenialNeverAbsent(t *testing.T) {
	cases := []struct {
		name      string
		headCode  string // opc-error-code on HEAD ("" = none, like ocisim)
		rangeCode string // code returned by the ranged GET fallback
		want      hostkit.Code
		exists    bool
	}{
		{"header says denial", "NotAuthorizedOrNotFound", "", hostkit.PermissionDenied, false},
		{"ranged GET says denial", "", "NotAuthorizedOrNotFound", hostkit.PermissionDenied, false},
		{"ranged GET says object absent", "", "ObjectNotFound", hostkit.OK, false},
		{"ranged GET says bucket absent", "", "BucketNotFound", hostkit.NotFound, false},
		{"ranged GET unclassifiable", "", "", hostkit.PermissionDenied, false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			h, m := newHost(t, func(w http.ResponseWriter, r *http.Request) bool {
				if r.URL.Path == "/n/floci-local/b/bkt" {
					writeJSON(w, 200, map[string]string{"name": "bkt"})
					return true
				}
				if strings.HasPrefix(r.URL.Path, "/n/floci-local/b/bkt/o/") {
					if r.Method == http.MethodHead {
						if c.headCode != "" {
							w.Header().Set("opc-error-code", c.headCode)
						}
						w.WriteHeader(404)
						return true
					}
					if r.Header.Get("Range") != "bytes=0-0" {
						t.Errorf("fallback GET must be ranged, got %q", r.Header.Get("Range"))
					}
					if c.rangeCode == "" {
						w.WriteHeader(404)
						return true
					}
					writeJSON(w, 404, map[string]string{"code": c.rangeCode, "message": "m"})
					return true
				}
				return false
			})
			meta, _ := h.ObjectHead(false, "bkt", "secret.txt")
			if meta.Exists != c.exists || code(h) != c.want {
				t.Fatalf("exists=%v last=%+v", meta.Exists, h.LastError())
			}
			if c.headCode != "" && m.count("GET /n/floci-local/b/bkt/o/") != 0 {
				t.Fatal("no fallback GET needed when the header names the code")
			}
		})
	}
}

// Sol finding 6: a stalled continuation token fails instead of returning partial data.
func TestStalledListTokenFails(t *testing.T) {
	h, _ := newHost(t, func(w http.ResponseWriter, r *http.Request) bool {
		if r.URL.Path == "/n/floci-local/b/bkt/o" {
			writeJSON(w, 200, map[string]any{"objects": []map[string]any{{"name": "a", "size": 1, "etag": "e"}}, "nextStartWith": "a"})
			return true
		}
		return false
	})
	if keys, _ := h.ObjectListAll(false, "bkt", ""); keys != nil || code(h) != hostkit.Internal {
		t.Fatalf("keys=%v last=%+v", keys, h.LastError())
	}
}

func queueItem(id, name, state string) map[string]string {
	return map[string]string{"id": id, "displayName": name, "lifecycleState": state, "messagesEndpoint": "https://cell-1.queue.example"}
}

// Queue lookup follows opc-next-page and ignores non-ACTIVE queues; create waits for the work request.
func TestQueueLookupPagesAndWorkRequest(t *testing.T) {
	var created atomic.Bool
	var polls atomic.Int32
	h, m := newHost(t, func(w http.ResponseWriter, r *http.Request) bool {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/20210201/queues":
			if r.URL.Query().Get("page") == "" {
				w.Header().Set("opc-next-page", "p2")
				writeJSON(w, 200, map[string]any{"items": []any{queueItem("ocid1.queue.old", "q-one", "DELETED")}})
				return true
			}
			items := []any{}
			if created.Load() {
				items = append(items, queueItem("ocid1.queue.new", "q-one", "ACTIVE"))
			}
			writeJSON(w, 200, map[string]any{"items": items})
			return true
		case r.Method == http.MethodPost && r.URL.Path == "/20210201/queues":
			var body map[string]string
			_ = json.NewDecoder(r.Body).Decode(&body)
			if body["compartmentId"] != "ocid1.compartment.custom" {
				t.Errorf("compartmentId = %q", body["compartmentId"])
			}
			w.Header().Set("opc-work-request-id", "wr1")
			w.WriteHeader(202)
			return true
		case r.URL.Path == "/20210201/workRequests/wr1":
			if polls.Add(1) < 2 {
				writeJSON(w, 200, map[string]string{"status": "IN_PROGRESS"})
				return true
			}
			created.Store(true)
			writeJSON(w, 200, map[string]string{"status": "SUCCEEDED"})
			return true
		case r.URL.Path == "/20210201/queues/ocid1.queue.new/messages" && r.Method == http.MethodPost:
			writeJSON(w, 200, map[string]any{"messages": []map[string]any{{"id": 42}}})
			return true
		}
		return false
	})
	t.Setenv("LMOCI_SIM_COMPARTMENT_ID", "ocid1.compartment.custom")
	if c, _ := h.QueueCreate(false, "q-one"); !c || code(h) != hostkit.OK {
		t.Fatalf("create: %+v", h.LastError())
	}
	if polls.Load() < 2 {
		t.Fatal("create must wait for the work request")
	}
	if id, _ := h.QueueSend(false, "q-one", "hi"); id != "42" {
		t.Fatalf("send id=%q last=%+v", id, h.LastError())
	}
	if m.count("POST /20210201/queues/ocid1.queue.new/messages") != 1 {
		t.Fatal("loopback override must keep data-plane traffic on the override, not messagesEndpoint")
	}
}

func TestWorkRequestFailureIsInternal(t *testing.T) {
	h, _ := newHost(t, func(w http.ResponseWriter, r *http.Request) bool {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/20210201/queues":
			writeJSON(w, 200, map[string]any{"items": []any{}})
		case r.Method == http.MethodPost:
			w.Header().Set("opc-work-request-id", "wr2")
			w.WriteHeader(202)
		case r.URL.Path == "/20210201/workRequests/wr2":
			writeJSON(w, 200, map[string]string{"status": "FAILED"})
		default:
			return false
		}
		return true
	})
	if c, _ := h.QueueCreate(false, "q-two"); c || code(h) != hostkit.Internal {
		t.Fatalf("created=%v last=%+v", c, h.LastError())
	}
}

// Receipt quirk: only ocisim's "No in-flight message for receipt" 404 is an absent message.
func TestReceiptQuirkIsNarrow(t *testing.T) {
	deny := false
	h, _ := newHost(t, func(w http.ResponseWriter, r *http.Request) bool {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/20210201/queues":
			writeJSON(w, 200, map[string]any{"items": []any{queueItem("ocid1.queue.q", "q-one", "ACTIVE")}})
		case strings.HasPrefix(r.URL.Path, "/20210201/queues/ocid1.queue.q/messages/"):
			msg := "No in-flight message for receipt: r1"
			if deny {
				msg = "Authorization failed or requested resource not found"
			}
			writeJSON(w, 404, map[string]string{"code": "NotAuthorizedOrNotFound", "message": msg})
		default:
			return false
		}
		return true
	})
	if _ = h.QueueAck(false, "q-one", "r1"); code(h) != hostkit.OK {
		t.Fatalf("re-ack of a gone message must be OK: %+v", h.LastError())
	}
	if _, _ = h.QueueNack(false, "q-one", "r1", 0); code(h) != hostkit.InvalidArgument {
		t.Fatalf("nack of a gone message must be INVALID_ARGUMENT: %+v", h.LastError())
	}
	deny = true
	if _ = h.QueueAck(false, "q-one", "r1"); code(h) != hostkit.PermissionDenied {
		t.Fatalf("a real denial must not be masked as an idempotent ack: %+v", h.LastError())
	}
}

func TestSendWithoutIDIsInternal(t *testing.T) {
	h, _ := newHost(t, func(w http.ResponseWriter, r *http.Request) bool {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/20210201/queues":
			writeJSON(w, 200, map[string]any{"items": []any{queueItem("ocid1.queue.q", "q-one", "ACTIVE")}})
		case r.Method == http.MethodPost:
			writeJSON(w, 200, map[string]any{"messages": []any{}})
		default:
			return false
		}
		return true
	})
	if id, _ := h.QueueSend(false, "q-one", "x"); id != "" || code(h) != hostkit.Internal {
		t.Fatalf("id=%q last=%+v", id, h.LastError())
	}
}

// Credentials and scope: LMOCI_SIM_KEY_ID signs requests, its tenancy is the
// default compartment, LMOCI_SIM_NAMESPACE skips namespace discovery.
func TestSimCredentialsAndScope(t *testing.T) {
	var auth, compartment string
	h, m := newHost(t, func(w http.ResponseWriter, r *http.Request) bool {
		if r.Method == http.MethodPost && r.URL.Path == "/n/ns-x/b/" {
			auth = r.Header.Get("Authorization")
			var body map[string]string
			_ = json.NewDecoder(r.Body).Decode(&body)
			compartment = body["compartmentId"]
			writeJSON(w, 200, body)
			return true
		}
		return false
	})
	t.Setenv("LMOCI_SIM_KEY_ID", "ocid1.tenancy.oc1..t1/ocid1.user.oc1..u1/aa:bb")
	t.Setenv("LMOCI_SIM_NAMESPACE", "ns-x")
	if c, _ := h.BucketCreate(false, "bkt"); !c {
		t.Fatalf("create: %+v", h.LastError())
	}
	if !strings.Contains(auth, `keyId="ocid1.tenancy.oc1..t1/ocid1.user.oc1..u1/aa:bb"`) || compartment != "ocid1.tenancy.oc1..t1" {
		t.Fatalf("auth=%q compartment=%q", auth, compartment)
	}
	if m.count("GET /n/") != 0 {
		t.Fatal("LMOCI_SIM_NAMESPACE must skip GET /n/")
	}
	t.Setenv("LMOCI_SIM_PRIVATE_KEY_PATH", "/nonexistent/key.pem")
	if _, _ = h.BucketCreate(false, "bkt2"); code(h) != hostkit.Unauthenticated {
		t.Fatalf("unreadable key must be UNAUTHENTICATED: %+v", h.LastError())
	}
}

// Endpoint routing: unsafe sources are rejected before any request; live and
// non-loopback endpoints are an explicit not-implemented error; no endpoint is offline.
func TestEndpointRouting(t *testing.T) {
	h, m := newHost(t, nil)
	t.Setenv("LMOCI_ENDPOINT", "http://0.0.0.0:4599")
	if _, _ = h.BucketExists(false, "bkt"); code(h) != hostkit.InvalidArgument || len(m.requests) != 0 {
		t.Fatalf("0.0.0.0: %+v requests=%v", h.LastError(), m.requests)
	}
	t.Setenv("LMOCI_ENDPOINT", "https://objectstorage.us-ashburn-1.oraclecloud.com")
	if _, _ = h.BucketExists(false, "bkt"); code(h) != hostkit.Unauthenticated || h.LastError().ProviderCode != "LiveCredentialsNotImplemented" {
		t.Fatalf("non-loopback: %+v", h.LastError())
	}
	t.Setenv("LMOCI_ENDPOINT", "")
	t.Setenv("LMOCI_LIVE", "1")
	if _, _ = h.BucketCreate(false, "bkt"); h.LastError().ProviderCode != "LiveCredentialsNotImplemented" {
		t.Fatalf("live: %+v", h.LastError())
	}
	t.Setenv("LMOCI_LIVE", "")
	if c, _ := h.BucketCreate(false, "offline-bkt"); !c || code(h) != hostkit.OK {
		t.Fatalf("offline fake: %+v", h.LastError())
	}
	if len(m.requests) != 0 {
		t.Fatalf("offline/live paths must not reach the network: %v", m.requests)
	}
}
