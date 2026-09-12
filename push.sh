#!/bin/bash
# 用法: bash push.sh https://github.com/<你的用户名>/<仓库名>.git
# 私有/公开都行；建议先建空仓库（不要勾选 README，否则要先 pull）
set -eu
[ $# -eq 1 ] || { echo "用法: bash push.sh <仓库地址>"; exit 1; }

cd "$(dirname "$0")"
git branch -M main
git remote remove origin 2>/dev/null || true
git remote add origin "$1"

echo "==> 推送中（如需令牌，按提示输入 GitHub Personal Access Token 作为密码）"
git push -u origin main
echo "==> 完成: $1"
