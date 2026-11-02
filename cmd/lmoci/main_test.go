// SPDX-License-Identifier: MIT

package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"strings"
	"testing"

	"github.com/lmctl-ai/lmcloud/schema"
)

// The schema served (hostkit's embedded bytes) must be the bytes pinned in lmcloud.lock.
func TestServedSchemaMatchesLock(t *testing.T) {
	raw, err := os.ReadFile("../../lmcloud.lock")
	if err != nil {
		t.Fatal(err)
	}
	var lock struct {
		SHA256 string `json:"sha256"`
	}
	if err := json.Unmarshal(raw, &lock); err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	if code := run([]string{"cloud", "--lmcode-schema", "--json"}, &out); code != 0 {
		t.Fatalf("exit %d", code)
	}
	sum := sha256.Sum256(out.Bytes())
	if got := hex.EncodeToString(sum[:]); got != lock.SHA256 || !bytes.Equal(out.Bytes(), schema.Bytes) {
		t.Fatalf("served schema sha256 %s, lmcloud.lock pins %s", got, lock.SHA256)
	}
}

func TestCLISurface(t *testing.T) {
	cases := []struct {
		args []string
		code int
		want string
	}{
		{[]string{"--version"}, 0, "lmoci 0.1.0"},
		{[]string{"cloud", "-V"}, 0, "lmoci 0.1.0"},
		{[]string{"--help"}, 0, "lmoci 0.1.0"},
		{[]string{"--notices"}, 0, "github.com/oracle/oci-go-sdk/v65"},
		{[]string{"--lmcode-signatures", "--without-std"}, 0, "method Cloud.queue_nack(string, string, int) -> string"},
		{[]string{"--unknown-flag"}, 2, "LMCODE_RETURN_BEGIN"},
		{[]string{"--sweep"}, 2, "CLI_USAGE"},
		{[]string{"--lmcode"}, 2, "CLI_USAGE"},
	}
	for _, c := range cases {
		var out bytes.Buffer
		code := run(c.args, &out)
		if code != c.code {
			t.Errorf("%v: exit %d, want %d", c.args, code, c.code)
		}
		if c.code == 0 && !strings.Contains(out.String(), c.want) {
			t.Errorf("%v: output lacks %q", c.args, c.want)
		}
	}
}
