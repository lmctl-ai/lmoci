module github.com/lmctl-ai/lmoci

go 1.26.10

// github.com/lmctl-ai/lmcloud (hostkit) is not published yet: it resolves to the
// local checkout through the replace directive below (branch shared-go).
require (
	github.com/lmctl-ai/lmcloud v0.0.0-00010101000000-000000000000
	github.com/lmctl-ai/lmcode-go v0.6.39-0.20261031234045-77932bf7134a
	github.com/oracle/oci-go-sdk/v65 v65.128.0
)

require (
	github.com/gofrs/flock v0.10.0 // indirect
	github.com/sony/gobreaker/v2 v2.4.0 // indirect
	github.com/youmark/pkcs8 v0.0.0-20240726163527-a2c0da244d78 // indirect
	golang.org/x/crypto v0.56.0 // indirect
	golang.org/x/sys v0.47.0 // indirect
)

replace github.com/lmctl-ai/lmcloud => ../lmcloud

// TEMPORARY: lmcode-go Registry.Enum (dry runs that evaluate ErrorCode) is not
// published yet; ../lmcode-go-patched is branch dry-enum-string (23ff76b).
// Remove when Registry.Enum is merged upstream and the lmcode-go pin above and
// sdk.lock are bumped (hostkit's go.mod has the same line).
replace github.com/lmctl-ai/lmcode-go => ../lmcode-go-patched
