#!/bin/sh
# Workers Builds entry point for the Python Worker.
#   sh scripts/ci.sh deploy    production builds  (Deploy command:  npm run deploy:ci)
#   sh scripts/ci.sh preview   preview builds     (Preview command: npm run preview:ci)
# The build image has Python and pip but not uv, which pywrangler needs to bundle the Python
# packages. pywrangler calls `uv` from PATH, so link the pip-installed uv into a private bin dir
# and put only that first (its install dir may hold other, older tools such as node).
set -eu
python3 -m pip install --quiet 'uv>=0.12.3'
mkdir -p .ci-bin
ln -sf "$(python3 -c 'import uv; print(uv.find_uv_bin())')" .ci-bin/uv
export PATH="$PWD/.ci-bin:$PATH"
uv --version
case "${1:-deploy}" in
  deploy) uv run pywrangler deploy ;;
  # Unlike deploy, pywrangler doesn't bundle packages before `preview`, so sync first.
  preview) uv run pywrangler sync && uv run pywrangler preview ;;
  *) echo "usage: scripts/ci.sh deploy|preview" >&2; exit 2 ;;
esac
