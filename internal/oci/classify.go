// SPDX-License-Identifier: MIT

package oci

import (
	"encoding/json"
	"net/http"
	"strings"

	"github.com/lmctl-ai/lmcloud/hostkit"
)

// scope tells classify which resource a 404 can name.
type scope int

const (
	scopeNone scope = iota
	scopeBucket
	scopeObject
	scopeQueue
	scopeMessage
)

// errorCode returns the OCI error code from the JSON body or the
// opc-error-code header (HEAD responses carry no body).
func errorCode(resp *response) string {
	var p struct {
		Code string `json:"code"`
	}
	if len(resp.Body) > 0 && json.Unmarshal(resp.Body, &p) == nil && p.Code != "" {
		return p.Code
	}
	return resp.Header.Get("opc-error-code")
}

func errorMessage(resp *response) string {
	var p struct {
		Message string `json:"message"`
	}
	if len(resp.Body) > 0 && json.Unmarshal(resp.Body, &p) == nil && p.Message != "" {
		return p.Message
	}
	return http.StatusText(resp.Status)
}

// inflightReceiptPrefix is ocisim's message for an unknown/stale receipt. It
// arrives as 404 NotAuthorizedOrNotFound; only this exact message on a message
// operation is treated as "message gone" (see go-port-findings receipts quirk).
const inflightReceiptPrefix = "No in-flight message for receipt"

// classify maps an OCI error response onto the shared enum (design 02 §5 H1):
//   - 401                                  -> UNAUTHENTICATED
//   - 403 (any code)                       -> PERMISSION_DENIED
//   - 404 NotAuthorizedOrNotFound          -> PERMISSION_DENIED (never absence)
//   - 404 BucketNotFound / ObjectNotFound  -> NOT_FOUND bucket / object
//   - 404 on a queue/message route         -> NOT_FOUND queue / message
//   - 409 -> CONFLICT, 412 -> PRECONDITION_FAILED, 400 -> INVALID_ARGUMENT
//   - 429 / 5xx (after retries) / other    -> INTERNAL
func classify(resp *response, sc scope) *hostkit.CloudError {
	code, msg := errorCode(resp), errorMessage(resp)
	status := resp.Status
	switch status {
	case http.StatusUnauthorized:
		return hostkit.ProviderError(hostkit.Unauthenticated, status, orDefault(code, "NotAuthenticated"), msg)
	case http.StatusForbidden:
		return hostkit.ProviderError(hostkit.PermissionDenied, status, orDefault(code, "NotAuthorizedOrNotFound"), msg)
	case http.StatusNotFound:
		if strings.EqualFold(code, "NotAuthorizedOrNotFound") {
			if sc == scopeMessage && strings.HasPrefix(msg, inflightReceiptPrefix) {
				return hostkit.ProviderError(hostkit.NotFound, status, code, msg).WithAbsent(hostkit.AbsentMessage)
			}
			return hostkit.ProviderError(hostkit.PermissionDenied, status, code, msg)
		}
		e := hostkit.ProviderError(hostkit.NotFound, status, code, msg)
		switch {
		case strings.EqualFold(code, "BucketNotFound"):
			return e.WithAbsent(hostkit.AbsentBucket)
		case strings.EqualFold(code, "ObjectNotFound"):
			return e.WithAbsent(hostkit.AbsentObject)
		case sc == scopeQueue:
			return e.WithAbsent(hostkit.AbsentQueue)
		}
		return e
	}
	return hostkit.ProviderError(hostkit.CodeForStatus(status), status, code, msg)
}

func orDefault(v, d string) string {
	if v == "" {
		return d
	}
	return v
}

func ok(resp *response, statuses ...int) bool {
	for _, s := range statuses {
		if resp.Status == s {
			return true
		}
	}
	return false
}
