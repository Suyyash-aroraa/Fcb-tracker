"""Dev-only egress relay: GET /?url=<https URL> fetches it via the system proxy.

Some sandboxes let command-line tools out through an HTTPS proxy but give wrangler's local runtime
no internet access. Run this, then `wrangler dev --var DEV_FETCH_RELAY:http://127.0.0.1:8798`, so
the Worker's live fetches work locally. Deployed Workers never use it."""
import http.server, urllib.parse, urllib.request
ALLOWED = ("site.web.api.espn.com", "site.api.espn.com", "prod-public-api.livescore.com")
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        url = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).get("url", [""])[0]
        if urllib.parse.urlparse(url).hostname not in ALLOWED:
            self.send_response(403); self.end_headers(); return
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"accept": "application/json"}), timeout=20) as r:
                body, status = r.read(), r.status
        except urllib.error.HTTPError as e:
            body, status = e.read(), e.code
        self.send_response(status); self.send_header("content-type", "application/json"); self.end_headers(); self.wfile.write(body)
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("127.0.0.1", 8798), H).serve_forever()
