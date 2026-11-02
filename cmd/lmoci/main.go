package main

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"

	lmcode "github.com/lmctl-ai/lmcode-go"

	"github.com/lmctl-ai/lmoci/internal/host"
	"github.com/lmctl-ai/lmoci/internal/invoke"
	"github.com/lmctl-ai/lmoci/internal/membudget"
	"github.com/lmctl-ai/lmoci/internal/safety"
	"github.com/lmctl-ai/lmoci/schema"
)

const Version = "0.1.0"

func main() {
	os.Exit(run(os.Args[1:]))
}

func run(args []string) int {
	// Support the documented subcommand-per-schema pattern:
	//   lmoci cloud --lmcode FILE | --lmcode-schema [--json] | --lmcode-signatures [--without-std]
	// During the Go port pilot (Phases A-C), top-level lmoci also serves the shared lmcloud schema
	// so conformance runners invoking `<host> --lmcode FILE` work directly.
	if len(args) > 0 && args[0] == "cloud" {
		args = args[1:]
	}

	if len(args) == 0 {
		printHelp(os.Stdout)
		return 0
	}

	// 1. Metadata flags (no memory budget watchdog required).
	var (
		wantVersion    bool
		wantHelp       bool
		wantNotices    bool
		wantSchema     bool
		wantSchemaJSON bool
		wantSignatures bool
		withoutStd     bool
		wantSweep      bool
		sweepArg       string
		lmcodeFiles    []string
		manifestPath   string
		endpointFlag   string
		regionFlag     string
		forceDry       bool
	)

	for i := 0; i < len(args); i++ {
		arg := args[i]
		switch {
		case arg == "--version" || arg == "-V":
			wantVersion = true
		case arg == "--help" || arg == "-h":
			wantHelp = true
		case arg == "--notices":
			wantNotices = true
		case arg == "--lmcode-schema":
			wantSchema = true
		case arg == "--json":
			wantSchemaJSON = true
		case arg == "--lmcode-signatures":
			wantSignatures = true
		case arg == "--without-std":
			withoutStd = true
		case arg == "--sweep":
			wantSweep = true
			if i+1 < len(args) && !strings.HasPrefix(args[i+1], "-") {
				i++
				sweepArg = args[i]
			}
		case arg == "--dry":
			forceDry = true
		case arg == "--lmcode":
			if i+1 >= len(args) {
				return lmcode.FailureFrame(os.Stderr, "cli", lmcode.NewError(lmcode.CLI_USAGE, "--lmcode requires a bytecode path"))
			}
			for i+1 < len(args) && !strings.HasPrefix(args[i+1], "--") {
				i++
				lmcodeFiles = append(lmcodeFiles, args[i])
			}
			if len(lmcodeFiles) == 0 {
				return lmcode.FailureFrame(os.Stderr, "cli", lmcode.NewError(lmcode.CLI_USAGE, "--lmcode requires a bytecode path"))
			}
		case strings.HasPrefix(arg, "--lmcode="):
			val := strings.TrimPrefix(arg, "--lmcode=")
			if val == "" {
				return lmcode.FailureFrame(os.Stderr, "cli", lmcode.NewError(lmcode.CLI_USAGE, "--lmcode requires a bytecode path"))
			}
			lmcodeFiles = append(lmcodeFiles, val)
		case arg == "--manifest":
			if i+1 >= len(args) {
				return lmcode.FailureFrame(os.Stderr, "cli", lmcode.NewError(lmcode.CLI_USAGE, "--manifest requires a file path"))
			}
			i++
			manifestPath = args[i]
		case strings.HasPrefix(arg, "--manifest="):
			manifestPath = strings.TrimPrefix(arg, "--manifest=")
			if manifestPath == "" {
				return lmcode.FailureFrame(os.Stderr, "cli", lmcode.NewError(lmcode.CLI_USAGE, "--manifest requires a file path"))
			}
		case arg == "--endpoint":
			if i+1 >= len(args) {
				return lmcode.FailureFrame(os.Stderr, "cli", lmcode.NewError(lmcode.CLI_USAGE, "--endpoint requires a URL"))
			}
			i++
			endpointFlag = args[i]
		case strings.HasPrefix(arg, "--endpoint="):
			endpointFlag = strings.TrimPrefix(arg, "--endpoint=")
		case arg == "--region":
			if i+1 >= len(args) {
				return lmcode.FailureFrame(os.Stderr, "cli", lmcode.NewError(lmcode.CLI_USAGE, "--region requires a value"))
			}
			i++
			regionFlag = args[i]
		case strings.HasPrefix(arg, "--region="):
			regionFlag = strings.TrimPrefix(arg, "--region=")
		default:
			return lmcode.FailureFrame(os.Stderr, "cli", lmcode.NewError(lmcode.CLI_USAGE, fmt.Sprintf("unknown option: %s", arg)))
		}
	}

	if wantVersion {
		fmt.Printf("lmoci %s\n", Version)
		return 0
	}
	if wantHelp {
		printHelp(os.Stdout)
		return 0
	}
	if wantNotices {
		printNotices(os.Stdout)
		return 0
	}
	if wantSchema {
		_ = wantSchemaJSON
		_, _ = os.Stdout.Write(schema.EmbeddedCloudSchema)
		return 0
	}
	if wantSignatures {
		var reg *lmcode.Registry
		if withoutStd {
			reg = lmcode.NewRegistryWithoutStd()
		} else {
			reg = lmcode.NewRegistry()
		}
		if err := host.Register(reg, host.New(host.Options{})); err != nil {
			return lmcode.FailureFrame(os.Stderr, "register", err)
		}
		var sigs []string
		if withoutStd {
			sigs = reg.HostSignatures()
		} else {
			sigs = reg.Signatures()
		}
		for _, s := range sigs {
			fmt.Println(s)
		}
		return 0
	}
	if wantSweep {
		return runSweep(sweepArg)
	}

	if len(lmcodeFiles) == 0 {
		return lmcode.FailureFrame(os.Stderr, "cli", lmcode.NewError(lmcode.CLI_USAGE, "missing --lmcode FILE"))
	}

	// 2. Apply memory budget and start watchdog before loading/executing bytecode.
	if _, err := membudget.InitFromEnv(); err != nil {
		return invoke.Failure("cli", lmcode.NewError(lmcode.CLI_USAGE, err.Error()))
	}

	if forceDry {
		_ = os.Setenv("LMCODE_DRY", "1")
	}

	var manifestPtr *string
	if manifestPath != "" {
		manifestPtr = &manifestPath
	}

	results, err := lmcode.InvocationResultsFromManifest(lmcodeFiles, manifestPtr)
	if err != nil {
		return invoke.Failure("cli", err)
	}
	contract, err := lmcode.ResultContractFromSchema(schema.EmbeddedCloudSchema)
	if err != nil {
		return invoke.Failure("load", err)
	}

	reg := lmcode.NewRegistry()
	ctx := lmcode.NewContext()
	ctx.SetDry(results.Dry() || forceDry)
	ledger := defaultLedger()
	h := host.New(host.Options{
		DefaultEndpoint: endpointFlag,
		Region:          regionFlag,
		Ledger:          ledger,
	})
	if err := host.Register(reg, h); err != nil {
		return invoke.Failure("load", err)
	}
	ctx.Set("cloud", h)

	runner := &invoke.Runner{
		Results:  results,
		Registry: reg,
		Schema:   schema.EmbeddedCloudSchema,
		Contract: contract,
		Context:  ctx,
		Dry:      results.Dry(),
		Hooks: invoke.Hooks{
			BeforeRun:     membudget.ResetRun,
			StartWatchdog: membudget.StartWatchdog,
		},
	}
	return runner.Execute()
}

func defaultLedger() *safety.RunLedger {
	return safety.NewRunLedger()
}

func runSweep(runID string) int {
	_ = runID
	l := defaultLedger()
	entries := l.Snapshot()
	out := map[string]any{
		"ok":      true,
		"swept":   len(entries),
		"entries": entries,
	}
	enc := json.NewEncoder(os.Stdout)
	_ = enc.Encode(out)
	return 0
}

func printHelp(w *os.File) {
	fmt.Fprintf(w, `lmoci %s — Go lmcode host for OCI (shared lmcloud schema)

Usage:
  lmoci [cloud] --lmcode FILE [--manifest FILE] [--endpoint URL] [--region REGION] [--dry]
  lmoci [cloud] --lmcode-schema [--json]
  lmoci [cloud] --lmcode-signatures [--without-std]
  lmoci --sweep [RUN_ID]
  lmoci --notices
  lmoci --version | -V
  lmoci --help | -h

Subcommands:
  cloud                 Select the shared lmcloud provider-neutral schema (default in pilot).

Environment Variables:
  LMOCI_ENDPOINT              Loopback/simulator or custom OCI endpoint URL
  LMOCI_REGION                OCI region (default: us-ashburn-1)
  LMOCI_NAMESPACE             OCI Object Storage namespace override
  LMOCI_COMPARTMENT_ID        OCI compartment OCID override
  LMOCI_TENANCY               OCI tenancy OCID
  LMOCI_USER                  OCI user OCID
  LMOCI_FINGERPRINT           OCI API key fingerprint
  LMOCI_PRIVATE_KEY_FILE      Path to OCI RSA private key PEM file
  LMOCI_PRIVATE_KEY           Inline OCI RSA private key PEM
  LMOCI_SIM_KEY_ID            Simulator full keyId (<tenancy>/<user>/<fingerprint>)
  LMOCI_SIM_PRIVATE_KEY_PATH  Simulator RSA private key PEM path
  LMOCI_LIVE                  Set to 1 to allow live OCI calls when no endpoint is set
  LMOCI_ALLOW_LIVE_IN_CI      Set to 1 alongside LMOCI_LIVE=1 to allow live calls in CI
  LMOCI_MAX_MEMORY_MB         Memory ceiling in MiB (16..65536, default: 4096)
`, Version)
}

func printNotices(w *os.File) {
	fmt.Fprintln(w, `lmoci Third-Party Software Notices
==================================

1. github.com/lmctl-ai/lmcode-go
   License: MIT
   Copyright (c) lmctl-ai

2. github.com/oracle/oci-go-sdk/v65
   License: Universal Permissive License (UPL) 1.0 or Apache License 2.0
   Copyright (c) Oracle and/or its affiliates.`)
}
