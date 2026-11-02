// SPDX-License-Identifier: MIT

package oci

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/lmctl-ai/lmcloud/hostkit"
)

const queueAPI = "/20210201"

func jsonBody(v any) ([]byte, error) { return json.Marshal(v) }

type queueSummary struct {
	ID               string `json:"id"`
	DisplayName      string `json:"displayName"`
	LifecycleState   string `json:"lifecycleState"`
	MessagesEndpoint string `json:"messagesEndpoint"`
}

// listQueuePage returns one page of the queue collection (optionally filtered by displayName).
func (r *remote) listQueuePage(ctx context.Context, base, displayName, token string) ([]queueSummary, string, *hostkit.CloudError) {
	_, tenancy, cerr := r.credentials()
	if cerr != nil {
		return nil, "", cerr
	}
	q := url.Values{"compartmentId": {r.compartment(tenancy)}, "limit": {"1000"}}
	if displayName != "" {
		q.Set("displayName", displayName)
	}
	if token != "" {
		q.Set("page", token)
	}
	resp, cerr := r.do(ctx, http.MethodGet, base, queueAPI+"/queues", q, nil, "", nil)
	if cerr != nil {
		return nil, "", cerr
	}
	if resp.Status != http.StatusOK {
		return nil, "", classify(resp, scopeNone)
	}
	var col struct {
		Items []queueSummary `json:"items"`
	}
	if cerr := decode(resp, &col); cerr != nil {
		return nil, "", cerr
	}
	return col.Items, resp.Header.Get("opc-next-page"), nil
}

// lookupQueue resolves a displayName to its OCID and messages endpoint,
// following every page. Only ACTIVE queues count; the cache is per endpoint.
func (r *remote) lookupQueue(ctx context.Context, base, name string) (queueRef, *hostkit.CloudError) {
	cacheKey := base + "\x00" + name
	r.mu.Lock()
	ref, cached := r.queues[cacheKey]
	r.mu.Unlock()
	if cached {
		return ref, nil
	}
	items, cerr := hostkit.ListAll(ctx, 0, func(ctx context.Context, token string) ([]queueSummary, string, error) {
		page, next, cerr := r.listQueuePage(ctx, base, name, token)
		if cerr != nil {
			return nil, "", cerr
		}
		return page, next, nil
	})
	if cerr != nil {
		return queueRef{}, cerr
	}
	for _, it := range items {
		if it.DisplayName != name || it.ID == "" || !strings.EqualFold(it.LifecycleState, "ACTIVE") {
			continue
		}
		ref, cerr := r.messagesBase(base, it)
		if cerr != nil {
			return queueRef{}, cerr
		}
		r.mu.Lock()
		r.queues[cacheKey] = ref
		r.mu.Unlock()
		return ref, nil
	}
	return queueRef{}, hostkit.ProviderError(hostkit.NotFound, 404, "QueueNotFound", "queue "+strconv.Quote(name)+" does not exist").WithAbsent(hostkit.AbsentQueue)
}

// messagesBase picks the data-plane endpoint. On a loopback override all
// traffic stays on the override (design 07 §3.1); otherwise the queue's
// advertised messagesEndpoint is used after passing the shared endpoint policy.
func (r *remote) messagesBase(base string, it queueSummary) (queueRef, *hostkit.CloudError) {
	if u, err := url.Parse(base); err == nil && hostkit.IsLoopbackHost(u.Hostname()) {
		return queueRef{ID: it.ID, Messages: base}, nil
	}
	ep, cerr := hostkit.ValidateEndpoint(it.MessagesEndpoint)
	if cerr != nil {
		return queueRef{}, hostkit.ProviderError(hostkit.Internal, 0, "", "queue messagesEndpoint rejected by endpoint policy: "+cerr.Message)
	}
	return queueRef{ID: it.ID, Messages: ep}, nil
}

func (r *remote) forget(base, name string) {
	r.mu.Lock()
	delete(r.queues, base+"\x00"+name)
	r.mu.Unlock()
}

// waitWorkRequest polls an asynchronous queue lifecycle operation until it
// succeeds; FAILED/CANCELED or the operation deadline are INTERNAL.
func (r *remote) waitWorkRequest(ctx context.Context, base string, resp *response) *hostkit.CloudError {
	id := resp.Header.Get("opc-work-request-id")
	if id == "" {
		return hostkit.ProviderError(hostkit.Internal, resp.Status, "", "202 Accepted without opc-work-request-id")
	}
	for {
		wr, cerr := r.do(ctx, http.MethodGet, base, queueAPI+"/workRequests/"+hostkit.EscapeSegment(id), nil, nil, "", nil)
		if cerr != nil {
			return cerr
		}
		if wr.Status != http.StatusOK {
			return classify(wr, scopeNone)
		}
		var st struct {
			Status string `json:"status"`
		}
		if cerr := decode(wr, &st); cerr != nil {
			return cerr
		}
		switch strings.ToUpper(st.Status) {
		case "SUCCEEDED":
			return nil
		case "FAILED", "CANCELED", "CANCELLED":
			return hostkit.ProviderError(hostkit.Internal, wr.Status, st.Status, "queue work request "+st.Status)
		}
		if err := hostkit.SleepContext(ctx, 250*time.Millisecond); err != nil {
			return hostkit.ProviderError(hostkit.Internal, 0, "", "queue work request did not finish: "+err.Error())
		}
	}
}

func (r *remote) CreateQueue(ctx context.Context, name string) (bool, error) {
	base := hostkit.EndpointFrom(ctx)
	_, cerr := r.lookupQueue(ctx, base, name)
	if cerr == nil {
		return false, nil
	}
	if cerr.Code != hostkit.NotFound {
		return false, cerr
	}
	_, tenancy, cerr := r.credentials()
	if cerr != nil {
		return false, cerr
	}
	body, _ := jsonBody(map[string]any{"compartmentId": r.compartment(tenancy), "displayName": name, "freeformTags": tokenTags(ctx)})
	// OCI does not de-duplicate queue names, so the POST is never retried. If
	// the reply is lost (transport error or 5xx) and a queue with this name is
	// now visible, report CONFLICT: the host resolves it with QueueCreateToken.
	resp, cerr := r.do(ctx, http.MethodPost, base, queueAPI+"/queues", nil, body, "application/json", nil)
	if cerr != nil || resp.Status >= 500 {
		if lost := r.lostCreate(ctx, base, name); lost != nil {
			return false, lost
		}
		if cerr != nil {
			return false, cerr
		}
		return false, classify(resp, scopeQueue)
	}
	if !ok(resp, http.StatusOK, http.StatusAccepted) {
		return false, classify(resp, scopeQueue)
	}
	if resp.Status == http.StatusAccepted {
		if cerr := r.waitWorkRequest(ctx, base, resp); cerr != nil {
			return false, cerr
		}
	}
	if _, cerr := r.lookupQueue(ctx, base, name); cerr != nil {
		return false, hostkit.ProviderError(hostkit.Internal, 0, "", "queue was created but is not ACTIVE: "+cerr.Message)
	}
	return true, nil
}

func (r *remote) DeleteQueue(ctx context.Context, name string) error {
	base := hostkit.EndpointFrom(ctx)
	ref, cerr := r.lookupQueue(ctx, base, name)
	if cerr != nil {
		return cerr
	}
	resp, cerr := r.do(ctx, http.MethodDelete, base, queueAPI+"/queues/"+hostkit.EscapeSegment(ref.ID), nil, nil, "", nil)
	if cerr != nil {
		return cerr
	}
	r.forget(base, name)
	if !ok(resp, http.StatusOK, http.StatusAccepted, http.StatusNoContent) {
		return classify(resp, scopeQueue)
	}
	if resp.Status == http.StatusAccepted {
		if cerr := r.waitWorkRequest(ctx, base, resp); cerr != nil {
			return cerr // never return a typed-nil *CloudError through error
		}
	}
	return nil
}

func (r *remote) ListQueues(ctx context.Context, token string) ([]string, string, error) {
	base := hostkit.EndpointFrom(ctx)
	items, next, cerr := r.listQueuePage(ctx, base, "", token)
	if cerr != nil {
		return nil, "", cerr
	}
	names := make([]string, 0, len(items))
	for _, it := range items {
		if strings.EqualFold(it.LifecycleState, "ACTIVE") && it.DisplayName != "" {
			names = append(names, it.DisplayName)
		}
	}
	return names, next, nil
}

func messagePath(ref queueRef, receipt string) string {
	p := queueAPI + "/queues/" + hostkit.EscapeSegment(ref.ID) + "/messages"
	if receipt != "" {
		p += "/" + hostkit.EscapeSegment(receipt)
	}
	return p
}

func (r *remote) Send(ctx context.Context, name, wire string) (string, error) {
	base := hostkit.EndpointFrom(ctx)
	ref, cerr := r.lookupQueue(ctx, base, name)
	if cerr != nil {
		return "", cerr
	}
	body, _ := jsonBody(map[string]any{"messages": []map[string]string{{"content": wire}}})
	resp, cerr := r.do(ctx, http.MethodPost, ref.Messages, messagePath(ref, ""), nil, body, "application/json", nil)
	if cerr != nil {
		return "", cerr
	}
	if !ok(resp, http.StatusOK) {
		return "", classify(resp, scopeQueue)
	}
	var out struct {
		Messages []struct {
			ID json.Number `json:"id"`
		} `json:"messages"`
	}
	if cerr := decode(resp, &out); cerr != nil {
		return "", cerr
	}
	if len(out.Messages) != 1 || out.Messages[0].ID == "" {
		return "", hostkit.ProviderError(hostkit.Internal, resp.Status, "", "PutMessages returned no message id")
	}
	return out.Messages[0].ID.String(), nil
}

func (r *remote) Receive(ctx context.Context, name string, max, wait, vis int) ([]hostkit.RawMessage, error) {
	base := hostkit.EndpointFrom(ctx)
	ref, cerr := r.lookupQueue(ctx, base, name)
	if cerr != nil {
		return nil, cerr
	}
	q := url.Values{"limit": {strconv.Itoa(max)}, "timeoutInSeconds": {strconv.Itoa(wait)}, "visibilityInSeconds": {strconv.Itoa(vis)}}
	resp, cerr := r.do(ctx, http.MethodGet, ref.Messages, messagePath(ref, ""), q, nil, "", nil)
	if cerr != nil {
		return nil, cerr
	}
	if resp.Status != http.StatusOK {
		return nil, classify(resp, scopeQueue)
	}
	var out struct {
		Messages []struct {
			ID      json.Number `json:"id"`
			Content string      `json:"content"`
			Receipt string      `json:"receipt"`
		} `json:"messages"`
	}
	if cerr := decode(resp, &out); cerr != nil {
		return nil, cerr
	}
	msgs := make([]hostkit.RawMessage, 0, len(out.Messages))
	for _, m := range out.Messages {
		msgs = append(msgs, hostkit.RawMessage{ID: m.ID.String(), Wire: m.Content, Receipt: m.Receipt})
	}
	return msgs, nil
}

func (r *remote) Ack(ctx context.Context, name, receipt string) error {
	base := hostkit.EndpointFrom(ctx)
	ref, cerr := r.lookupQueue(ctx, base, name) // missing queue wins over a bad receipt
	if cerr != nil {
		return cerr
	}
	resp, cerr := r.do(ctx, http.MethodDelete, ref.Messages, messagePath(ref, receipt), nil, nil, "", nil)
	if cerr != nil {
		return cerr
	}
	if ok(resp, http.StatusOK, http.StatusNoContent) {
		return nil
	}
	return classify(resp, scopeMessage)
}

// nack: OCI UpdateMessage keeps the receipt valid, so the same receipt is returned.
func (r *remote) Nack(ctx context.Context, name, receipt string, vis int) (string, error) {
	base := hostkit.EndpointFrom(ctx)
	ref, cerr := r.lookupQueue(ctx, base, name)
	if cerr != nil {
		return "", cerr
	}
	body, _ := jsonBody(map[string]int{"visibilityInSeconds": vis})
	resp, cerr := r.do(ctx, http.MethodPut, ref.Messages, messagePath(ref, receipt), nil, body, "application/json", nil)
	if cerr != nil {
		return "", cerr
	}
	if ok(resp, http.StatusOK, http.StatusNoContent) {
		return receipt, nil
	}
	return "", classify(resp, scopeMessage)
}

// lostCreate checks, after an ambiguous CreateQueue reply, whether a queue
// with this name now exists; if so it returns CONFLICT for token resolution.
func (r *remote) lostCreate(ctx context.Context, base, name string) *hostkit.CloudError {
	r.forget(base, name)
	if _, cerr := r.lookupQueue(ctx, base, name); cerr == nil {
		return hostkit.ProviderError(hostkit.Conflict, 409, "QueueAlreadyExists", "queue exists after an ambiguous create reply")
	}
	return nil
}

// LookupQueue implements hostkit.Queue.
func (r *remote) LookupQueue(ctx context.Context, name string) error {
	if _, cerr := r.lookupQueue(ctx, hostkit.EndpointFrom(ctx), name); cerr != nil {
		return cerr
	}
	return nil
}

// QueueCaps: OCI GetMessages long-polls (timeoutInSeconds) and carries content
// as a JSON string, so the host applies the __lmcloud_b64__ envelope.
func (r *remote) QueueCaps() hostkit.QueueCaps {
	return hostkit.QueueCaps{LongPoll: true, TextOnlyWire: true}
}

// QueueCreateToken implements hostkit.QueueCreateTokenReader (GetQueue freeformTags).
func (r *remote) QueueCreateToken(ctx context.Context, name string) (string, error) {
	base := hostkit.EndpointFrom(ctx)
	ref, cerr := r.lookupQueue(ctx, base, name)
	if cerr != nil {
		return "", cerr
	}
	resp, cerr := r.do(ctx, http.MethodGet, base, queueAPI+"/queues/"+hostkit.EscapeSegment(ref.ID), nil, nil, "", nil)
	if cerr != nil {
		return "", cerr
	}
	if resp.Status != http.StatusOK {
		return "", classify(resp, scopeQueue)
	}
	var out struct {
		FreeformTags map[string]string `json:"freeformTags"`
	}
	if cerr := decode(resp, &out); cerr != nil {
		return "", cerr
	}
	return out.FreeformTags[createTokenTag], nil
}
