package main

import (
	"flag"
	"fmt"
	"os"

	"github.com/goldb/keenwg/xkeen-control/internal/makebundle"
)

func main() {
	input := flag.String("input", "", "flat bundle staging directory")
	output := flag.String("output", "", "output tar.gz path")
	flag.Parse()
	if *input == "" || *output == "" || flag.NArg() != 0 {
		fmt.Fprintln(os.Stderr, "usage: keenwg-makebundle -input DIR -output FILE")
		os.Exit(2)
	}
	if err := makebundle.Build(*input, *output); err != nil {
		fmt.Fprintln(os.Stderr, "bundle creation failed")
		os.Exit(1)
	}
}
