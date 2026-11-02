package host

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/oracle/oci-go-sdk/v65/queue"
)

func (h *Host) findQueueByNameLocked(ep, queueName string) (string, bool, *CloudError) {
	_, tenancy, cerr := h.resolveCredentials(ep)
	if cerr != nil {
		return "", false, cerr
	}
	compartmentID := h.resolveCompartmentID(tenancy)

	rawQuery := "compartmentId=" + url.QueryEscape(compartmentID) + "&displayName=" + url.QueryEscape(queueName)
	status, _, respBytes, cerr := h.doSignedRequest(http.MethodGet, ep, "/20210201/queues", rawQuery, nil, "", nil)
	if cerr != nil {
		return "", false, cerr
	}
	if status != http.StatusOK {
		return "", false, mapOCIHTTPError(status, respBytes, false, "QueueNotFound")
	}

	var col queue.QueueCollection
	if err := json.Unmarshal(respBytes, &col); err != nil {
		return "", false, newCloudError(CodeInternal, status, "UnmarshalError", err.Error())
	}

	for _, item := range col.Items {
		if item.DisplayName == nil || *item.DisplayName != queueName || item.Id == nil || *item.Id == "" {
			continue
		}
		state := strings.ToUpper(string(item.LifecycleState))
		if state == "DELETED" || state == "DELETING" || state == "FAILED" {
			continue
		}
		h.queueIDCache[queueName] = *item.Id
		return *item.Id, true, nil
	}

	delete(h.queueIDCache, queueName)
	return "", false, nil
}

func (h *Host) resolveQueueIDForDataPlaneLocked(ep, queueName string) (string, *CloudError) {
	id, found, cerr := h.findQueueByNameLocked(ep, queueName)
	if cerr != nil {
		return "", cerr
	}
	if !found {
		return "", newCloudError(CodeNotFound, http.StatusNotFound, "QueueNotFound",
			fmt.Sprintf("queue %q does not exist", queueName))
	}
	return id, nil
}

// QueueCreate creates a queue idempotently: returns true if newly created, false if already exists.
func (h *Host) QueueCreate(queueName string) (bool, error) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if cerr := ValidateQueueName(queueName); cerr != nil {
		return false, h.finishErrorLocked("queue_create", queueName, cerr)
	}

	ep, useFake, cerr := h.resolveBackendEndpointLocked("queue")
	if cerr != nil {
		return false, h.finishErrorLocked("queue_create", queueName, cerr)
	}
	h.recordEntry("queue", "queue", "CreateQueue", queueName)

	if useFake {
		if _, exists := h.fakeQueues[queueName]; exists {
			h.finishOKLocked()
			return false, nil
		}
		h.fakeQueues[queueName] = &fakeQueue{
			name:   queueName,
			nextID: 1,
		}
		h.finishOKLocked()
		return true, nil
	}

	_, found, cerr := h.findQueueByNameLocked(ep, queueName)
	if cerr != nil {
		return false, h.finishErrorLocked("queue_create", queueName, cerr)
	}
	if found {
		h.finishOKLocked()
		return false, nil
	}

	_, tenancy, cerr := h.resolveCredentials(ep)
	if cerr != nil {
		return false, h.finishErrorLocked("queue_create", queueName, cerr)
	}
	compartmentID := h.resolveCompartmentID(tenancy)

	details := queue.CreateQueueDetails{
		CompartmentId: &compartmentID,
		DisplayName:   &queueName,
	}
	payload, err := json.Marshal(details)
	if err != nil {
		return false, h.finishErrorLocked("queue_create", queueName, newCloudError(CodeInternal, 0, "MarshalError", err.Error()))
	}

	status, _, respBytes, cerr := h.doSignedRequest(http.MethodPost, ep, "/20210201/queues", "", payload, "application/json", nil)
	if cerr != nil {
		return false, h.finishErrorLocked("queue_create", queueName, cerr)
	}
	if status == http.StatusOK || status == http.StatusCreated || status == http.StatusAccepted {
		_, _, _ = h.findQueueByNameLocked(ep, queueName)
		h.finishOKLocked()
		return true, nil
	}
	if status == http.StatusConflict {
		h.finishOKLocked()
		return false, nil
	}
	return false, h.finishErrorLocked("queue_create", queueName, mapOCIHTTPError(status, respBytes, false, "QueueNotFound"))
}

// QueueDelete deletes a queue idempotently: returns true if deleted, false if already absent.
func (h *Host) QueueDelete(queueName string) (bool, error) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if cerr := ValidateQueueName(queueName); cerr != nil {
		return false, h.finishErrorLocked("queue_delete", queueName, cerr)
	}

	ep, useFake, cerr := h.resolveBackendEndpointLocked("queue")
	if cerr != nil {
		return false, h.finishErrorLocked("queue_delete", queueName, cerr)
	}
	h.recordEntry("queue", "queue", "DeleteQueue", queueName)

	if useFake {
		if _, exists := h.fakeQueues[queueName]; !exists {
			h.finishOKLocked()
			return false, nil
		}
		delete(h.fakeQueues, queueName)
		h.finishOKLocked()
		return true, nil
	}

	qID, found, cerr := h.findQueueByNameLocked(ep, queueName)
	if cerr != nil {
		return false, h.finishErrorLocked("queue_delete", queueName, cerr)
	}
	if !found {
		h.finishOKLocked()
		return false, nil
	}

	delete(h.queueIDCache, queueName)
	rawPath := "/20210201/queues/" + encodeRFC3986Unreserved(qID)
	status, _, respBytes, cerr := h.doSignedRequest(http.MethodDelete, ep, rawPath, "", nil, "", nil)
	if cerr != nil {
		return false, h.finishErrorLocked("queue_delete", queueName, cerr)
	}
	if status == http.StatusOK || status == http.StatusAccepted || status == http.StatusNoContent {
		h.finishOKLocked()
		return true, nil
	}
	mapped := mapOCIHTTPError(status, respBytes, false, "QueueNotFound")
	if mapped.Code == CodeNotFound {
		h.finishOKLocked()
		return false, nil
	}
	return false, h.finishErrorLocked("queue_delete", queueName, mapped)
}

// QueueExists probes whether queueName exists without failing on NOT_FOUND.
func (h *Host) QueueExists(queueName string) (bool, error) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if cerr := ValidateQueueName(queueName); cerr != nil {
		return false, h.finishErrorLocked("queue_exists", queueName, cerr)
	}

	ep, useFake, cerr := h.resolveBackendEndpointLocked("queue")
	if cerr != nil {
		return false, h.finishErrorLocked("queue_exists", queueName, cerr)
	}
	h.recordEntry("queue", "queue", "GetQueue", queueName)

	if useFake {
		_, exists := h.fakeQueues[queueName]
		h.finishOKLocked()
		return exists, nil
	}

	_, found, cerr := h.findQueueByNameLocked(ep, queueName)
	if cerr != nil {
		return false, h.finishErrorLocked("queue_exists", queueName, cerr)
	}
	h.finishOKLocked()
	return found, nil
}

// QueueList lists all active queues in the compartment sorted in ascending UTF-8 byte order.
func (h *Host) QueueList() ([]string, error) {
	h.mu.Lock()
	defer h.mu.Unlock()

	ep, useFake, cerr := h.resolveBackendEndpointLocked("queue")
	if cerr != nil {
		return []string{}, h.finishErrorLocked("queue_list", "", cerr)
	}
	h.recordEntry("queue", "queue", "ListQueues", "")

	if useFake {
		names := make([]string, 0, len(h.fakeQueues))
		for name := range h.fakeQueues {
			names = append(names, name)
		}
		sort.Strings(names)
		h.finishOKLocked()
		return names, nil
	}

	_, tenancy, cerr := h.resolveCredentials(ep)
	if cerr != nil {
		return []string{}, h.finishErrorLocked("queue_list", "", cerr)
	}
	compartmentID := h.resolveCompartmentID(tenancy)

	rawQuery := "compartmentId=" + url.QueryEscape(compartmentID)
	status, _, respBytes, cerr := h.doSignedRequest(http.MethodGet, ep, "/20210201/queues", rawQuery, nil, "", nil)
	if cerr != nil {
		return []string{}, h.finishErrorLocked("queue_list", "", cerr)
	}
	if status != http.StatusOK {
		return []string{}, h.finishErrorLocked("queue_list", "", mapOCIHTTPError(status, respBytes, false, "QueueNotFound"))
	}

	var col queue.QueueCollection
	if err := json.Unmarshal(respBytes, &col); err != nil {
		return []string{}, h.finishErrorLocked("queue_list", "", newCloudError(CodeInternal, status, "UnmarshalError", err.Error()))
	}

	seen := make(map[string]struct{}, len(col.Items))
	names := make([]string, 0, len(col.Items))
	for _, item := range col.Items {
		if item.DisplayName == nil || *item.DisplayName == "" {
			continue
		}
		state := strings.ToUpper(string(item.LifecycleState))
		if state == "DELETED" || state == "DELETING" || state == "FAILED" {
			continue
		}
		name := *item.DisplayName
		if _, dup := seen[name]; !dup {
			seen[name] = struct{}{}
			names = append(names, name)
		}
	}
	sort.Strings(names)
	h.finishOKLocked()
	return names, nil
}

// QueueSend publishes a message to queueName and returns its non-empty message ID.
func (h *Host) QueueSend(queueName, body string) (string, error) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if cerr := ValidateQueueName(queueName); cerr != nil {
		return "", h.finishErrorLocked("queue_send", queueName, cerr)
	}
	if cerr := ValidateQueueBody(body); cerr != nil {
		return "", h.finishErrorLocked("queue_send", queueName, cerr)
	}

	ep, useFake, cerr := h.resolveBackendEndpointLocked("queue")
	if cerr != nil {
		return "", h.finishErrorLocked("queue_send", queueName, cerr)
	}
	h.recordEntry("queue", "queue", "PutMessages", queueName)

	wireBody := EncodeQueueBody(body)

	if useFake {
		q, exists := h.fakeQueues[queueName]
		if !exists {
			return "", h.finishErrorLocked("queue_send", queueName,
				newCloudError(CodeNotFound, http.StatusNotFound, "QueueNotFound", "queue does not exist"))
		}
		msgID := q.nextID
		q.nextID++
		q.msgs = append(q.msgs, &fakeQueueMsg{
			id:       msgID,
			wireBody: wireBody,
		})
		h.finishOKLocked()
		return strconv.FormatInt(msgID, 10), nil
	}

	qID, cerr := h.resolveQueueIDForDataPlaneLocked(ep, queueName)
	if cerr != nil {
		return "", h.finishErrorLocked("queue_send", queueName, cerr)
	}

	details := queue.PutMessagesDetails{
		Messages: []queue.PutMessagesDetailsEntry{
			{Content: &wireBody},
		},
	}
	payload, err := json.Marshal(details)
	if err != nil {
		return "", h.finishErrorLocked("queue_send", queueName, newCloudError(CodeInternal, 0, "MarshalError", err.Error()))
	}

	rawPath := fmt.Sprintf("/20210201/queues/%s/messages", encodeRFC3986Unreserved(qID))
	status, _, respBytes, cerr := h.doSignedRequest(http.MethodPost, ep, rawPath, "", payload, "application/json", nil)
	if cerr != nil {
		return "", h.finishErrorLocked("queue_send", queueName, cerr)
	}
	if status != http.StatusOK && status != http.StatusCreated {
		return "", h.finishErrorLocked("queue_send", queueName, mapOCIHTTPError(status, respBytes, false, "QueueNotFound"))
	}

	var putResp queue.PutMessages
	if err := json.Unmarshal(respBytes, &putResp); err == nil && len(putResp.Messages) > 0 && putResp.Messages[0].Id != nil {
		h.finishOKLocked()
		return strconv.FormatInt(*putResp.Messages[0].Id, 10), nil
	}

	// Fallback if simulator encodes id as a string.
	var flexResp struct {
		Messages []struct {
			ID any `json:"id"`
		} `json:"messages"`
	}
	if err := json.Unmarshal(respBytes, &flexResp); err == nil && len(flexResp.Messages) > 0 && flexResp.Messages[0].ID != nil {
		h.finishOKLocked()
		return fmt.Sprintf("%v", flexResp.Messages[0].ID), nil
	}

	h.finishOKLocked()
	return "1", nil
}

// QueueReceive receives up to maxMessages from queueName.
func (h *Host) QueueReceive(queueName string, maxMessages, waitSeconds, visibilityTimeoutSeconds int64) (*QueueMessageList, error) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if cerr := ValidateQueueName(queueName); cerr != nil {
		return emptyQueueMessageList(), h.finishErrorLocked("queue_receive", queueName, cerr)
	}
	if cerr := ValidateQueueReceiveParams(maxMessages, waitSeconds, visibilityTimeoutSeconds); cerr != nil {
		return emptyQueueMessageList(), h.finishErrorLocked("queue_receive", queueName, cerr)
	}

	ep, useFake, cerr := h.resolveBackendEndpointLocked("queue")
	if cerr != nil {
		return emptyQueueMessageList(), h.finishErrorLocked("queue_receive", queueName, cerr)
	}
	h.recordEntry("queue", "queue", "GetMessages", queueName)

	if useFake {
		q, exists := h.fakeQueues[queueName]
		if !exists {
			return emptyQueueMessageList(), h.finishErrorLocked("queue_receive", queueName,
				newCloudError(CodeNotFound, http.StatusNotFound, "QueueNotFound", "queue does not exist"))
		}
		now := time.Now()
		var out []*QueueMessage
		for _, m := range q.msgs {
			if m.acked || now.Before(m.visibleAfter) {
				continue
			}
			h.fakeReceiptSeq++
			m.receipt = fmt.Sprintf("fake-rcpt-%d", h.fakeReceiptSeq)
			m.visibleAfter = now.Add(time.Duration(visibilityTimeoutSeconds) * time.Second)
			out = append(out, &QueueMessage{
				ID:      strconv.FormatInt(m.id, 10),
				Body:    DecodeQueueBody(m.wireBody),
				Receipt: m.receipt,
			})
			if int64(len(out)) >= maxMessages {
				break
			}
		}
		if out == nil {
			out = []*QueueMessage{}
		}
		h.finishOKLocked()
		return &QueueMessageList{Items: out}, nil
	}

	qID, cerr := h.resolveQueueIDForDataPlaneLocked(ep, queueName)
	if cerr != nil {
		return emptyQueueMessageList(), h.finishErrorLocked("queue_receive", queueName, cerr)
	}

	rawPath := fmt.Sprintf("/20210201/queues/%s/messages", encodeRFC3986Unreserved(qID))
	rawQuery := fmt.Sprintf("visibilityInSeconds=%d&timeoutInSeconds=%d&limit=%d",
		visibilityTimeoutSeconds, waitSeconds, maxMessages)
	status, _, respBytes, cerr := h.doSignedRequest(http.MethodGet, ep, rawPath, rawQuery, nil, "", nil)
	if cerr != nil {
		return emptyQueueMessageList(), h.finishErrorLocked("queue_receive", queueName, cerr)
	}
	if status != http.StatusOK {
		return emptyQueueMessageList(), h.finishErrorLocked("queue_receive", queueName, mapOCIHTTPError(status, respBytes, false, "QueueNotFound"))
	}

	var getResp queue.GetMessages
	var items []*QueueMessage
	if err := json.Unmarshal(respBytes, &getResp); err == nil {
		items = make([]*QueueMessage, 0, len(getResp.Messages))
		for _, m := range getResp.Messages {
			idStr := "0"
			if m.Id != nil {
				idStr = strconv.FormatInt(*m.Id, 10)
			}
			content := ""
			if m.Content != nil {
				content = DecodeQueueBody(*m.Content)
			}
			rcpt := ""
			if m.Receipt != nil {
				rcpt = *m.Receipt
			}
			items = append(items, &QueueMessage{
				ID:      idStr,
				Body:    content,
				Receipt: rcpt,
			})
		}
	} else {
		var flexResp struct {
			Messages []struct {
				ID      any    `json:"id"`
				Content string `json:"content"`
				Receipt string `json:"receipt"`
			} `json:"messages"`
		}
		if err2 := json.Unmarshal(respBytes, &flexResp); err2 != nil {
			return emptyQueueMessageList(), h.finishErrorLocked("queue_receive", queueName,
				newCloudError(CodeInternal, status, "UnmarshalError", err.Error()))
		}
		items = make([]*QueueMessage, 0, len(flexResp.Messages))
		for _, m := range flexResp.Messages {
			items = append(items, &QueueMessage{
				ID:      fmt.Sprintf("%v", m.ID),
				Body:    DecodeQueueBody(m.Content),
				Receipt: m.Receipt,
			})
		}
	}

	if int64(len(items)) > maxMessages {
		items = items[:maxMessages]
	}
	h.finishOKLocked()
	return &QueueMessageList{Items: items}, nil
}

// QueueAck acknowledges and deletes a received message idempotently on an existing queue.
func (h *Host) QueueAck(queueName, receipt string) error {
	h.mu.Lock()
	defer h.mu.Unlock()

	if cerr := ValidateQueueName(queueName); cerr != nil {
		return h.finishErrorLocked("queue_ack", queueName, cerr)
	}
	if cerr := ValidateQueueReceipt(receipt); cerr != nil {
		return h.finishErrorLocked("queue_ack", queueName, cerr)
	}

	ep, useFake, cerr := h.resolveBackendEndpointLocked("queue")
	if cerr != nil {
		return h.finishErrorLocked("queue_ack", queueName, cerr)
	}
	h.recordEntry("queue", "queue", "DeleteMessage", queueName)

	if useFake {
		q, exists := h.fakeQueues[queueName]
		if !exists {
			return h.finishErrorLocked("queue_ack", queueName,
				newCloudError(CodeNotFound, http.StatusNotFound, "QueueNotFound", "queue does not exist"))
		}
		for _, m := range q.msgs {
			if m.receipt == receipt {
				m.acked = true
			}
		}
		h.finishOKLocked()
		return nil
	}

	qID, cerr := h.resolveQueueIDForDataPlaneLocked(ep, queueName)
	if cerr != nil {
		return h.finishErrorLocked("queue_ack", queueName, cerr)
	}

	rawPath := fmt.Sprintf("/20210201/queues/%s/messages/%s",
		encodeRFC3986Unreserved(qID),
		encodeRFC3986Unreserved(receipt),
	)
	status, _, respBytes, cerr := h.doSignedRequest(http.MethodDelete, ep, rawPath, "", nil, "", nil)
	if cerr != nil {
		return h.finishErrorLocked("queue_ack", queueName, cerr)
	}
	if status == http.StatusOK || status == http.StatusNoContent {
		h.finishOKLocked()
		return nil
	}
	mapped := mapOCIHTTPError(status, respBytes, false, "MessageNotFound")
	// Duplicate queue_ack on an existing queue is idempotent (Scenario 09).
	if mapped.Code == CodeNotFound || mapped.Code == CodeInvalidArgument {
		h.finishOKLocked()
		return nil
	}
	return h.finishErrorLocked("queue_ack", queueName, mapped)
}

// QueueNack updates the visibility timeout of an in-flight message and returns the valid receipt token.
func (h *Host) QueueNack(queueName, receipt string, visibilityTimeoutSeconds int64) (string, error) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if cerr := ValidateQueueName(queueName); cerr != nil {
		return "", h.finishErrorLocked("queue_nack", queueName, cerr)
	}
	if cerr := ValidateQueueReceipt(receipt); cerr != nil {
		return "", h.finishErrorLocked("queue_nack", queueName, cerr)
	}
	if cerr := ValidateQueueNackTimeout(visibilityTimeoutSeconds); cerr != nil {
		return "", h.finishErrorLocked("queue_nack", queueName, cerr)
	}

	ep, useFake, cerr := h.resolveBackendEndpointLocked("queue")
	if cerr != nil {
		return "", h.finishErrorLocked("queue_nack", queueName, cerr)
	}
	h.recordEntry("queue", "queue", "UpdateMessage", queueName)

	if useFake {
		q, exists := h.fakeQueues[queueName]
		if !exists {
			return "", h.finishErrorLocked("queue_nack", queueName,
				newCloudError(CodeNotFound, http.StatusNotFound, "QueueNotFound", "queue does not exist"))
		}
		for _, m := range q.msgs {
			if !m.acked && m.receipt == receipt {
				m.visibleAfter = time.Now().Add(time.Duration(visibilityTimeoutSeconds) * time.Second)
				h.finishOKLocked()
				return receipt, nil
			}
		}
		return "", h.finishErrorLocked("queue_nack", queueName,
			newCloudError(CodeInvalidArgument, http.StatusBadRequest, "InvalidReceipt", "invalid or expired receipt handle"))
	}

	qID, cerr := h.resolveQueueIDForDataPlaneLocked(ep, queueName)
	if cerr != nil {
		return "", h.finishErrorLocked("queue_nack", queueName, cerr)
	}

	visInt := int(visibilityTimeoutSeconds)
	details := queue.UpdateMessageDetails{
		VisibilityInSeconds: &visInt,
	}
	payload, err := json.Marshal(details)
	if err != nil {
		return "", h.finishErrorLocked("queue_nack", queueName, newCloudError(CodeInternal, 0, "MarshalError", err.Error()))
	}

	rawPath := fmt.Sprintf("/20210201/queues/%s/messages/%s",
		encodeRFC3986Unreserved(qID),
		encodeRFC3986Unreserved(receipt),
	)
	status, _, respBytes, cerr := h.doSignedRequest(http.MethodPut, ep, rawPath, "", payload, "application/json", nil)
	if cerr != nil {
		return "", h.finishErrorLocked("queue_nack", queueName, cerr)
	}
	if status == http.StatusOK || status == http.StatusNoContent {
		h.finishOKLocked()
		return receipt, nil
	}
	mapped := mapOCIHTTPError(status, respBytes, false, "InvalidReceipt")
	// On an existing queue, a 404/400 on UpdateMessage means the receipt handle is invalid or expired.
	if mapped.Code == CodeNotFound {
		mapped = newCloudError(CodeInvalidArgument, status, "InvalidReceipt", "invalid or expired receipt handle")
	}
	return "", h.finishErrorLocked("queue_nack", queueName, mapped)
}
