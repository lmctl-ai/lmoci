package host

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	lmcode "github.com/lmctl-ai/lmcode-go"
)

func TestRegisterAndHostSignatures(t *testing.T) {
	reg := lmcode.NewRegistry()
	h := New(Options{})
	if err := Register(reg, h); err != nil {
		t.Fatalf("Register failed: %v", err)
	}
	hostSigs := reg.HostSignatures()
	if len(hostSigs) == 0 {
		t.Fatalf("expected non-empty host signatures")
	}
}

func TestValidationGuards(t *testing.T) {
	if cerr := ValidateBucketName("Bad_Bucket"); cerr == nil || cerr.Code != CodeInvalidArgument {
		t.Fatalf("expected INVALID_ARGUMENT for Bad_Bucket, got %+v", cerr)
	}
	if cerr := ValidateBucketName("sthree-bucket"); cerr == nil || cerr.Code != CodeInvalidArgument {
		t.Fatalf("expected INVALID_ARGUMENT for sthree-bucket, got %+v", cerr)
	}
	if cerr := ValidateObjectKey("seg./nested.txt"); cerr == nil || cerr.Code != CodeInvalidArgument {
		t.Fatalf("expected INVALID_ARGUMENT for trailing dot segment, got %+v", cerr)
	}
	if cerr := ValidateContentType("invalid-no-slash"); cerr == nil || cerr.Code != CodeInvalidArgument {
		t.Fatalf("expected INVALID_ARGUMENT for content_type without slash, got %+v", cerr)
	}
	if cerr := ValidateQueueName("my-google-queue"); cerr == nil || cerr.Code != CodeInvalidArgument {
		t.Fatalf("expected INVALID_ARGUMENT for reserved queue substring, got %+v", cerr)
	}
	if _, cerr := ValidateEndpointURL("http://127.0.0.1.evil.example:4599"); cerr == nil || cerr.Code != CodeInvalidArgument {
		t.Fatalf("expected INVALID_ARGUMENT for non-loopback http endpoint, got %+v", cerr)
	}
	if norm, cerr := ValidateEndpointURL("http://127.0.0.1:4599/"); cerr != nil || norm != "http://127.0.0.1:4599" {
		t.Fatalf("expected normalized loopback endpoint, got %q, %+v", norm, cerr)
	}
}

func TestEncodeObjectKeyPathAndQueueCodec(t *testing.T) {
	got := EncodeObjectKeyPath("dir/a+b%20c?d#e f.txt")
	want := "dir/a%2Bb%2520c%3Fd%23e%20f.txt"
	if got != want {
		t.Fatalf("EncodeObjectKeyPath = %q; want %q", got, want)
	}

	cases := []string{
		"",
		"__lmcloud_empty__",
		"__lmcloud_esc__:literal",
		"__lmcloud_b64__:literal",
		"hello 🚀 world",
		string([]byte{0x00, 0x01, 0x7f, 0x80, 0xfe, 0xff, 'A', 0x00}),
	}
	for _, tc := range cases {
		wire := EncodeQueueBody(tc)
		if wire == "" {
			t.Fatalf("EncodeQueueBody(%q) produced empty wire payload", tc)
		}
		roundtrip := DecodeQueueBody(wire)
		if roundtrip != tc {
			t.Fatalf("queue codec roundtrip mismatch: got %x, want %x (wire=%q)", roundtrip, tc, wire)
		}
	}
}

func TestFakeStorageAndQueueLifecycle(t *testing.T) {
	h := New(Options{})
	if h.Provider() != "oci" {
		t.Fatalf("Provider() = %q; want oci", h.Provider())
	}

	created, err := h.BucketCreate("unit-test-bucket")
	if err != nil || !created {
		t.Fatalf("BucketCreate first call = %v, %v; want true, nil", created, err)
	}
	created2, err := h.BucketCreate("unit-test-bucket")
	if err != nil || created2 {
		t.Fatalf("BucketCreate second call = %v, %v; want false, nil", created2, err)
	}

	meta, err := h.ObjectPut("unit-test-bucket", "docs/readme.txt", "hello-oci", "text/plain", map[string]string{"env": "test"})
	if err != nil || !meta.Exists || meta.Size != 9 {
		t.Fatalf("ObjectPut failed: meta=%+v, err=%v", meta, err)
	}

	// Non-empty bucket delete must fail with CONFLICT
	if _, err := h.BucketDelete("unit-test-bucket"); err == nil {
		t.Fatalf("expected BucketDelete on non-empty bucket to fail")
	}
	if h.LastError().Code != CodeConflict {
		t.Fatalf("expected last_error CONFLICT, got %s", h.LastError().Code)
	}

	if err := h.ObjectDelete("unit-test-bucket", "docs/readme.txt"); err != nil {
		t.Fatalf("ObjectDelete failed: %v", err)
	}
	deleted, err := h.BucketDelete("unit-test-bucket")
	if err != nil || !deleted {
		t.Fatalf("BucketDelete = %v, %v; want true, nil", deleted, err)
	}

	// Queue lifecycle
	qCreated, err := h.QueueCreate("unit-test-queue")
	if err != nil || !qCreated {
		t.Fatalf("QueueCreate = %v, %v; want true, nil", qCreated, err)
	}
	msgID, err := h.QueueSend("unit-test-queue", "")
	if err != nil || msgID == "" {
		t.Fatalf("QueueSend empty body = %q, %v", msgID, err)
	}
	batch, err := h.QueueReceive("unit-test-queue", 1, 0, 30)
	if err != nil || len(batch.Items) != 1 || batch.Items[0].Body != "" {
		t.Fatalf("QueueReceive failed: %+v, %v", batch, err)
	}
	rcpt2, err := h.QueueNack("unit-test-queue", batch.Items[0].Receipt, 0)
	if err != nil || rcpt2 == "" {
		t.Fatalf("QueueNack = %q, %v", rcpt2, err)
	}
	if err := h.QueueAck("unit-test-queue", rcpt2); err != nil {
		t.Fatalf("QueueAck = %v", err)
	}
	// Duplicate ack is idempotent
	if err := h.QueueAck("unit-test-queue", rcpt2); err != nil {
		t.Fatalf("duplicate QueueAck = %v", err)
	}
}

func TestObjectStorageWireAndH1AuthMapping(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth := r.Header.Get("Authorization")
		if !strings.HasPrefix(auth, "Signature ") {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"code":"NotAuthenticated","message":"missing signature"}`))
			return
		}
		if r.URL.Path == "/n/" {
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`"floci-local"`))
			return
		}
		// H1 live-OCI simulation: 404 with NotAuthorizedOrNotFound must map to PERMISSION_DENIED, never exists==false
		if strings.HasSuffix(r.URL.Path, "/b/restricted-bucket") {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"code":"NotAuthorizedOrNotFound","message":"no inspect permission"}`))
			return
		}
		if strings.Contains(r.URL.RawPath, "/o/dir/a%2Bb%2520c.txt") && r.Method == http.MethodPut {
			if r.Header.Get("opc-meta-custom_tag") != "val1" {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			w.Header().Set("ETag", `"etag-quoted-123"`)
			w.WriteHeader(http.StatusOK)
			return
		}
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"code":"BucketNotFound","message":"not found"}`))
	}))
	defer srv.Close()

	h := New(Options{DefaultEndpoint: srv.URL})
	h.SetAbortOnError(false)

	exists, err := h.BucketExists("restricted-bucket")
	if err != nil || exists {
		t.Fatalf("expected BucketExists false without abort, got exists=%v, err=%v", exists, err)
	}
	if h.LastError().Code != CodePermissionDenied {
		t.Fatalf("H1 violation: expected PERMISSION_DENIED on 404+NotAuthorizedOrNotFound, got %+v", h.LastError())
	}

	meta, err := h.ObjectPut("allowed-bucket", "dir/a+b%20c.txt", "payload", "text/plain", map[string]string{"custom_tag": "val1"})
	if err != nil || !meta.Exists || meta.ETag != "etag-quoted-123" {
		t.Fatalf("ObjectPut wire test failed: meta=%+v, err=%v, lastErr=%+v", meta, err, h.LastError())
	}
}

