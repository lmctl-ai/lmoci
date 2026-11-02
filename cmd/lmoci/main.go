// SPDX-License-Identifier: MIT

// Command lmoci serves the shared lmcloud schema for OCI. Everything provider
// neutral comes from github.com/lmctl-ai/lmcloud/hostkit; this binary adds the
// OCI adapter (internal/oci), lmoci's CLI surface and its memory watchdog.
package main

import (
	"fmt"
	"io"
	"os"

	lmcode "github.com/lmctl-ai/lmcode-go"

	"github.com/lmctl-ai/lmcloud/hostkit/cloudhost"
	"github.com/lmctl-ai/lmcloud/hostkit/invoke"
	"github.com/lmctl-ai/lmcloud/schema"

	"github.com/lmctl-ai/lmoci/internal/membudget"
	"github.com/lmctl-ai/lmoci/internal/oci"
)

const Version = "0.1.0"

func main() { os.Exit(run(os.Args[1:], os.Stdout)) }

func run(args []string, out io.Writer) int {
	// `lmoci cloud ...` selects the shared schema (the only schema served).
	if len(args) > 0 && args[0] == "cloud" {
		args = args[1:]
	}
	switch {
	case len(args) == 0 || (len(args) == 1 && (args[0] == "--help" || args[0] == "-h")):
		printHelp(out)
		return 0
	case len(args) == 1 && (args[0] == "--version" || args[0] == "-V"):
		fmt.Fprintf(out, "lmoci %s\n", Version)
		return 0
	case len(args) == 1 && args[0] == "--notices":
		printNotices(out)
		return 0
	case args[0] == "--lmcode-schema" && (len(args) == 1 || (len(args) == 2 && args[1] == "--json")):
		_, _ = out.Write(schema.Bytes)
		return 0
	case args[0] == "--lmcode-signatures" && (len(args) == 1 || (len(args) == 2 && args[1] == "--without-std")):
		return signatures(out, len(args) == 2)
	case invoke.IsInvocation(args):
		return invocation(args, out)
	}
	return invoke.Failure("cli", lmcode.NewError(lmcode.CLI_USAGE, fmt.Sprintf("unknown or malformed arguments: %q (see lmoci --help)", args)))
}

func signatures(out io.Writer, withoutStd bool) int {
	reg := lmcode.NewRegistry()
	if withoutStd {
		reg = lmcode.NewRegistryWithoutStd()
	}
	if err := cloudhost.Register(reg, io.Discard); err != nil {
		return invoke.Failure("app", lmcode.NewError(lmcode.APP_FAIL, err.Error()))
	}
	sigs := reg.Signatures()
	if withoutStd {
		sigs = reg.HostSignatures()
	}
	for _, s := range sigs {
		fmt.Fprintln(out, s)
	}
	return 0
}

// invocation mirrors cloudhost.Program.Main but adds lmoci's memory budget
// hooks (Program has no Hooks field; see durable-memory/go-port-findings.md).
func invocation(args []string, out io.Writer) int {
	if _, err := membudget.InitFromEnv(); err != nil {
		return invoke.Failure("cli", lmcode.NewError(lmcode.CLI_USAGE, err.Error()))
	}
	inv, err := invoke.ParseArgs(args)
	if err != nil {
		return invoke.Failure("cli", err)
	}
	reg, err := cloudhost.NewRegistry(out)
	if err != nil {
		return invoke.Failure("app", lmcode.NewError(lmcode.APP_FAIL, err.Error()))
	}
	contract, err := lmcode.ResultContractFromSchema(schema.Bytes)
	if err != nil {
		return invoke.Failure("load", err)
	}
	adapter := oci.New(nil)
	ctx := lmcode.NewContext()
	ctx.Set("cloud", cloudhost.New(cloudhost.Config{Provider: "oci", Store: adapter, Queue: adapter, Endpoints: oci.Endpoints()}))
	runner := &invoke.Runner{
		Results: inv.Results, Registry: reg, Schema: schema.Bytes, Contract: contract, Context: ctx,
		Order: inv.Order, Dry: inv.Dry, Builder: inv.Builder, Out: out,
		Hooks: invoke.Hooks{BeforeRun: membudget.ResetRun, StartWatchdog: membudget.StartWatchdog},
	}
	return runner.Execute()
}

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
