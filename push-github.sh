#!/data/data/com.termux/files/usr/bin/bash
set -e

if [ -z "${1:-}" ]; then
    echo "Usage: ./push-github.sh https://github.com/USERNAME/REPOSITORY.git"
    exit 1
fi

git init
git branch -M main

git config user.name "A01 Mirror"
git config user.email "a01mirror@localhost"

git add .
git commit -m "A01 Mirror initial release" || true

if git remote get-url origin >/dev/null 2>&1; then
    git remote set-url origin "$1"
else
    git remote add origin "$1"
fi

git push -u origin main
