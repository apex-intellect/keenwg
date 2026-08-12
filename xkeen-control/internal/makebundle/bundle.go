package makebundle

import (
	"archive/tar"
	"compress/gzip"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

func Build(inputDirectory, outputPath string) error {
	entries, err := os.ReadDir(inputDirectory)
	if err != nil || len(entries) == 0 {
		return errors.New("bundle input unavailable")
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
	for _, entry := range entries {
		if entry.IsDir() || entry.Type()&os.ModeSymlink != 0 || filepath.Base(entry.Name()) != entry.Name() {
			return fmt.Errorf("unsafe bundle entry: %s", entry.Name())
		}
	}
	outputDirectory := filepath.Dir(outputPath)
	if err := os.MkdirAll(outputDirectory, 0o700); err != nil {
		return err
	}
	file, err := os.CreateTemp(outputDirectory, ".keenwg-bundle-*.tmp")
	if err != nil {
		return err
	}
	temporary := file.Name()
	closed := false
	defer func() {
		if !closed {
			_ = file.Close()
		}
		_ = os.Remove(temporary)
	}()
	if err := file.Chmod(0o600); err != nil {
		return err
	}
	gzipWriter := gzip.NewWriter(file)
	gzipWriter.Header.ModTime = time.Unix(0, 0).UTC()
	gzipWriter.Header.OS = 255
	tarWriter := tar.NewWriter(gzipWriter)
	for _, entry := range entries {
		path := filepath.Join(inputDirectory, entry.Name())
		info, err := entry.Info()
		if err != nil || !info.Mode().IsRegular() {
			return fmt.Errorf("unsafe bundle entry: %s", entry.Name())
		}
		header := &tar.Header{
			Name: entry.Name(), Mode: archiveMode(entry.Name()), Size: info.Size(),
			ModTime: time.Unix(0, 0).UTC(), AccessTime: time.Time{}, ChangeTime: time.Time{},
			Uid: 0, Gid: 0, Uname: "", Gname: "", Format: tar.FormatUSTAR,
		}
		if err := tarWriter.WriteHeader(header); err != nil {
			return err
		}
		source, err := os.Open(path)
		if err != nil {
			return err
		}
		_, copyErr := io.Copy(tarWriter, source)
		closeErr := source.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
	}
	if err := tarWriter.Close(); err != nil {
		return err
	}
	if err := gzipWriter.Close(); err != nil {
		return err
	}
	if err := file.Sync(); err != nil {
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	closed = true
	if err := os.Rename(temporary, outputPath); err != nil {
		return err
	}
	return nil
}

func archiveMode(name string) int64 {
	if name == "keenwg-companion" || name == "keenwg-updater" || strings.HasSuffix(name, ".sh") || strings.HasPrefix(name, "S96") {
		return 0o755
	}
	if strings.HasSuffix(name, ".json") {
		return 0o600
	}
	return 0o644
}
