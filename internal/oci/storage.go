// SPDX-License-Identifier: MIT

package oci

import (
	"context"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"github.com/lmctl-ai/lmcloud/hostkit"
)

func bucketPath(ns, b string) string {
	return "/n/" + hostkit.EscapeSegment(ns) + "/b/" + hostkit.EscapeSegment(b)
}

func objectPath(ns, b, k string) string {
	return bucketPath(ns, b) + "/o/" + hostkit.EscapeKeyPath(k)
}

func (r *remote) createBucket(ctx context.Context, base, b string) (bool, error) {
	ns, cerr := r.namespaceFor(ctx, base)
	if cerr != nil {
		return false, cerr
	}
	_, tenancy, cerr := r.credentials()
	if cerr != nil {
		return false, cerr
	}
	body, _ := jsonBody(map[string]string{"name": b, "compartmentId": r.compartment(tenancy)})
	resp, cerr := r.do(ctx, http.MethodPost, base, "/n/"+hostkit.EscapeSegment(ns)+"/b/", nil, body, "application/json", nil)
	if cerr != nil {
		return false, cerr
	}
	if ok(resp, http.StatusOK, http.StatusCreated) {
		return true, nil
	}
	// 409 BucketAlreadyExists: the host probes HeadBucket to decide owned vs foreign.
	return false, classify(resp, scopeBucket)
}

func (r *remote) deleteBucket(ctx context.Context, base, b string) error {
	ns, cerr := r.namespaceFor(ctx, base)
	if cerr != nil {
		return cerr
	}
	// OCI refuses to delete a non-empty bucket (409), so no pre-list is needed.
	resp, cerr := r.do(ctx, http.MethodDelete, base, bucketPath(ns, b), nil, nil, "", nil)
	if cerr != nil {
		return cerr
	}
	if ok(resp, http.StatusOK, http.StatusNoContent) {
		return nil
	}
	return classify(resp, scopeBucket)
}

// headBucket uses GET (not HEAD) so the JSON body distinguishes BucketNotFound
// from NotAuthorizedOrNotFound.
func (r *remote) headBucket(ctx context.Context, base, b string) error {
	ns, cerr := r.namespaceFor(ctx, base)
	if cerr != nil {
		return cerr
	}
	resp, cerr := r.do(ctx, http.MethodGet, base, bucketPath(ns, b), nil, nil, "", nil)
	if cerr != nil {
		return cerr
	}
	if resp.Status == http.StatusOK {
		return nil
	}
	return classify(resp, scopeBucket)
}

func (r *remote) listBuckets(ctx context.Context, base, token string) ([]string, string, error) {
	ns, cerr := r.namespaceFor(ctx, base)
	if cerr != nil {
		return nil, "", cerr
	}
	_, tenancy, cerr := r.credentials()
	if cerr != nil {
		return nil, "", cerr
	}
	q := url.Values{"compartmentId": {r.compartment(tenancy)}, "limit": {"1000"}}
	if token != "" {
		q.Set("page", token)
	}
	resp, cerr := r.do(ctx, http.MethodGet, base, "/n/"+hostkit.EscapeSegment(ns)+"/b/", q, nil, "", nil)
	if cerr != nil {
		return nil, "", cerr
	}
	if resp.Status != http.StatusOK {
		return nil, "", classify(resp, scopeNone)
	}
	var items []struct {
		Name string `json:"name"`
	}
	if cerr := decode(resp, &items); cerr != nil {
		return nil, "", cerr
	}
	names := make([]string, 0, len(items))
	for _, it := range items {
		if it.Name == "" {
			return nil, "", hostkit.ProviderError(hostkit.Internal, resp.Status, "", "bucket summary without a name")
		}
		names = append(names, it.Name)
	}
	return names, resp.Header.Get("opc-next-page"), nil
}

func (r *remote) putObject(ctx context.Context, base, b, k string, body []byte, ct string, meta map[string]string) (hostkit.ObjectMeta, error) {
	ns, cerr := r.namespaceFor(ctx, base)
	if cerr != nil {
		return hostkit.ObjectMeta{}, cerr
	}
	headers := make(map[string]string, len(meta))
	for mk, mv := range meta {
		headers["opc-meta-"+mk] = mv
	}
	resp, cerr := r.do(ctx, http.MethodPut, base, objectPath(ns, b, k), nil, body, ct, headers)
	if cerr != nil {
		return hostkit.ObjectMeta{}, cerr
	}
	if !ok(resp, http.StatusOK, http.StatusCreated) {
		return hostkit.ObjectMeta{}, classify(resp, scopeBucket)
	}
	etag := resp.Header.Get("ETag")
	if etag == "" {
		return hostkit.ObjectMeta{}, hostkit.ProviderError(hostkit.Internal, resp.Status, "", "PutObject response carried no ETag")
	}
	return hostkit.ObjectMeta{Exists: true, ETag: etag, ContentType: ct, Metadata: meta}, nil
}

func (r *remote) getObject(ctx context.Context, base, b, k string, limit int64) ([]byte, error) {
	ns, cerr := r.namespaceFor(ctx, base)
	if cerr != nil {
		return nil, cerr
	}
	resp, cerr := r.do(ctx, http.MethodGet, base, objectPath(ns, b, k), nil, nil, "", nil)
	if cerr != nil {
		return nil, cerr
	}
	if resp.Status != http.StatusOK {
		return nil, classify(resp, scopeObject)
	}
	if int64(len(resp.Body)) > limit {
		return nil, hostkit.ProviderError(hostkit.Internal, resp.Status, "", "object exceeds the read limit; refusing to truncate")
	}
	return resp.Body, nil
}

// headObject issues HEAD. OCI HEAD errors carry no body and (on ocisim) no
// opc-error-code, so a code-less 404 is re-issued as a one-byte ranged GET to
// learn whether it means ObjectNotFound, BucketNotFound or NotAuthorizedOrNotFound.
func (r *remote) headObject(ctx context.Context, base, b, k string) (hostkit.ObjectMeta, error) {
	ns, cerr := r.namespaceFor(ctx, base)
	if cerr != nil {
		return hostkit.ObjectMeta{}, cerr
	}
	path := objectPath(ns, b, k)
	resp, cerr := r.do(ctx, http.MethodHead, base, path, nil, nil, "", nil)
	if cerr != nil {
		return hostkit.ObjectMeta{}, cerr
	}
	if resp.Status == http.StatusNotFound && errorCode(resp) == "" {
		resp, cerr = r.do(ctx, http.MethodGet, base, path, nil, nil, "", map[string]string{"Range": "bytes=0-0"})
		if cerr != nil {
			return hostkit.ObjectMeta{}, cerr
		}
		if ok(resp, http.StatusOK, http.StatusPartialContent) {
			// It exists after all (race); report it via a fresh HEAD.
			return r.headObject(ctx, base, b, k)
		}
		if resp.Status == http.StatusNotFound && errorCode(resp) == "" {
			return hostkit.ObjectMeta{}, hostkit.ProviderError(hostkit.PermissionDenied, 404, "", "object HEAD returned an unclassifiable 404")
		}
	}
	if resp.Status != http.StatusOK {
		return hostkit.ObjectMeta{}, classify(resp, scopeObject)
	}
	size, err := strconv.ParseInt(resp.Header.Get("Content-Length"), 10, 64)
	if err != nil || size < 0 {
		return hostkit.ObjectMeta{}, hostkit.ProviderError(hostkit.Internal, resp.Status, "", "HeadObject response has no valid Content-Length")
	}
	meta := map[string]string{}
	for name, vals := range resp.Header {
		if l := strings.ToLower(name); strings.HasPrefix(l, "opc-meta-") && len(vals) > 0 {
			meta[strings.TrimPrefix(l, "opc-meta-")] = vals[0]
		}
	}
	return hostkit.ObjectMeta{Exists: true, Size: size, ETag: resp.Header.Get("ETag"), ContentType: resp.Header.Get("Content-Type"), Metadata: meta}, nil
}

func (r *remote) deleteObject(ctx context.Context, base, b, k string) error {
	ns, cerr := r.namespaceFor(ctx, base)
	if cerr != nil {
		return cerr
	}
	resp, cerr := r.do(ctx, http.MethodDelete, base, objectPath(ns, b, k), nil, nil, "", nil)
	if cerr != nil {
		return cerr
	}
	if ok(resp, http.StatusOK, http.StatusNoContent) {
		return nil
	}
	return classify(resp, scopeObject)
}

// listObjects uses OCI start keys: start is inclusive and nextStartWith names
// the first key of the next page, so it is a valid continuation token.
func (r *remote) listObjects(ctx context.Context, base, b, prefix string, max int, token string) (hostkit.ObjectPage, error) {
	ns, cerr := r.namespaceFor(ctx, base)
	if cerr != nil {
		return hostkit.ObjectPage{}, cerr
	}
	q := url.Values{"fields": {"name,size,etag"}, "limit": {strconv.Itoa(max)}}
	if prefix != "" {
		q.Set("prefix", prefix)
	}
	if token != "" {
		q.Set("start", token)
	}
	resp, cerr := r.do(ctx, http.MethodGet, base, bucketPath(ns, b)+"/o", q, nil, "", nil)
	if cerr != nil {
		return hostkit.ObjectPage{}, cerr
	}
	if resp.Status != http.StatusOK {
		return hostkit.ObjectPage{}, classify(resp, scopeBucket)
	}
	var out struct {
		Objects []struct {
			Name string `json:"name"`
			Size *int64 `json:"size"`
			ETag string `json:"etag"`
		} `json:"objects"`
		NextStartWith string `json:"nextStartWith"`
	}
	if cerr := decode(resp, &out); cerr != nil {
		return hostkit.ObjectPage{}, cerr
	}
	page := hostkit.ObjectPage{NextToken: out.NextStartWith}
	for _, o := range out.Objects {
		if o.Name == "" || o.Size == nil {
			return hostkit.ObjectPage{}, hostkit.ProviderError(hostkit.Internal, resp.Status, "", "object summary without name or size")
		}
		page.Items = append(page.Items, hostkit.ObjectSummary{Key: o.Name, Size: *o.Size, ETag: o.ETag})
	}
	return page, nil
}
