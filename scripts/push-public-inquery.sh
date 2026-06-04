#!/usr/bin/env bash
# Push a clean single-commit snapshot to the public jeonghyeondang/inquery repo.
# Always rebuilds from current HEAD so history never carries old snapshots.
# Prerequisite: public repo at https://github.com/jeonghyeondang/inquery

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXPORT_DIR="$ROOT/.public-export-tmp"

echo "Rebuilding public snapshot from current HEAD..."
rm -rf "$EXPORT_DIR"
mkdir -p "$EXPORT_DIR"
git -C "$ROOT" archive HEAD | tar -x -C "$EXPORT_DIR"
rm -f "$EXPORT_DIR/CLAUDE.local.md"
git -C "$EXPORT_DIR" init -b main
git -C "$EXPORT_DIR" add .
git -C "$EXPORT_DIR" commit -m "Initial public release"

git -C "$EXPORT_DIR" remote remove origin 2>/dev/null || true
git -C "$EXPORT_DIR" remote add origin git@github.com:jeonghyeondang/inquery.git
git -C "$EXPORT_DIR" push --force -u origin main

echo "Done: https://github.com/jeonghyeondang/inquery"
