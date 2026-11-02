package host

import (
	"bytes"
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

	"github.com/lmctl-ai/lmoci/internal/safety"
)

const (
	DefaultTenancyID   = "ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000"
	DefaultUserID      = "ocid1.user.oc1..flocilocaluser0000000000000000000000000000000000000000000"
	DefaultFingerprint = "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"
	DefaultNamespace   = "floci-local"
	DefaultRegion      = "us-ashburn-1"
)

// Options configures a new Host instance.
type Options struct {
	DefaultEndpoint string
	Region          string
	Ledger          *safety.RunLedger
}

type fakeObject struct {
	data        []byte
	contentType string
	etag        string
	metadata    map[string]string
}

type fakeQueueMsg struct {
	id           int64
	wireBody     string
	receipt      string
	visibleAfter time.Time
	acked        bool
}

type fakeQueue struct {
	name   string
	nextID int64
	msgs   []*fakeQueueMsg
}

// Host implements the shared lmcloud Cloud context for OCI (lmoci).
type Host struct {
	mu               sync.Mutex
	abortOnError     bool
	lastError        *CloudError
	endpoints        map[string]string
	defaultEndpoint  string
	region           string
	ledger           *safety.RunLedger
	httpClient       *http.Client
	cachedNamespace  string
	queueIDCache     map[string]string
	fakeBuckets      map[string]map[string]*fakeObject
	fakeQueues       map[string]*fakeQueue
	fakeReceiptSeq   int64
}

var (
	ephemeralKeyOnce sync.Once
	ephemeralRSAKey  *rsa.PrivateKey
)

func getEphemeralRSAKey() (*rsa.PrivateKey, error) {
	var err error
	ephemeralKeyOnce.Do(func() {
		ephemeralRSAKey, err = rsa.GenerateKey(rand.Reader, 2048)
	})
	return ephemeralRSAKey, err
}

// New creates a new Host instance.
func New(opts Options) *Host {
	defEp := strings.TrimSpace(opts.DefaultEndpoint)
	if defEp == "" {
		defEp = firstNonEmpty(os.Getenv("LMOCI_ENDPOINT"), os.Getenv("OCI_ENDPOINT"))
	}
	defEp = strings.TrimRight(defEp, "/")

	reg := strings.TrimSpace(opts.Region)
	if reg == "" {
		reg = firstNonEmpty(os.Getenv("LMOCI_REGION"), os.Getenv("OCI_REGION"), os.Getenv("OCI_CLI_REGION"), DefaultRegion)
	}

	transport := &http.Transport{
		Proxy:               nil, // never consult HTTP(S)_PROXY
		MaxIdleConns:        32,
		MaxIdleConnsPerHost: 16,
		IdleConnTimeout:     30 * time.Second,
	}

	return &Host{
		abortOnError:    true,
		lastError:       okCloudError(),
		endpoints:       make(map[string]string),
		defaultEndpoint: defEp,
		region:          reg,
		ledger:          opts.Ledger,
		httpClient: &http.Client{
			Transport: transport,
			Timeout:   30 * time.Second,
		},
		queueIDCache: make(map[string]string),
		fakeBuckets:  make(map[string]map[string]*fakeObject),
		fakeQueues:   make(map[string]*fakeQueue),
	}
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if t := strings.TrimSpace(v); t != "" {
			return t
		}
	}
	return ""
}

// Provider returns the canonical provider identifier ("oci").
func (h *Host) Provider() string {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.lastError = okCloudError()
	return "oci"
}

// AbortOnError returns whether operations abort on error.
func (h *Host) AbortOnError() bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.abortOnError
}

// SetAbortOnError updates the abort-on-error flag and returns the receiver.
func (h *Host) SetAbortOnError(abort bool) *Host {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.abortOnError = abort
	h.lastError = okCloudError()
	return h
}

// LastError returns the structured CloudError from the most recent operation.
func (h *Host) LastError() *CloudError {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.lastError == nil {
		return okCloudError()
	}
	return h.lastError
}

// Endpoint returns the configured endpoint for service (or default endpoint).
func (h *Host) Endpoint(service string) (string, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if cerr := ValidateServiceName(service); cerr != nil {
		return "", h.finishErrorLocked("endpoint", service, cerr)
	}
	h.lastError = okCloudError()
	return h.resolveServiceEndpointLocked(service), nil
}

// SetEndpoint validates and sets the endpoint override for service.
func (h *Host) SetEndpoint(service, rawURL string) (*Host, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if cerr := ValidateServiceName(service); cerr != nil {
		return h, h.finishErrorLocked("set_endpoint", service, cerr)
	}
	norm, cerr := ValidateEndpointURL(rawURL)
	if cerr != nil {
		return h, h.finishErrorLocked("set_endpoint", rawURL, cerr)
	}
	h.endpoints[service] = norm
	if service == "" {
		h.defaultEndpoint = norm
	}
	h.lastError = okCloudError()
	return h, nil
}

func (h *Host) resolveServiceEndpointLocked(service string) string {
	if ep, ok := h.endpoints[service]; ok && ep != "" {
		return ep
	}
	switch service {
	case "storage", "object-storage", "object_storage", "objectstorage", "s3", "blob", "gcs":
		for _, k := range []string{"storage", "objectstorage", "object-storage", "object_storage", "s3", "blob", "gcs", ""} {
			if ep, ok := h.endpoints[k]; ok && ep != "" {
				return ep
			}
		}
	case "queue", "sqs", "pubsub":
		for _, k := range []string{"queue", "sqs", "pubsub", ""} {
			if ep, ok := h.endpoints[k]; ok && ep != "" {
				return ep
			}
		}
	case "":
		for _, k := range []string{"", "storage", "objectstorage", "queue"} {
			if ep, ok := h.endpoints[k]; ok && ep != "" {
				return ep
			}
		}
	}
	if h.defaultEndpoint != "" {
		return h.defaultEndpoint
	}
	return strings.TrimRight(firstNonEmpty(os.Getenv("LMOCI_ENDPOINT"), os.Getenv("OCI_ENDPOINT")), "/")
}

// resolveBackendEndpoint determines whether to use the in-memory fake, a configured endpoint, or live OCI.
func (h *Host) resolveBackendEndpointLocked(serviceKind string) (endpoint string, useFake bool, cerr *CloudError) {
	ep := h.resolveServiceEndpointLocked(serviceKind)
	if ep != "" {
		return ep, false, nil
	}
	if os.Getenv("LMOCI_LIVE") == "1" {
		if os.Getenv("CI") != "" && os.Getenv("LMOCI_ALLOW_LIVE_IN_CI") != "1" {
			return "", false, newCloudError(CodePermissionDenied, 0, "LiveInCIDisabled",
				"live OCI calls in CI require both LMOCI_LIVE=1 and LMOCI_ALLOW_LIVE_IN_CI=1")
		}
		if serviceKind == "queue" {
			return fmt.Sprintf("https://messaging.%s.oci.oraclecloud.com", h.region), false, nil
		}
		return fmt.Sprintf("https://objectstorage.%s.oraclecloud.com", h.region), false, nil
	}
	return "", true, nil
}

func (h *Host) finishOKLocked() {
	h.lastError = okCloudError()
}

func (h *Host) finishErrorLocked(op, target string, cerr *CloudError) error {
	if cerr == nil {
		cerr = newCloudError(CodeInternal, 0, "InternalError", "unknown error")
	}
	h.lastError = cerr
	if h.abortOnError {
		return cerr.FormatAbortError(op, target)
	}
	return nil
}

func (h *Host) recordEntry(kind, service, action, id string) {
	if h.ledger != nil {
		h.ledger.Record(kind, id)
	}
}

type ociStaticKeyProvider struct {
	keyID string
	key   *rsa.PrivateKey
}

func (p *ociStaticKeyProvider) PrivateRSAKey() (*rsa.PrivateKey, error) {
	return p.key, nil
}

func (p *ociStaticKeyProvider) KeyID() (string, error) {
	return p.keyID, nil
}

func parseRSAPrivateKeyPEM(pemBytes []byte) (*rsa.PrivateKey, error) {
	rest := pemBytes
	for len(rest) > 0 {
		var block *pem.Block
		block, rest = pem.Decode(rest)
		if block == nil {
			break
		}
		if key, err := x509.ParsePKCS1PrivateKey(block.Bytes); err == nil {
			return key, nil
		}
		if parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes); err == nil {
			if rsaKey, ok := parsed.(*rsa.PrivateKey); ok {
				return rsaKey, nil
			}
		}
	}
	return nil, fmt.Errorf("failed to parse RSA private key PEM")
}

func (h *Host) resolveCredentials(baseEndpoint string) (common.KeyProvider, string, *CloudError) {
	u, _ := url.Parse(baseEndpoint)
	loopback := u != nil && isLoopbackHost(u.Hostname())

	simKeyID := strings.TrimSpace(os.Getenv("LMOCI_SIM_KEY_ID"))
	tenancy := firstNonEmpty(os.Getenv("LMOCI_TENANCY"), os.Getenv("OCI_CLI_TENANCY"), DefaultTenancyID)
	user := firstNonEmpty(os.Getenv("LMOCI_USER"), os.Getenv("OCI_CLI_USER"), DefaultUserID)
	fingerprint := firstNonEmpty(os.Getenv("LMOCI_FINGERPRINT"), os.Getenv("OCI_CLI_FINGERPRINT"), DefaultFingerprint)

	keyID := simKeyID
	if keyID != "" {
		parts := strings.Split(keyID, "/")
		if len(parts) >= 1 && strings.HasPrefix(parts[0], "ocid1.tenancy.") {
			tenancy = parts[0]
		}
	} else {
		keyID = fmt.Sprintf("%s/%s/%s", tenancy, user, fingerprint)
	}

	var pemData []byte
	keyFile := firstNonEmpty(
		os.Getenv("LMOCI_SIM_PRIVATE_KEY_PATH"),
		os.Getenv("LMOCI_PRIVATE_KEY_FILE"),
		os.Getenv("OCI_CLI_KEY_FILE"),
	)
	if keyFile != "" {
		b, err := os.ReadFile(keyFile)
		if err != nil {
			return nil, tenancy, newCloudError(CodeUnauthenticated, 0, "InvalidPrivateKey",
				fmt.Sprintf("failed to read OCI private key file: %v", err))
		}
		pemData = b
	} else if inline := firstNonEmpty(os.Getenv("LMOCI_PRIVATE_KEY"), os.Getenv("OCI_CLI_KEY_CONTENT")); inline != "" {
		pemData = []byte(inline)
	}

	var rsaKey *rsa.PrivateKey
	if len(pemData) > 0 {
		k, err := parseRSAPrivateKeyPEM(pemData)
		if err != nil {
			return nil, tenancy, newCloudError(CodeUnauthenticated, 0, "InvalidPrivateKey", err.Error())
		}
		rsaKey = k
	} else if loopback {
		k, err := getEphemeralRSAKey()
		if err != nil {
			return nil, tenancy, newCloudError(CodeInternal, 0, "KeyGenError", err.Error())
		}
		rsaKey = k
	} else {
		return nil, tenancy, newCloudError(CodeUnauthenticated, 0, "MissingCredentials",
			"no OCI private key configured for non-loopback endpoint")
	}

	return &ociStaticKeyProvider{
		keyID: keyID,
		key:   rsaKey,
	}, tenancy, nil
}

func (h *Host) resolveCompartmentID(defaultTenancy string) string {
	return firstNonEmpty(os.Getenv("LMOCI_COMPARTMENT_ID"), os.Getenv("OCI_COMPARTMENT_ID"), defaultTenancy)
}

// doSignedRequest signs and executes an OCI HTTP request against baseEndpoint.
func (h *Host) doSignedRequest(
	method, baseEndpoint, rawPath, rawQuery string,
	body []byte,
	contentType string,
	extraHeaders map[string]string,
) (int, http.Header, []byte, *CloudError) {
	provider, _, cerr := h.resolveCredentials(baseEndpoint)
	if cerr != nil {
		return 0, nil, nil, cerr
	}

	parsedBase, err := url.Parse(baseEndpoint)
	if err != nil {
		return 0, nil, nil, newCloudError(CodeInvalidArgument, 0, "InvalidEndpoint", err.Error())
	}

	if !strings.HasPrefix(rawPath, "/") {
		rawPath = "/" + rawPath
	}
	unescapedPath, err := url.PathUnescape(rawPath)
	if err != nil {
		unescapedPath = rawPath
	}

	reqURL := &url.URL{
		Scheme:   parsedBase.Scheme,
		Host:     parsedBase.Host,
		Path:     unescapedPath,
		RawPath:  rawPath,
		RawQuery: rawQuery,
	}

	var bodyReader io.Reader
	if body != nil {
		bodyReader = bytes.NewReader(body)
	}

	req, err := http.NewRequest(method, reqURL.String(), bodyReader)
	if err != nil {
		return 0, nil, nil, newCloudError(CodeInternal, 0, "RequestBuildError", err.Error())
	}
	req.URL = reqURL
	req.Host = parsedBase.Host
	req.Header.Set("Date", time.Now().UTC().Format(http.TimeFormat))

	if body != nil || method == http.MethodPost || method == http.MethodPut || method == http.MethodPatch {
		if contentType == "" {
			contentType = "application/json"
		}
		req.Header.Set("Content-Type", contentType)
		if body == nil {
			req.Body = io.NopCloser(bytes.NewReader(nil))
		}
	}

	for k, v := range extraHeaders {
		req.Header.Set(k, v)
	}

	signer := common.DefaultRequestSigner(provider)
	if err := signer.Sign(req); err != nil {
		return 0, nil, nil, newCloudError(CodeUnauthenticated, 0, "SignatureError", err.Error())
	}

	resp, err := h.httpClient.Do(req)
	if err != nil {
		return 0, nil, nil, newCloudError(CodeUnavailable, 0, "NetworkError", safety.Redact(err.Error()))
	}
	defer resp.Body.Close()

	var respBytes []byte
	if method != http.MethodHead {
		limited := io.LimitReader(resp.Body, MaxObjectBodyBytes+4096)
		respBytes, err = io.ReadAll(limited)
		if err != nil {
			return resp.StatusCode, resp.Header, nil, newCloudError(CodeUnavailable, resp.StatusCode, "ReadError", safety.Redact(err.Error()))
		}
	}

	return resp.StatusCode, resp.Header, respBytes, nil
}

type ociErrorPayload struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func mapOCIHTTPError(status int, respBody []byte, isHead bool, defaultNotFoundCode string) *CloudError {
	var parsed ociErrorPayload
	if len(respBody) > 0 {
		_ = json.Unmarshal(respBody, &parsed)
	}
	pcode := strings.TrimSpace(parsed.Code)
	msg := strings.TrimSpace(parsed.Message)

	switch status {
	case http.StatusUnauthorized:
		if pcode == "" {
			pcode = "NotAuthenticated"
		}
		if msg == "" {
			msg = "authentication failed"
		}
		return newCloudError(CodeUnauthenticated, status, pcode, msg)

	case http.StatusForbidden:
		if pcode == "" {
			pcode = "NotAuthorizedOrNotFound"
		}
		if msg == "" {
			msg = "permission denied"
		}
		return newCloudError(CodePermissionDenied, status, pcode, msg)

	case http.StatusNotFound:
		// H1: Real OCI returns 404 NotAuthorizedOrNotFound when caller lacks inspect permission.
		// Map 404 + NotAuthorizedOrNotFound to PERMISSION_DENIED (never NOT_FOUND or exists==false).
		if strings.EqualFold(pcode, "NotAuthorizedOrNotFound") {
			if msg == "" {
				msg = "authorization failed or requested resource not found"
			}
			return newCloudError(CodePermissionDenied, status, pcode, msg)
		}
		if pcode == "" {
			pcode = defaultNotFoundCode
		}
		if msg == "" {
			msg = "resource not found"
		}
		return newCloudError(CodeNotFound, status, pcode, msg)

	case http.StatusConflict:
		if strings.EqualFold(pcode, "BucketAlreadyExists") || strings.EqualFold(pcode, "NamespaceBucketAlreadyExists") || strings.EqualFold(pcode, "QueueAlreadyExists") {
			if msg == "" {
				msg = "resource already exists"
			}
			return newCloudError(CodeAlreadyExists, status, pcode, msg)
		}
		if pcode == "" {
			pcode = "Conflict"
		}
		if msg == "" {
			msg = "resource state conflict"
		}
		return newCloudError(CodeConflict, status, pcode, msg)

	case http.StatusPreconditionFailed:
		if pcode == "" {
			pcode = "PreconditionFailed"
		}
		if msg == "" {
			msg = "precondition failed"
		}
		return newCloudError(CodePreconditionFailed, status, pcode, msg)

	case http.StatusTooManyRequests:
		if pcode == "" {
			pcode = "TooManyRequests"
		}
		if msg == "" {
			msg = "rate limit exceeded"
		}
		return newCloudError(CodeRateLimited, status, pcode, msg)

	case http.StatusBadRequest:
		if pcode == "" {
			pcode = "InvalidParameter"
		}
		if msg == "" {
			msg = "invalid argument"
		}
		return newCloudError(CodeInvalidArgument, status, pcode, msg)

	case http.StatusBadGateway, http.StatusServiceUnavailable, http.StatusGatewayTimeout:
		if pcode == "" {
			pcode = "ServiceUnavailable"
		}
		if msg == "" {
			msg = "service unavailable"
		}
		return newCloudError(CodeUnavailable, status, pcode, msg)

	default:
		if pcode == "" {
			pcode = fmt.Sprintf("HTTP_%d", status)
		}
		if msg == "" {
			msg = fmt.Sprintf("unexpected HTTP status %d", status)
		}
		return newCloudError(CodeInternal, status, pcode, msg)
	}
}
