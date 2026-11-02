// SPDX-License-Identifier: MIT

// Package oci is the OCI adapter for the shared lmcloud host kit: request
// signing and simulator credentials, Object Storage and Queue REST calls, and
// classification of OCI responses onto the eight shared error codes. Every
// provider-neutral rule (validation, codec, retry policy, pagination, dry
// runs, abort format) lives in github.com/lmctl-ai/lmcloud/hostkit.
package oci

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/oracle/oci-go-sdk/v65/common"

	"github.com/lmctl-ai/lmcloud/hostkit"
)

// Simulator defaults (design 02 §3.2, loopback column).
const (
	SimTenancy     = "ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000"
	SimUser        = "ocid1.user.oc1..flocilocaluser0000000000000000000000000000000000000000000"
	SimFingerprint = "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"
	SimNamespace   = "floci-local"
	maxKeyFile     = 64 << 10
	maxBody        = hostkit.MaxObjectBytes
)

// errLiveNotImplemented is returned for live OCI and for any non-loopback
// endpoint: the live credential chain (OCI_* env, ~/.oci/config profiles)
// is not implemented yet, and simulator keys are never sent off-host.
func errLiveNotImplemented(what string) *hostkit.CloudError {
	return hostkit.ProviderError(hostkit.Unauthenticated, 0, "LiveCredentialsNotImplemented",
		"live OCI is not supported yet ("+what+"): TODO implement the OCI_TENANCY_OCID/OCI_USER_OCID/OCI_FINGERPRINT/OCI_PRIVATE_KEY_PATH and OCI_CONFIG_FILE (~/.oci/config) chain; only loopback simulator endpoints work today")
}

// keyProvider satisfies common.KeyProvider for the request signer.
type keyProvider struct {
	keyID string
	key   *rsa.PrivateKey
}

func (k keyProvider) PrivateRSAKey() (*rsa.PrivateKey, error) { return k.key, nil }
func (k keyProvider) KeyID() (string, error)                  { return k.keyID, nil }

// remote talks to one OCI-compatible loopback endpoint.
type remote struct {
	client *http.Client
	retry  hostkit.RetryPolicy
	getenv func(string) string

	mu        sync.Mutex
	keys      map[string]*rsa.PrivateKey // by key file path ("" = ephemeral)
	namespace map[string]string          // by endpoint
	queues    map[string]queueRef        // by endpoint + "\x00" + name
}

type queueRef struct {
	ID       string
	Messages string // data-plane base URL
}

func newRemote(getenv func(string) string) *remote {
	return &remote{
		client:    hostkit.NewHTTPClient(60 * time.Second),
		retry:     hostkit.DefaultRetryPolicy(),
		getenv:    getenv,
		keys:      map[string]*rsa.PrivateKey{},
		namespace: map[string]string{},
		queues:    map[string]queueRef{},
	}
}

// credentials returns the simulator signing identity (spec M3 loopback column):
// LMOCI_SIM_KEY_ID + LMOCI_SIM_PRIVATE_KEY_PATH, else a synthetic open-mode
// keyId signed with an ephemeral key. The tenancy is the keyId's first part.
func (r *remote) credentials() (keyProvider, string, *hostkit.CloudError) {
	keyID := strings.TrimSpace(r.getenv("LMOCI_SIM_KEY_ID"))
	if keyID == "" {
		keyID = SimTenancy + "/" + SimUser + "/" + SimFingerprint
	}
	tenancy := SimTenancy
	if first, _, ok := strings.Cut(keyID, "/"); ok && strings.HasPrefix(first, "ocid1.tenancy.") {
		tenancy = first
	}
	path := strings.TrimSpace(r.getenv("LMOCI_SIM_PRIVATE_KEY_PATH"))
	r.mu.Lock()
	defer r.mu.Unlock()
	if key, ok := r.keys[path]; ok {
		return keyProvider{keyID: keyID, key: key}, tenancy, nil
	}
	var key *rsa.PrivateKey
	if path == "" {
		k, err := rsa.GenerateKey(rand.Reader, 2048)
		if err != nil {
			return keyProvider{}, "", hostkit.ProviderError(hostkit.Internal, 0, "", "generating ephemeral signing key: "+err.Error())
		}
		key = k
	} else {
		k, err := readKey(path)
		if err != nil {
			return keyProvider{}, "", hostkit.ProviderError(hostkit.Unauthenticated, 0, "InvalidPrivateKey", "LMOCI_SIM_PRIVATE_KEY_PATH: "+err.Error())
		}
		key = k
	}
	r.keys[path] = key
	return keyProvider{keyID: keyID, key: key}, tenancy, nil
}

func readKey(path string) (*rsa.PrivateKey, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	raw, err := io.ReadAll(io.LimitReader(f, maxKeyFile+1))
	if err != nil {
		return nil, err
	}
	if len(raw) > maxKeyFile {
		return nil, fmt.Errorf("key file is larger than %d bytes", maxKeyFile)
	}
	for rest := raw; len(rest) > 0; {
		var block *pem.Block
		block, rest = pem.Decode(rest)
		if block == nil {
			break
		}
		if k, err := x509.ParsePKCS1PrivateKey(block.Bytes); err == nil {
			return k, nil
		}
		if k, err := x509.ParsePKCS8PrivateKey(block.Bytes); err == nil {
			if rk, ok := k.(*rsa.PrivateKey); ok {
				return rk, nil
			}
		}
	}
	return nil, fmt.Errorf("no RSA private key found in PEM")
}

func (r *remote) compartment(tenancy string) string {
	if c := strings.TrimSpace(r.getenv("LMOCI_SIM_COMPARTMENT_ID")); c != "" {
		return c
	}
	return tenancy
}

// response is a fully read reply.
type response struct {
	Status int
	Header http.Header
	Body   []byte
}

// do signs and sends one request with the shared retry policy (the OCI SDK
// is used only for signing, so no SDK retries can multiply attempts). The
// body is read with ReadBounded: oversize replies fail instead of truncating.
func (r *remote) do(ctx context.Context, method, base, path string, query url.Values, body []byte, contentType string, headers map[string]string) (*response, *hostkit.CloudError) {
	return r.send(ctx, hostkit.IdempotentMethod(method), method, base, path, query, body, contentType, headers)
}

// send is do with an explicit retry-safety flag (a POST is retry-safe only
// when a retry is resolved by the provider, e.g. a bucket create that turns
// into 409 + create-token proof).
func (r *remote) send(ctx context.Context, idempotent bool, method, base, path string, query url.Values, body []byte, contentType string, headers map[string]string) (*response, *hostkit.CloudError) {
	kp, _, cerr := r.credentials()
	if cerr != nil {
		return nil, cerr
	}
	u, err := url.Parse(base)
	if err != nil {
		return nil, hostkit.ProviderError(hostkit.InvalidArgument, 0, "", "invalid endpoint")
	}
	unescaped, err := url.PathUnescape(path)
	if err != nil {
		return nil, hostkit.ProviderError(hostkit.Internal, 0, "", "invalid request path")
	}
	target := &url.URL{Scheme: u.Scheme, Host: u.Host, Path: strings.TrimRight(u.Path, "/") + unescaped, RawPath: strings.TrimRight(u.Path, "/") + path}
	if query != nil {
		target.RawQuery = query.Encode()
	}
	resp, _, err := r.retry.DoHTTP(ctx, r.client, idempotent, func(ctx context.Context) (*http.Request, error) {
		var rd io.Reader
		if body != nil {
			rd = bytes.NewReader(body)
		}
		req, err := http.NewRequestWithContext(ctx, method, target.String(), rd)
		if err != nil {
			return nil, err
		}
		req.URL = target
		req.Header.Set("Date", time.Now().UTC().Format(http.TimeFormat))
		if body != nil || method == http.MethodPost || method == http.MethodPut {
			if contentType == "" {
				contentType = "application/json"
			}
			req.Header.Set("Content-Type", contentType)
			if body == nil {
				req.Body = io.NopCloser(bytes.NewReader(nil))
			}
		}
		for k, v := range headers {
			req.Header.Set(k, v)
		}
		if err := common.DefaultRequestSigner(kp).Sign(req); err != nil {
			return nil, err
		}
		return req, nil
	})
	if err != nil {
		return nil, hostkit.ProviderError(hostkit.Internal, 0, "", "OCI request failed: "+err.Error())
	}
	defer resp.Body.Close()
	data, cerr := hostkit.ReadBoundedResponse(resp, maxBody)
	if cerr != nil {
		return nil, cerr
	}
	return &response{Status: resp.StatusCode, Header: resp.Header, Body: data}, nil
}

func decode(resp *response, v any) *hostkit.CloudError {
	if err := json.Unmarshal(resp.Body, v); err != nil {
		return hostkit.ProviderError(hostkit.Internal, resp.Status, "", "undecodable OCI response: "+err.Error())
	}
	return nil
}

// namespaceFor returns LMOCI_SIM_NAMESPACE, else GET /n/ (cached per endpoint).
// A 404 from GET /n/ (an endpoint without the namespace route) falls back to
// the simulator default; any other failure is reported, never masked.
func (r *remote) namespaceFor(ctx context.Context, base string) (string, *hostkit.CloudError) {
	if ns := strings.TrimSpace(r.getenv("LMOCI_SIM_NAMESPACE")); ns != "" {
		return ns, nil
	}
	r.mu.Lock()
	ns, ok := r.namespace[base]
	r.mu.Unlock()
	if ok {
		return ns, nil
	}
	resp, cerr := r.do(ctx, http.MethodGet, base, "/n/", nil, nil, "", nil)
	if cerr != nil {
		return "", cerr
	}
	switch {
	case resp.Status == http.StatusOK:
		if err := json.Unmarshal(resp.Body, &ns); err != nil || strings.TrimSpace(ns) == "" {
			return "", hostkit.ProviderError(hostkit.Internal, resp.Status, "", "GET /n/ returned no namespace")
		}
	case resp.Status == http.StatusNotFound && errorCode(resp) == "":
		ns = SimNamespace
	default:
		return "", classify(resp, scopeNone)
	}
	r.mu.Lock()
	r.namespace[base] = ns
	r.mu.Unlock()
	return ns, nil
}
