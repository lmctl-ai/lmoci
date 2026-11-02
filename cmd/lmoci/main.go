// SPDX-License-Identifier: MIT

// Command lmoci serves the shared lmcloud schema for OCI. Everything provider
// neutral comes from github.com/lmctl-ai/lmcloud/hostkit; this binary adds the
// OCI adapter (internal/oci), lmoci's CLI surface and its memory watchdog.
package main

import (
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/lmctl-ai/lmcloud/hostkit/cloudhost"
	"github.com/lmctl-ai/lmcloud/hostkit/invoke"
	"github.com/lmctl-ai/lmcloud/schema"

	"github.com/lmctl-ai/lmoci/internal/membudget"
	"github.com/lmctl-ai/lmoci/internal/oci"
)

const Version = "0.1.0"

func main() { os.Exit(run(os.Args[1:], os.Stdout)) }

// program is lmoci's CLI: hostkit's Program with the OCI adapter, lmoci's
// help/notices and the memory budget (Init) + watchdog (Hooks).
func program(out io.Writer) cloudhost.Program {
	var help, notices strings.Builder
	printHelp(&help)
	printNotices(&notices)
	return cloudhost.Program{
		Name: "lmoci", Version: Version, Schema: schema.Bytes, Out: out,
		Help: help.String(), Notices: notices.String(),
		Init: func() error {
			_, err := membudget.InitFromEnv()
			return err
		},
		Hooks: invoke.Hooks{BeforeRun: membudget.ResetRun, StartWatchdog: membudget.StartWatchdog},
		NewConfig: func(bool) (cloudhost.Config, error) {
			adapter := oci.New(nil)
			return cloudhost.Config{Provider: "oci", Store: adapter, Queue: adapter, Endpoints: oci.Endpoints()}, nil
		},
	}
}

func run(args []string, out io.Writer) int { return program(out).Main(args) }

func printHelp(w io.Writer) {
	fmt.Fprintf(w, `lmoci %s — Go lmcode host for OCI (shared lmcloud schema, built on lmcloud hostkit)

Usage:
  lmoci [cloud] [--lmcode|--first|--last] FILE ...
  lmoci [cloud] --lmcode-schema [--json]
  lmoci [cloud] --lmcode-signatures [--without-std]
  lmoci --notices | --version | -V | --help | -h

Dry runs come from the caller's invocation manifest (lmscript --dry-run).

Endpoints (validated; plain http only for loopback; see lmcloud design 02 §3.3):
  set_endpoint(service|"all", url) > LMCLOUD_<SVC>_ENDPOINT > LMOCI_<SVC>_ENDPOINT >
  LMCLOUD_ENDPOINT > LMOCI_ENDPOINT > OCI_OBJECT_STORAGE_ENDPOINT / OCI_QUEUE_ENDPOINT > OCI_ENDPOINT
  No endpoint: offline in-process fake. LMOCI_LIVE=1 or a non-loopback endpoint:
  not supported yet (live OCI credential chain / ~/.oci/config is a TODO).

Simulator credentials and scope (loopback endpoints only):
  LMOCI_SIM_KEY_ID            keyId <tenancy>/<user>/<fingerprint> (default: synthetic open-mode id)
  LMOCI_SIM_PRIVATE_KEY_PATH  RSA private key PEM (default: ephemeral key, open mode only)
  LMOCI_SIM_NAMESPACE         namespace (default: GET /n/)
  LMOCI_SIM_COMPARTMENT_ID    compartment (default: tenancy from the keyId)

Other:
  LMOCI_MAX_MEMORY_MB         memory ceiling in MiB (default 4096)
`, Version)
}

func printNotices(w io.Writer) {
	fmt.Fprintln(w, `lmoci Third-Party Software Notices
==================================

1. github.com/lmctl-ai/lmcode-go
   License: MIT
   Copyright (c) lmctl-ai

2. github.com/lmctl-ai/lmcloud (hostkit)
   License: MIT
   Copyright (c) lmctl-ai

3. github.com/oracle/oci-go-sdk/v65 (request signing only)
   License: Universal Permissive License (UPL) 1.0 or Apache License 2.0
   Copyright (c) Oracle and/or its affiliates.`)
}
