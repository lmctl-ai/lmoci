// SPDX-License-Identifier: MIT

package oci

import (
	"os"

	"github.com/lmctl-ai/lmcloud/hostkit"
	"github.com/lmctl-ai/lmcloud/hostkit/fakeadapter"
)

// Adapter is the OCI adapter: a hostkit.Router over three routes chosen per
// call from the endpoint the host resolved (hostkit.EndpointRoutes):
//   - no endpoint, LMOCI_LIVE unset: offline in-process fake (its own caps:
//     short poll, so the host's wait loop honours wait_seconds);
//   - loopback endpoint (ocisim): signed OCI REST with simulator credentials
//     (long poll via timeoutInSeconds);
//   - live (LMOCI_LIVE=1 without endpoint, or any non-loopback endpoint):
//     UNAUTHENTICATED/LiveCredentialsNotImplemented until the live chain exists.
//
// The Router forwards every adapter method, QueueCapsFor and the create-token
// readers, so there is no per-method routing code here.
type Adapter struct {
	hostkit.Router
	remote *remote
	fake   *fakeadapter.Fake
}

// New returns the adapter. getenv defaults to os.Getenv.
func New(getenv func(string) string) *Adapter {
	if getenv == nil {
		getenv = os.Getenv
	}
	a := &Adapter{remote: newRemote(getenv), fake: fakeadapter.New(fakeadapter.Options{})}
	a.Router = hostkit.Router{Pick: hostkit.EndpointRoutes{
		Offline: a.fake,
		Sim:     a.remote,
		UseLive: func() bool { return getenv("LMOCI_LIVE") == "1" },
		Unavailable: func(route string) error {
			if route == "live" {
				return errLiveNotImplemented("LMOCI_LIVE=1 or a non-loopback endpoint")
			}
			return hostkit.ProviderError(hostkit.Internal, 0, "", "no "+route+" backend")
		},
	}.Pick}
	return a
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

var (
	_ hostkit.Backend                 = (*remote)(nil)
	_ hostkit.BucketCreateTokenReader = (*remote)(nil)
	_ hostkit.QueueCreateTokenReader  = (*remote)(nil)
)
