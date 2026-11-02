// SPDX-License-Identifier: MIT

package oci

import (
	"context"
	"net/url"
	"os"

	"github.com/lmctl-ai/lmcloud/hostkit"
	"github.com/lmctl-ai/lmcloud/hostkit/fakeadapter"
)

// Adapter implements hostkit.ObjectStore and hostkit.Queue. Each call is
// routed by the endpoint the host resolved:
//   - "" and LMOCI_LIVE unset: offline in-process fake (hostkit fakeadapter);
//   - "" with LMOCI_LIVE=1, or any non-loopback endpoint: live OCI, which is
//     an explicit not-implemented error until the live credential chain exists;
//   - a loopback endpoint (ocisim): signed OCI REST with simulator credentials.
type Adapter struct {
	remote *remote
	fake   *fakeadapter.Fake
	getenv func(string) string
}

// New returns the adapter. getenv defaults to os.Getenv.
func New(getenv func(string) string) *Adapter {
	if getenv == nil {
		getenv = os.Getenv
	}
	return &Adapter{remote: newRemote(getenv), fake: fakeadapter.New(fakeadapter.Options{}), getenv: getenv}
}

// Endpoints returns the resolver with OCI's native variables (design 02 §3.3).
// There is no built-in live default: "" means offline fake or live (see Adapter).
func Endpoints() *hostkit.EndpointResolver {
	return &hostkit.EndpointResolver{
		Prefix: "LMOCI",
		Native: map[string][]hostkit.NativeEnv{
			"storage": {{Name: "OCI_OBJECT_STORAGE_ENDPOINT"}, {Name: "OCI_ENDPOINT"}},
			"queue":   {{Name: "OCI_QUEUE_ENDPOINT"}, {Name: "OCI_ENDPOINT"}},
		},
	}
}

// route returns (base, useFake, err) for this call.
func (a *Adapter) route(ctx context.Context) (string, bool, *hostkit.CloudError) {
	base := hostkit.EndpointFrom(ctx)
	if base == "" {
		if a.getenv("LMOCI_LIVE") == "1" {
			return "", false, errLiveNotImplemented("LMOCI_LIVE=1 with no endpoint override")
		}
		return "", true, nil
	}
	u, err := url.Parse(base)
	if err != nil || !hostkit.IsLoopbackHost(u.Hostname()) {
		return "", false, errLiveNotImplemented("non-loopback endpoint")
	}
	return base, false, nil
}

// --- hostkit.ObjectStore ---

func (a *Adapter) CreateBucket(ctx context.Context, b string) (bool, error) {
	base, fake, e := a.route(ctx)
	switch {
	case e != nil:
		return false, e
	case fake:
		return a.fake.CreateBucket(ctx, b)
	}
	return a.remote.createBucket(ctx, base, b)
}

func (a *Adapter) DeleteBucket(ctx context.Context, b string) error {
	base, fake, e := a.route(ctx)
	switch {
	case e != nil:
		return e
	case fake:
		return a.fake.DeleteBucket(ctx, b)
	}
	return a.remote.deleteBucket(ctx, base, b)
}

func (a *Adapter) HeadBucket(ctx context.Context, b string) error {
	base, fake, e := a.route(ctx)
	switch {
	case e != nil:
		return e
	case fake:
		return a.fake.HeadBucket(ctx, b)
	}
	return a.remote.headBucket(ctx, base, b)
}

func (a *Adapter) ListBuckets(ctx context.Context, token string) ([]string, string, error) {
	base, fake, e := a.route(ctx)
	switch {
	case e != nil:
		return nil, "", e
	case fake:
		return a.fake.ListBuckets(ctx, token)
	}
	return a.remote.listBuckets(ctx, base, token)
}

func (a *Adapter) PutObject(ctx context.Context, b, k string, body []byte, ct string, meta map[string]string) (hostkit.ObjectMeta, error) {
	base, fake, e := a.route(ctx)
	switch {
	case e != nil:
		return hostkit.ObjectMeta{}, e
	case fake:
		return a.fake.PutObject(ctx, b, k, body, ct, meta)
	}
	return a.remote.putObject(ctx, base, b, k, body, ct, meta)
}

func (a *Adapter) GetObject(ctx context.Context, b, k string, limit int64) ([]byte, error) {
	base, fake, e := a.route(ctx)
	switch {
	case e != nil:
		return nil, e
	case fake:
		return a.fake.GetObject(ctx, b, k, limit)
	}
	return a.remote.getObject(ctx, base, b, k, limit)
}

func (a *Adapter) HeadObject(ctx context.Context, b, k string) (hostkit.ObjectMeta, error) {
	base, fake, e := a.route(ctx)
	switch {
	case e != nil:
		return hostkit.ObjectMeta{}, e
	case fake:
		return a.fake.HeadObject(ctx, b, k)
	}
	return a.remote.headObject(ctx, base, b, k)
}

func (a *Adapter) DeleteObject(ctx context.Context, b, k string) error {
	base, fake, e := a.route(ctx)
	switch {
	case e != nil:
		return e
	case fake:
		return a.fake.DeleteObject(ctx, b, k)
	}
	return a.remote.deleteObject(ctx, base, b, k)
}

func (a *Adapter) ListObjects(ctx context.Context, b, prefix string, max int, token string) (hostkit.ObjectPage, error) {
	base, fake, e := a.route(ctx)
	switch {
	case e != nil:
		return hostkit.ObjectPage{}, e
	case fake:
		return a.fake.ListObjects(ctx, b, prefix, max, token)
	}
	return a.remote.listObjects(ctx, base, b, prefix, max, token)
}

// --- hostkit.Queue ---

// QueueCaps: OCI GetMessages long-polls (timeoutInSeconds) and carries
// content as a JSON string, so the host applies the __lmcloud_b64__ envelope.
func (a *Adapter) QueueCaps() hostkit.QueueCaps {
	return hostkit.QueueCaps{LongPoll: true, TextOnlyWire: true}
}

func (a *Adapter) CreateQueue(ctx context.Context, q string) (bool, error) {
	base, fake, e := a.route(ctx)
	switch {
	case e != nil:
		return false, e
	case fake:
		return a.fake.CreateQueue(ctx, q)
	}
	return a.remote.createQueue(ctx, base, q)
}

func (a *Adapter) DeleteQueue(ctx context.Context, q string) error {
	base, fake, e := a.route(ctx)
	switch {
	case e != nil:
		return e
	case fake:
		return a.fake.DeleteQueue(ctx, q)
	}
	return a.remote.deleteQueue(ctx, base, q)
}

func (a *Adapter) LookupQueue(ctx context.Context, q string) error {
	base, fake, e := a.route(ctx)
	switch {
	case e != nil:
		return e
	case fake:
		return a.fake.LookupQueue(ctx, q)
	}
	if _, cerr := a.remote.lookupQueue(ctx, base, q); cerr != nil {
		return cerr
	}
	return nil
}

func (a *Adapter) ListQueues(ctx context.Context, token string) ([]string, string, error) {
	base, fake, e := a.route(ctx)
	switch {
	case e != nil:
		return nil, "", e
	case fake:
		return a.fake.ListQueues(ctx, token)
	}
	return a.remote.listQueues(ctx, base, token)
}

func (a *Adapter) Send(ctx context.Context, q, wire string) (string, error) {
	base, fake, e := a.route(ctx)
	switch {
	case e != nil:
		return "", e
	case fake:
		return a.fake.Send(ctx, q, wire)
	}
	return a.remote.send(ctx, base, q, wire)
}

func (a *Adapter) Receive(ctx context.Context, q string, max, wait, vis int) ([]hostkit.RawMessage, error) {
	base, fake, e := a.route(ctx)
	switch {
	case e != nil:
		return nil, e
	case fake:
		// The fake does not long-poll; offline mode returns immediately.
		return a.fake.Receive(ctx, q, max, 0, vis)
	}
	return a.remote.receive(ctx, base, q, max, wait, vis)
}

func (a *Adapter) Ack(ctx context.Context, q, receipt string) error {
	base, fake, e := a.route(ctx)
	switch {
	case e != nil:
		return e
	case fake:
		return a.fake.Ack(ctx, q, receipt)
	}
	return a.remote.ack(ctx, base, q, receipt)
}

func (a *Adapter) Nack(ctx context.Context, q, receipt string, vis int) (string, error) {
	base, fake, e := a.route(ctx)
	switch {
	case e != nil:
		return "", e
	case fake:
		return a.fake.Nack(ctx, q, receipt, vis)
	}
	return a.remote.nack(ctx, base, q, receipt, vis)
}
