package host

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strconv"
	"strings"

	"github.com/oracle/oci-go-sdk/v65/objectstorage"
)

func (h *Host) resolveNamespaceLocked(baseEndpoint string) (string, *CloudError) {
	if ns := firstNonEmpty(os.Getenv("LMOCI_NAMESPACE"), os.Getenv("OCI_NAMESPACE")); ns != "" {
		return ns, nil
	}
	if h.cachedNamespace != "" {
		return h.cachedNamespace, nil
	}

	status, _, respBytes, cerr := h.doSignedRequest(http.MethodGet, baseEndpoint, "/n/", "", nil, "", nil)
	if cerr != nil {
		return "", cerr
	}
	if status == http.StatusOK {
		var ns string
		if err := json.Unmarshal(respBytes, &ns); err == nil && strings.TrimSpace(ns) != "" {
			h.cachedNamespace = strings.TrimSpace(ns)
			return h.cachedNamespace, nil
		}
		trimmed := strings.Trim(strings.TrimSpace(string(respBytes)), `"`)
		if trimmed != "" {
			h.cachedNamespace = trimmed
			return h.cachedNamespace, nil
		}
	}

	// If talking to a loopback simulator where GetNamespace might be restricted by a bucket-scoped policy,
	// fall back to DefaultNamespace ("floci-local") so the actual bucket/object request is evaluated by the simulator.
	if u, err := url.Parse(baseEndpoint); err == nil && isLoopbackHost(u.Hostname()) {
		if status == http.StatusUnauthorized || status == http.StatusForbidden {
			return DefaultNamespace, nil
		}
	}

	return "", mapOCIHTTPError(status, respBytes, false, "NamespaceNotFound")
}

// checkBucketExistsLocked sends GET /n/{ns}/b/{bucket} to verify bucket existence or auth/permission error.
func (h *Host) checkBucketExistsLocked(baseEndpoint, ns, bucket string) (bool, *CloudError) {
	rawPath := fmt.Sprintf("/n/%s/b/%s", encodeRFC3986Unreserved(ns), encodeRFC3986Unreserved(bucket))
	status, _, respBytes, cerr := h.doSignedRequest(http.MethodGet, baseEndpoint, rawPath, "", nil, "", nil)
	if cerr != nil {
		return false, cerr
	}
	if status == http.StatusOK {
		return true, nil
	}
	mapped := mapOCIHTTPError(status, respBytes, false, "BucketNotFound")
	if mapped.Code == CodeNotFound {
		return false, nil
	}
	return false, mapped
}

// BucketCreate creates a bucket idempotently: returns true if newly created, false if already owned by caller.
func (h *Host) BucketCreate(bucket string) (bool, error) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if cerr := ValidateBucketName(bucket); cerr != nil {
		return false, h.finishErrorLocked("bucket_create", bucket, cerr)
	}

	ep, useFake, cerr := h.resolveBackendEndpointLocked("storage")
	if cerr != nil {
		return false, h.finishErrorLocked("bucket_create", bucket, cerr)
	}
	h.recordEntry("storage", "objectstorage", "CreateBucket", bucket)

	if useFake {
		if _, exists := h.fakeBuckets[bucket]; exists {
			h.finishOKLocked()
			return false, nil
		}
		h.fakeBuckets[bucket] = make(map[string]*fakeObject)
		h.finishOKLocked()
		return true, nil
	}

	ns, cerr := h.resolveNamespaceLocked(ep)
	if cerr != nil {
		return false, h.finishErrorLocked("bucket_create", bucket, cerr)
	}
	_, tenancy, cerr := h.resolveCredentials(ep)
	if cerr != nil {
		return false, h.finishErrorLocked("bucket_create", bucket, cerr)
	}
	compartmentID := h.resolveCompartmentID(tenancy)

	details := objectstorage.CreateBucketDetails{
		Name:          &bucket,
		CompartmentId: &compartmentID,
	}
	payload, err := json.Marshal(details)
	if err != nil {
		return false, h.finishErrorLocked("bucket_create", bucket, newCloudError(CodeInternal, 0, "MarshalError", err.Error()))
	}

	rawPath := fmt.Sprintf("/n/%s/b/", encodeRFC3986Unreserved(ns))
	status, _, respBytes, cerr := h.doSignedRequest(http.MethodPost, ep, rawPath, "", payload, "application/json", nil)
	if cerr != nil {
		return false, h.finishErrorLocked("bucket_create", bucket, cerr)
	}
	if status == http.StatusOK || status == http.StatusCreated {
		h.finishOKLocked()
		return true, nil
	}
	// In OCI, namespaces are tenancy-scoped, so 409 BucketAlreadyExists in our namespace means already owned by caller.
	if status == http.StatusConflict {
		h.finishOKLocked()
		return false, nil
	}
	return false, h.finishErrorLocked("bucket_create", bucket, mapOCIHTTPError(status, respBytes, false, "BucketNotFound"))
}

// BucketDelete deletes an empty bucket idempotently: returns true if deleted, false if already absent.
// Fails with ErrorCode.CONFLICT if the bucket is non-empty.
func (h *Host) BucketDelete(bucket string) (bool, error) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if cerr := ValidateBucketName(bucket); cerr != nil {
		return false, h.finishErrorLocked("bucket_delete", bucket, cerr)
	}

	ep, useFake, cerr := h.resolveBackendEndpointLocked("storage")
	if cerr != nil {
		return false, h.finishErrorLocked("bucket_delete", bucket, cerr)
	}
	h.recordEntry("storage", "objectstorage", "DeleteBucket", bucket)

	if useFake {
		objs, exists := h.fakeBuckets[bucket]
		if !exists {
			h.finishOKLocked()
			return false, nil
		}
		if len(objs) > 0 {
			return false, h.finishErrorLocked("bucket_delete", bucket,
				newCloudError(CodeConflict, http.StatusConflict, "BucketNotEmpty", "bucket is not empty"))
		}
		delete(h.fakeBuckets, bucket)
		h.finishOKLocked()
		return true, nil
	}

	ns, cerr := h.resolveNamespaceLocked(ep)
	if cerr != nil {
		return false, h.finishErrorLocked("bucket_delete", bucket, cerr)
	}

	// Pre-check whether bucket has any objects so non-empty guard is guaranteed across sim and live OCI.
	listPath := fmt.Sprintf("/n/%s/b/%s/o", encodeRFC3986Unreserved(ns), encodeRFC3986Unreserved(bucket))
	lStatus, _, lBytes, lCerr := h.doSignedRequest(http.MethodGet, ep, listPath, "limit=1", nil, "", nil)
	if lCerr != nil {
		return false, h.finishErrorLocked("bucket_delete", bucket, lCerr)
	}
	if lStatus == http.StatusOK {
		var listResp objectstorage.ListObjects
		if err := json.Unmarshal(lBytes, &listResp); err == nil && len(listResp.Objects) > 0 {
			return false, h.finishErrorLocked("bucket_delete", bucket,
				newCloudError(CodeConflict, http.StatusConflict, "BucketNotEmpty", "bucket is not empty"))
		}
	} else {
		mapped := mapOCIHTTPError(lStatus, lBytes, false, "BucketNotFound")
		if mapped.Code == CodeNotFound {
			h.finishOKLocked()
			return false, nil
		}
		return false, h.finishErrorLocked("bucket_delete", bucket, mapped)
	}

	delPath := fmt.Sprintf("/n/%s/b/%s", encodeRFC3986Unreserved(ns), encodeRFC3986Unreserved(bucket))
	status, _, respBytes, cerr := h.doSignedRequest(http.MethodDelete, ep, delPath, "", nil, "", nil)
	if cerr != nil {
		return false, h.finishErrorLocked("bucket_delete", bucket, cerr)
	}
	if status == http.StatusOK || status == http.StatusNoContent {
		h.finishOKLocked()
		return true, nil
	}
	mapped := mapOCIHTTPError(status, respBytes, false, "BucketNotFound")
	if mapped.Code == CodeNotFound {
		h.finishOKLocked()
		return false, nil
	}
	return false, h.finishErrorLocked("bucket_delete", bucket, mapped)
}

// BucketExists probes whether bucket exists without failing on 404 BucketNotFound.
func (h *Host) BucketExists(bucket string) (bool, error) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if cerr := ValidateBucketName(bucket); cerr != nil {
		return false, h.finishErrorLocked("bucket_exists", bucket, cerr)
	}

	ep, useFake, cerr := h.resolveBackendEndpointLocked("storage")
	if cerr != nil {
		return false, h.finishErrorLocked("bucket_exists", bucket, cerr)
	}
	h.recordEntry("storage", "objectstorage", "HeadBucket", bucket)

	if useFake {
		_, exists := h.fakeBuckets[bucket]
		h.finishOKLocked()
		return exists, nil
	}

	ns, cerr := h.resolveNamespaceLocked(ep)
	if cerr != nil {
		return false, h.finishErrorLocked("bucket_exists", bucket, cerr)
	}

	exists, cerr := h.checkBucketExistsLocked(ep, ns, bucket)
	if cerr != nil {
		return false, h.finishErrorLocked("bucket_exists", bucket, cerr)
	}
	h.finishOKLocked()
	return exists, nil
}

// BucketList lists all buckets in the compartment sorted in ascending UTF-8 byte order.
func (h *Host) BucketList() ([]string, error) {
	h.mu.Lock()
	defer h.mu.Unlock()

	ep, useFake, cerr := h.resolveBackendEndpointLocked("storage")
	if cerr != nil {
		return []string{}, h.finishErrorLocked("bucket_list", "", cerr)
	}
	h.recordEntry("storage", "objectstorage", "ListBuckets", "")

	if useFake {
		names := make([]string, 0, len(h.fakeBuckets))
		for name := range h.fakeBuckets {
			names = append(names, name)
		}
		sort.Strings(names)
		h.finishOKLocked()
		return names, nil
	}

	ns, cerr := h.resolveNamespaceLocked(ep)
	if cerr != nil {
		return []string{}, h.finishErrorLocked("bucket_list", "", cerr)
	}
	_, tenancy, cerr := h.resolveCredentials(ep)
	if cerr != nil {
		return []string{}, h.finishErrorLocked("bucket_list", "", cerr)
	}
	compartmentID := h.resolveCompartmentID(tenancy)

	rawPath := fmt.Sprintf("/n/%s/b/", encodeRFC3986Unreserved(ns))
	rawQuery := "compartmentId=" + url.QueryEscape(compartmentID)
	status, _, respBytes, cerr := h.doSignedRequest(http.MethodGet, ep, rawPath, rawQuery, nil, "", nil)
	if cerr != nil {
		return []string{}, h.finishErrorLocked("bucket_list", "", cerr)
	}
	if status != http.StatusOK {
		return []string{}, h.finishErrorLocked("bucket_list", "", mapOCIHTTPError(status, respBytes, false, "BucketNotFound"))
	}

	var summaries []objectstorage.BucketSummary
	if err := json.Unmarshal(respBytes, &summaries); err != nil {
		return []string{}, h.finishErrorLocked("bucket_list", "", newCloudError(CodeInternal, status, "UnmarshalError", err.Error()))
	}
	names := make([]string, 0, len(summaries))
	for _, s := range summaries {
		if s.Name != nil {
			names = append(names, *s.Name)
		}
	}
	sort.Strings(names)
	h.finishOKLocked()
	return names, nil
}

func computeSHA256Hex(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:16])
}

// ObjectPut uploads or overwrites an object and returns its normalized ObjectMeta.
func (h *Host) ObjectPut(bucket, key, body, contentType string, metadata map[string]string) (*ObjectMeta, error) {
	h.mu.Lock()
	defer h.mu.Unlock()

	target := bucket + "/" + key
	if cerr := ValidateBucketName(bucket); cerr != nil {
		return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_put", target, cerr)
	}
	if cerr := ValidateObjectKey(key); cerr != nil {
		return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_put", target, cerr)
	}
	if len(body) > MaxObjectBodyBytes {
		return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_put", target,
			newCloudError(CodeInvalidArgument, 0, "EntityTooLarge",
				fmt.Sprintf("object body length %d exceeds 32 MiB limit", len(body))))
	}
	if cerr := ValidateContentType(contentType); cerr != nil {
		return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_put", target, cerr)
	}
	if cerr := ValidateMetadata(metadata); cerr != nil {
		return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_put", target, cerr)
	}

	ep, useFake, cerr := h.resolveBackendEndpointLocked("storage")
	if cerr != nil {
		return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_put", target, cerr)
	}
	h.recordEntry("storage", "objectstorage", "PutObject", target)

	metaCopy := make(map[string]string, len(metadata))
	for k, v := range metadata {
		metaCopy[k] = v
	}

	if useFake {
		objs, exists := h.fakeBuckets[bucket]
		if !exists {
			return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_put", target,
				newCloudError(CodeNotFound, http.StatusNotFound, "BucketNotFound", "bucket does not exist"))
		}
		rawBytes := []byte(body)
		etag := computeSHA256Hex(rawBytes)
		objs[key] = &fakeObject{
			data:        rawBytes,
			contentType: contentType,
			etag:        etag,
			metadata:    metaCopy,
		}
		h.finishOKLocked()
		return &ObjectMeta{
			Exists:      true,
			Bucket:      bucket,
			Key:         key,
			Size:        int64(len(rawBytes)),
			ETag:        etag,
			ContentType: contentType,
			Metadata:    metaCopy,
		}, nil
	}

	ns, cerr := h.resolveNamespaceLocked(ep)
	if cerr != nil {
		return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_put", target, cerr)
	}

	extraHeaders := make(map[string]string, len(metaCopy))
	for k, v := range metaCopy {
		extraHeaders["opc-meta-"+k] = v
	}

	rawPath := fmt.Sprintf("/n/%s/b/%s/o/%s",
		encodeRFC3986Unreserved(ns),
		encodeRFC3986Unreserved(bucket),
		EncodeObjectKeyPath(key),
	)
	rawBytes := []byte(body)
	status, headers, respBytes, cerr := h.doSignedRequest(http.MethodPut, ep, rawPath, "", rawBytes, contentType, extraHeaders)
	if cerr != nil {
		return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_put", target, cerr)
	}
	if status != http.StatusOK && status != http.StatusCreated {
		return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_put", target, mapOCIHTTPError(status, respBytes, false, "BucketNotFound"))
	}

	etag := NormalizeETag(headers.Get("ETag"))
	if etag == "" {
		etag = computeSHA256Hex(rawBytes)
	}

	h.finishOKLocked()
	return &ObjectMeta{
		Exists:      true,
		Bucket:      bucket,
		Key:         key,
		Size:        int64(len(rawBytes)),
		ETag:        etag,
		ContentType: contentType,
		Metadata:    metaCopy,
	}, nil
}

// ObjectGet downloads an object's byte-transparent payload.
func (h *Host) ObjectGet(bucket, key string) (string, error) {
	h.mu.Lock()
	defer h.mu.Unlock()

	target := bucket + "/" + key
	if cerr := ValidateBucketName(bucket); cerr != nil {
		return "", h.finishErrorLocked("object_get", target, cerr)
	}
	if cerr := ValidateObjectKey(key); cerr != nil {
		return "", h.finishErrorLocked("object_get", target, cerr)
	}

	ep, useFake, cerr := h.resolveBackendEndpointLocked("storage")
	if cerr != nil {
		return "", h.finishErrorLocked("object_get", target, cerr)
	}
	h.recordEntry("storage", "objectstorage", "GetObject", target)

	if useFake {
		objs, exists := h.fakeBuckets[bucket]
		if !exists {
			return "", h.finishErrorLocked("object_get", target,
				newCloudError(CodeNotFound, http.StatusNotFound, "BucketNotFound", "bucket does not exist"))
		}
		obj, ok := objs[key]
		if !ok {
			return "", h.finishErrorLocked("object_get", target,
				newCloudError(CodeNotFound, http.StatusNotFound, "ObjectNotFound", "object does not exist"))
		}
		h.finishOKLocked()
		return string(obj.data), nil
	}

	ns, cerr := h.resolveNamespaceLocked(ep)
	if cerr != nil {
		return "", h.finishErrorLocked("object_get", target, cerr)
	}

	rawPath := fmt.Sprintf("/n/%s/b/%s/o/%s",
		encodeRFC3986Unreserved(ns),
		encodeRFC3986Unreserved(bucket),
		EncodeObjectKeyPath(key),
	)
	status, _, respBytes, cerr := h.doSignedRequest(http.MethodGet, ep, rawPath, "", nil, "", nil)
	if cerr != nil {
		return "", h.finishErrorLocked("object_get", target, cerr)
	}
	if status != http.StatusOK {
		return "", h.finishErrorLocked("object_get", target, mapOCIHTTPError(status, respBytes, false, "ObjectNotFound"))
	}

	h.finishOKLocked()
	return string(respBytes), nil
}

// ObjectHead retrieves normalized object metadata without downloading the body.
// If the bucket exists and the object is missing, returns Exists==false with ErrorCode.OK.
// If the bucket itself does not exist, fails with ErrorCode.NOT_FOUND.
func (h *Host) ObjectHead(bucket, key string) (*ObjectMeta, error) {
	h.mu.Lock()
	defer h.mu.Unlock()

	target := bucket + "/" + key
	if cerr := ValidateBucketName(bucket); cerr != nil {
		return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_head", target, cerr)
	}
	if cerr := ValidateObjectKey(key); cerr != nil {
		return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_head", target, cerr)
	}

	ep, useFake, cerr := h.resolveBackendEndpointLocked("storage")
	if cerr != nil {
		return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_head", target, cerr)
	}
	h.recordEntry("storage", "objectstorage", "HeadObject", target)

	if useFake {
		objs, exists := h.fakeBuckets[bucket]
		if !exists {
			return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_head", target,
				newCloudError(CodeNotFound, http.StatusNotFound, "BucketNotFound", "bucket does not exist"))
		}
		obj, ok := objs[key]
		if !ok {
			h.finishOKLocked()
			return notFoundObjectMeta(bucket, key), nil
		}
		metaCopy := make(map[string]string, len(obj.metadata))
		for k, v := range obj.metadata {
			metaCopy[k] = v
		}
		h.finishOKLocked()
		return &ObjectMeta{
			Exists:      true,
			Bucket:      bucket,
			Key:         key,
			Size:        int64(len(obj.data)),
			ETag:        obj.etag,
			ContentType: obj.contentType,
			Metadata:    metaCopy,
		}, nil
	}

	ns, cerr := h.resolveNamespaceLocked(ep)
	if cerr != nil {
		return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_head", target, cerr)
	}

	rawPath := fmt.Sprintf("/n/%s/b/%s/o/%s",
		encodeRFC3986Unreserved(ns),
		encodeRFC3986Unreserved(bucket),
		EncodeObjectKeyPath(key),
	)
	status, headers, respBytes, cerr := h.doSignedRequest(http.MethodHead, ep, rawPath, "", nil, "", nil)
	if cerr != nil {
		return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_head", target, cerr)
	}
	if status == http.StatusOK {
		size := int64(0)
		if cl := headers.Get("Content-Length"); cl != "" {
			if parsed, err := strconv.ParseInt(strings.TrimSpace(cl), 10, 64); err == nil && parsed >= 0 {
				size = parsed
			}
		}
		etag := NormalizeETag(headers.Get("ETag"))
		ct := strings.TrimSpace(headers.Get("Content-Type"))
		if ct == "" {
			ct = "application/octet-stream"
		}
		meta := make(map[string]string)
		for hk, hvals := range headers {
			lower := strings.ToLower(hk)
			if strings.HasPrefix(lower, "opc-meta-") && len(hvals) > 0 {
				userKey := strings.TrimPrefix(lower, "opc-meta-")
				if userKey != "" {
					meta[userKey] = hvals[0]
				}
			}
		}
		h.finishOKLocked()
		return &ObjectMeta{
			Exists:      true,
			Bucket:      bucket,
			Key:         key,
			Size:        size,
			ETag:        etag,
			ContentType: ct,
			Metadata:    meta,
		}, nil
	}

	if status == http.StatusNotFound {
		// Distinguish missing object in existing bucket (OK, Exists=false) vs missing bucket (NOT_FOUND) vs H1 permission denial.
		bExists, bCerr := h.checkBucketExistsLocked(ep, ns, bucket)
		if bCerr != nil {
			return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_head", target, bCerr)
		}
		if !bExists {
			return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_head", target,
				newCloudError(CodeNotFound, http.StatusNotFound, "BucketNotFound", "bucket does not exist"))
		}
		h.finishOKLocked()
		return notFoundObjectMeta(bucket, key), nil
	}

	return notFoundObjectMeta(bucket, key), h.finishErrorLocked("object_head", target, mapOCIHTTPError(status, respBytes, true, "ObjectNotFound"))
}

// ObjectDelete deletes an object idempotently if the bucket exists, or fails with NOT_FOUND if the bucket is absent.
func (h *Host) ObjectDelete(bucket, key string) error {
	h.mu.Lock()
	defer h.mu.Unlock()

	target := bucket + "/" + key
	if cerr := ValidateBucketName(bucket); cerr != nil {
		return h.finishErrorLocked("object_delete", target, cerr)
	}
	if cerr := ValidateObjectKey(key); cerr != nil {
		return h.finishErrorLocked("object_delete", target, cerr)
	}

	ep, useFake, cerr := h.resolveBackendEndpointLocked("storage")
	if cerr != nil {
		return h.finishErrorLocked("object_delete", target, cerr)
	}
	h.recordEntry("storage", "objectstorage", "DeleteObject", target)

	if useFake {
		objs, exists := h.fakeBuckets[bucket]
		if !exists {
			return h.finishErrorLocked("object_delete", target,
				newCloudError(CodeNotFound, http.StatusNotFound, "BucketNotFound", "bucket does not exist"))
		}
		delete(objs, key)
		h.finishOKLocked()
		return nil
	}

	ns, cerr := h.resolveNamespaceLocked(ep)
	if cerr != nil {
		return h.finishErrorLocked("object_delete", target, cerr)
	}

	rawPath := fmt.Sprintf("/n/%s/b/%s/o/%s",
		encodeRFC3986Unreserved(ns),
		encodeRFC3986Unreserved(bucket),
		EncodeObjectKeyPath(key),
	)
	status, _, respBytes, cerr := h.doSignedRequest(http.MethodDelete, ep, rawPath, "", nil, "", nil)
	if cerr != nil {
		return h.finishErrorLocked("object_delete", target, cerr)
	}
	if status == http.StatusOK || status == http.StatusNoContent {
		h.finishOKLocked()
		return nil
	}
	if status == http.StatusNotFound {
		var parsed ociErrorPayload
		_ = json.Unmarshal(respBytes, &parsed)
		if strings.EqualFold(parsed.Code, "NotAuthorizedOrNotFound") {
			return h.finishErrorLocked("object_delete", target,
				newCloudError(CodePermissionDenied, status, parsed.Code, parsed.Message))
		}
		if strings.EqualFold(parsed.Code, "BucketNotFound") {
			return h.finishErrorLocked("object_delete", target,
				newCloudError(CodeNotFound, status, "BucketNotFound", "bucket does not exist"))
		}
		// Verify bucket exists so deleting a missing key in an existing bucket is idempotent OK,
		// while deleting from a missing bucket returns NOT_FOUND.
		bExists, bCerr := h.checkBucketExistsLocked(ep, ns, bucket)
		if bCerr != nil {
			return h.finishErrorLocked("object_delete", target, bCerr)
		}
		if !bExists {
			return h.finishErrorLocked("object_delete", target,
				newCloudError(CodeNotFound, http.StatusNotFound, "BucketNotFound", "bucket does not exist"))
		}
		h.finishOKLocked()
		return nil
	}
	return h.finishErrorLocked("object_delete", target, mapOCIHTTPError(status, respBytes, false, "ObjectNotFound"))
}

// ObjectList lists a single page of objects matching prefix in ascending UTF-8 byte order.
func (h *Host) ObjectList(bucket, prefix string, maxKeys int64, pageToken string) (*ObjectListPage, error) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if cerr := ValidateBucketName(bucket); cerr != nil {
		return emptyObjectListPage(), h.finishErrorLocked("object_list", bucket, cerr)
	}
	if cerr := ValidateListPrefix(prefix); cerr != nil {
		return emptyObjectListPage(), h.finishErrorLocked("object_list", bucket, cerr)
	}
	if cerr := ValidateMaxKeys(maxKeys); cerr != nil {
		return emptyObjectListPage(), h.finishErrorLocked("object_list", bucket, cerr)
	}
	effectiveMax := int(maxKeys)
	if effectiveMax == 0 {
		effectiveMax = 1000
	}

	ep, useFake, cerr := h.resolveBackendEndpointLocked("storage")
	if cerr != nil {
		return emptyObjectListPage(), h.finishErrorLocked("object_list", bucket, cerr)
	}
	h.recordEntry("storage", "objectstorage", "ListObjects", bucket)

	if useFake {
		objs, exists := h.fakeBuckets[bucket]
		if !exists {
			return emptyObjectListPage(), h.finishErrorLocked("object_list", bucket,
				newCloudError(CodeNotFound, http.StatusNotFound, "BucketNotFound", "bucket does not exist"))
		}
		all := make([]*ObjectSummary, 0, len(objs))
		for k, obj := range objs {
			if strings.HasPrefix(k, prefix) && (pageToken == "" || k >= pageToken) {
				all = append(all, &ObjectSummary{
					Key:  k,
					Size: int64(len(obj.data)),
					ETag: obj.etag,
				})
			}
		}
		sort.Slice(all, func(i, j int) bool {
			return all[i].Key < all[j].Key
		})
		hasMore := false
		nextTok := ""
		if len(all) > effectiveMax {
			hasMore = true
			nextTok = all[effectiveMax].Key
			all = all[:effectiveMax]
		}
		h.finishOKLocked()
		return &ObjectListPage{
			Items:     all,
			HasMore:   hasMore,
			NextToken: nextTok,
		}, nil
	}

	page, cerr := h.objectListRemoteLocked(ep, bucket, prefix, effectiveMax, pageToken)
	if cerr != nil {
		return emptyObjectListPage(), h.finishErrorLocked("object_list", bucket, cerr)
	}
	h.finishOKLocked()
	return page, nil
}

func (h *Host) objectListRemoteLocked(ep, bucket, prefix string, effectiveMax int, pageToken string) (*ObjectListPage, *CloudError) {
	ns, cerr := h.resolveNamespaceLocked(ep)
	if cerr != nil {
		return nil, cerr
	}

	rawPath := fmt.Sprintf("/n/%s/b/%s/o", encodeRFC3986Unreserved(ns), encodeRFC3986Unreserved(bucket))
	params := []string{
		"fields=" + url.QueryEscape("name,size,etag"),
		"limit=" + strconv.Itoa(effectiveMax),
	}
	if prefix != "" {
		params = append(params, "prefix="+url.QueryEscape(prefix))
	}
	if pageToken != "" {
		params = append(params, "start="+url.QueryEscape(pageToken))
	}
	rawQuery := strings.Join(params, "&")

	status, _, respBytes, cerr := h.doSignedRequest(http.MethodGet, ep, rawPath, rawQuery, nil, "", nil)
	if cerr != nil {
		return nil, cerr
	}
	if status != http.StatusOK {
		return nil, mapOCIHTTPError(status, respBytes, false, "BucketNotFound")
	}

	var listResp objectstorage.ListObjects
	if err := json.Unmarshal(respBytes, &listResp); err != nil {
		return nil, newCloudError(CodeInternal, status, "UnmarshalError", err.Error())
	}

	summaries := make([]*ObjectSummary, 0, len(listResp.Objects))
	for _, o := range listResp.Objects {
		if o.Name == nil {
			continue
		}
		k := *o.Name
		if prefix != "" && !strings.HasPrefix(k, prefix) {
			continue
		}
		if pageToken != "" && k < pageToken {
			continue
		}
		sz := int64(0)
		if o.Size != nil {
			sz = *o.Size
		}
		etag := ""
		if o.Etag != nil {
			etag = NormalizeETag(*o.Etag)
		}
		summaries = append(summaries, &ObjectSummary{
			Key:  k,
			Size: sz,
			ETag: etag,
		})
	}
	sort.Slice(summaries, func(i, j int) bool {
		return summaries[i].Key < summaries[j].Key
	})

	nextTok := ""
	if listResp.NextStartWith != nil {
		nextTok = *listResp.NextStartWith
	}
	if len(summaries) > effectiveMax {
		nextTok = summaries[effectiveMax].Key
		summaries = summaries[:effectiveMax]
	}
	hasMore := nextTok != ""

	return &ObjectListPage{
		Items:     summaries,
		HasMore:   hasMore,
		NextToken: nextTok,
	}, nil
}

// ObjectListAll follows all pages and returns all matching object keys in ascending UTF-8 byte order.
func (h *Host) ObjectListAll(bucket, prefix string) ([]string, error) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if cerr := ValidateBucketName(bucket); cerr != nil {
		return []string{}, h.finishErrorLocked("object_list_all", bucket, cerr)
	}
	if cerr := ValidateListPrefix(prefix); cerr != nil {
		return []string{}, h.finishErrorLocked("object_list_all", bucket, cerr)
	}

	ep, useFake, cerr := h.resolveBackendEndpointLocked("storage")
	if cerr != nil {
		return []string{}, h.finishErrorLocked("object_list_all", bucket, cerr)
	}
	h.recordEntry("storage", "objectstorage", "ListObjectsAll", bucket)

	if useFake {
		objs, exists := h.fakeBuckets[bucket]
		if !exists {
			return []string{}, h.finishErrorLocked("object_list_all", bucket,
				newCloudError(CodeNotFound, http.StatusNotFound, "BucketNotFound", "bucket does not exist"))
		}
		keys := make([]string, 0, len(objs))
		for k := range objs {
			if strings.HasPrefix(k, prefix) {
				keys = append(keys, k)
			}
		}
		sort.Strings(keys)
		h.finishOKLocked()
		return keys, nil
	}

	var allKeys []string
	token := ""
	for pageIdx := 0; pageIdx < 10000; pageIdx++ {
		page, cerr := h.objectListRemoteLocked(ep, bucket, prefix, 1000, token)
		if cerr != nil {
			return []string{}, h.finishErrorLocked("object_list_all", bucket, cerr)
		}
		for _, item := range page.Items {
			allKeys = append(allKeys, item.Key)
		}
		if !page.HasMore || page.NextToken == "" || page.NextToken == token {
			break
		}
		token = page.NextToken
	}
	if allKeys == nil {
		allKeys = []string{}
	}
	h.finishOKLocked()
	return allKeys, nil
}
