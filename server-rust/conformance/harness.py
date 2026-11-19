#!/usr/bin/env python3
"""Runs one scripted conversation against the Kotlin server and against the Rust one, and compares
what each said and what each left on disk.

    conformance/harness.py run [--seed N] [--work DIR] [--steps N] [--phases a,b]   both, then the differences
    conformance/harness.py record kotlin|rust OUT.json [--seed N] ...               one of them
    conformance/harness.py compare A.json B.json

The Kotlin server is the specification: where the two differ, the Rust one is wrong, unless the
difference is listed in ALLOWED below with its reason. Every note is made up here; the servers run
on scratch folders and on ports of their own.

Needs `./gradlew :server:installDist` and `cargo build --release` to have been run.
"""
import argparse, gzip, hashlib, html, http.client, json, os, random, re, shutil, signal, socket, ssl
import subprocess, sys, tempfile, threading, time, unicodedata, zlib, urllib.parse
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
# Where each server is, unless the environment says otherwise: on another system the Rust one is another binary.
COMMANDS = {
    "kotlin": [os.environ.get("KORGNOTE_KOTLIN_SERVER", str(REPO / "server/build/install/server/bin/server"))],
    "rust": [os.environ.get("KORGNOTE_RUST_SERVER", str(REPO / "server-rust/target/release/korgnote-server"))],
}
PORTS = {"kotlin": (18180, 18543), "rust": (18280, 18643)}
ADDRESS = "192.168.77.7"

# Headers whose values must be the same from both servers.
COMPARED_HEADERS = ["content-type", "etag", "location", "content-encoding", "cache-control", "allow", "vary"]

WEB_FILES = {
    "index.html": b"<html><body>made-up web client</body></html>\n",
    "app.js": b"console.log('made up');\n" * 40,
    "module.wasm": b"\0asm\1\0\0\0" + b"\0" * 2000,
    "styles.css": b"body{margin:0}\n",
    "sub/data.json": b'{"made":"up"}\n',
    "sub/index.html": b"<html>an index in a folder</html>\n",
    "app.js.map": b'{"version":3,"sources":[]}\n' * 20,
    "app.js.LICENSE.txt": b"A made-up licence.\n" * 20,
    "composeResources/strings.commonMain.cvr": b"version:0\nstring|made|dXA=\n" * 20,
    "composeResources/drawable/icon.xml": b"<vector/>\n" * 30,
    "picture.svg": b"<svg xmlns='http://www.w3.org/2000/svg'/>\n" * 10,
    "picture.png": b"\x89PNG\r\n\x1a\n" + b"\0" * 400,
    "picture.jpg": b"\xff\xd8\xff" + b"\0" * 400,
    "picture.gif": b"GIF89a" + b"\0" * 400,
    "font.ttf": b"\0\1\0\0" + b"\0" * 400,
    "font.woff2": b"wOF2" + b"\0" * 400,
    "favicon.ico": b"\0\0\1\0" + b"\0" * 400,
    "data.bin": b"\1\2\3" * 200,
    "noextension": b"no extension\n" * 30,
    "manifest.webmanifest": b"{}\n" * 100,
    "page.htm": b"<html>htm</html>\n" * 20,
    "module.mjs": b"export default 1\n" * 20,
    "notes.md": b"# made up\n" * 30,
    "table.csv": b"a,b\n" * 80,
    "doc.pdf": b"%PDF-1.4\n" + b"\0" * 400,
    "movie.mp4": b"\0\0\0\x18ftyp" + b"\0" * 400,
    "sound.mp3": b"ID3" + b"\0" * 400,
    "archive.zip": b"PK\3\4" + b"\0" * 400,
    "UPPER.HTML": b"<html>upper</html>\n" * 20,
    ".hidden.txt": b"a dot file in the web folder\n" * 10,
    "with space.txt": b"spaced\n" * 40,
    "small.txt": b"small\n",
}


def etag_of(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()[:32]


def lossy(data: bytes) -> bytes:
    """What a body becomes on its way through a string, as both servers take it."""
    return data.decode("utf-8", errors="replace").encode("utf-8")


class Server:
    def __init__(self, impl, work: Path, state_from=None, variant="main", env=None, notes_through_link=False, make_notes=True):
        self.impl = impl
        self.http_port, self.https_port = PORTS[impl]
        self.dir = work / f"{impl}-{variant}"
        shutil.rmtree(self.dir, ignore_errors=True)
        self.real_notes = self.dir / ("real-notes" if notes_through_link else "notes")
        self.notes = self.dir / "notes"
        self.state = self.dir / "state"
        self.web = self.dir / "web"
        self.outside = self.dir / "outside"
        for d in (self.web, self.outside):
            d.mkdir(parents=True)
        if make_notes:
            self.real_notes.mkdir(parents=True)
            if notes_through_link:
                os.symlink(self.real_notes, self.notes)
        if state_from is not None:
            shutil.copytree(state_from, self.state)
        for relative, data in WEB_FILES.items():
            target = self.web / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        (self.outside / "secret.txt").write_bytes(b"outside the web folder\n" * 20)
        os.symlink(self.outside / "secret.txt", self.web / "link-out.txt")
        os.symlink(self.web / "small.txt", self.web / "link-in.txt")
        self.extra_env = env or {}
        self.process = None
        self.log = None

    def start(self, expect_failure=False):
        env = dict(os.environ)
        for name in list(env):
            if name.startswith("KORGNOTE_") or name in ("NOTES_DIR", "WASM_DIR", "JAVA_OPTS", "SERVER_OPTS"):
                del env[name]
        env.update(
            NOTES_DIR=str(self.notes), KORGNOTE_STATE_DIR=str(self.state), WASM_DIR=str(self.web),
            KORGNOTE_PORT=str(self.http_port), KORGNOTE_HTTPS_PORT=str(self.https_port),
            KORGNOTE_HTTP_HOST="127.0.0.1", KORGNOTE_ADVERTISED_ADDRESSES=ADDRESS,
            KORGNOTE_SERVER_NAME="Test Laptop", TZ="UTC",
        )
        env.update(self.extra_env)
        self.log = open(self.dir / "server.log", "ab")
        self.process = subprocess.Popen(COMMANDS[self.impl], env=env, cwd=self.dir, stdout=self.log, stderr=self.log)
        if expect_failure:
            try:
                return self.process.wait(30)
            except subprocess.TimeoutExpired:
                return None
        deadline = time.time() + 60
        while time.time() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError(f"{self.impl} server stopped at startup; see {self.dir}/server.log")
            try:
                status, _, _ = request(self.http_port, "GET", "/api/")
                if status == 200 and self._tls_up():
                    return None
            except OSError:
                pass
            time.sleep(0.2)
        raise RuntimeError(f"{self.impl} server did not answer; see {self.dir}/server.log")

    def _tls_up(self):
        try:
            request(self.https_port, "GET", "/api/", tls=True)
            return True
        except OSError:
            return False

    def stop(self):
        if self.process and self.process.poll() is None:
            self.process.send_signal(signal.SIGTERM)
            try:
                self.process.wait(20)
            except subprocess.TimeoutExpired:
                self.process.kill()
        if self.log:
            self.log.close()


def request(port, method, path, headers=None, body=None, tls=False, host=None, timeout=60):
    """One request, the path sent exactly as written. Returns (status, headers, body), the body decoded."""
    if tls:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        connection = http.client.HTTPSConnection("127.0.0.1", port, timeout=timeout, context=context)
    else:
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        connection.putrequest(method, path, skip_host=True, skip_accept_encoding=True)
        if host != "<none>":
            connection.putheader("Host", host if host is not None else f"127.0.0.1:{port}")
        sent = dict(headers or {})
        if body is not None:
            sent.setdefault("Content-Length", str(len(body)))
        for name, value in sent.items():
            connection.putheader(name, value)
        connection.endheaders(body)
        response = connection.getresponse()
        data = response.read()
        got = {}
        for name, value in response.getheaders():
            got.setdefault(name.lower(), value)
        encoding = got.get("content-encoding")
        if encoding == "gzip":
            data = gzip.decompress(data)
        elif encoding == "deflate":
            try:
                data = zlib.decompress(data)
                got["x-deflate-is"] = "zlib-wrapped"
            except zlib.error:
                data = zlib.decompress(data, -zlib.MAX_WBITS)
                got["x-deflate-is"] = "raw"
        return response.status, got, data
    finally:
        connection.close()


def raw_request(port, text: bytes, timeout=10) -> str:
    """Bytes onto a socket, for what no HTTP library will send; returns the status code, or what came."""
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout) as s:
            s.sendall(text)
            data = b""
            try:
                while b"\r\n" not in data:
                    chunk = s.recv(4096)
                    if not chunk:
                        break
                    data += chunk
            except socket.timeout:
                pass
    except OSError as error:
        return type(error).__name__
    line = data.split(b"\r\n")[0].decode("latin-1")
    parts = line.split(" ")
    return parts[1] if len(parts) > 1 and line.startswith("HTTP/") else (line[:40] or "nothing")


class EventStream:
    """The change stream, read on a thread into lines."""

    def __init__(self, port, tls=False, token=None):
        raw = socket.create_connection(("127.0.0.1", port), timeout=120)
        if tls:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
            raw = context.wrap_socket(raw)
        self.socket = raw
        auth = f"Authorization: Bearer {token}\r\n" if token else ""
        raw.sendall(f"GET /api/v1/events HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n{auth}Accept: text/event-stream\r\nAccept-Encoding: gzip\r\n\r\n".encode())
        self.buffer = b""
        self.lock = threading.Lock()
        self.ended = False
        self.thread = threading.Thread(target=self._read, daemon=True)
        self.thread.start()

    def _read(self):
        try:
            while True:
                chunk = self.socket.recv(65536)
                if not chunk:
                    break
                with self.lock:
                    self.buffer += chunk
        except OSError:
            pass
        self.ended = True

    def frames(self):
        """The stream so far, un-chunked, as (head, lines)."""
        with self.lock:
            data = self.buffer
        if b"\r\n\r\n" not in data:
            return None, []
        head, body = data.split(b"\r\n\r\n", 1)
        head = head.decode("latin-1")
        if "chunked" in head.lower():
            out, rest = b"", body
            while rest:
                line, _, after = rest.partition(b"\r\n")
                try:
                    size = int(line.split(b";")[0], 16)
                except ValueError:
                    break
                if len(after) < size + 2 or size == 0:
                    break
                out += after[:size]
                rest = after[size + 2:]
            body = out
        return head, body.decode("utf-8", errors="replace").split("\n")

    def close(self):
        try:
            self.socket.close()
        except OSError:
            pass


# --- Making a transcript comparable ---

UUID = re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b")
TIME = re.compile(r'<time datetime="[^"]*">[^<]*</time>')
SVG = re.compile(r"<svg.*?</svg>", re.S)
TERMINAL_QR = re.compile(r"((?:\x1b\[30;107m[^\n]*\x1b\[0m\n)+)")
SINCE = re.compile(r"since=(\d{10,})")


class Normalizer:
    """Generations count from each server's start, and codes, tokens and ids are random: generations
    are written as how far they are from the server's newest, and the rest named in the order they
    appear, so that two servers doing the same thing say the same thing."""

    def __init__(self, ports=()):
        self.current = None
        self.names = {}
        self.ports = ports
        self.drawn = []
        # How far the watcher has got depends on when it is asked; only where it was waited for are its counts compared.
        self.strict_counts = False
        # Set where a `since` is taken from a change that was seen, not counted back from now.
        self.since_name = None

    def generation(self, value):
        return value if self.current is None else f"now{value - self.current:+d}"

    def name(self, kind, value):
        key = (kind, value)
        if key not in self.names:
            self.names[key] = f"<{kind}-{sum(1 for k in self.names if k[0] == kind) + 1}>"
        return self.names[key]

    def json_value(self, value):
        if isinstance(value, dict):
            out = {}
            for k, v in value.items():
                if k == "generation" and isinstance(v, int):
                    out[k] = self.generation(v)
                elif k == "mtime" and isinstance(v, int):
                    out[k] = "<mtime>"
                elif k == "token" and isinstance(v, str):
                    out[k] = self.name("token", v)
                elif k == "deviceId" and isinstance(v, str):
                    out[k] = self.name("device", v)
                elif k == "deleted" and isinstance(v, list):
                    # One walk of the tree finds several deletions at once, and tells of them in the
                    # order of a hash table in Kotlin and by path in Rust. No client minds.
                    out[k] = sorted(v)
                else:
                    out[k] = self.json_value(v)
            return out
        if isinstance(value, list):
            return [self.json_value(v) for v in value]
        return value

    def text(self, text):
        svg = SVG.search(text)
        link = re.search(r"<code>(korgnote://[^<]+)</code>", text)
        if svg and link:
            self.drawn.append({"link": html.unescape(link.group(1)), "svg": svg.group(0)})
        picture = TERMINAL_QR.search(text)
        link = re.search(r"Or paste this link there:\n(korgnote://\S+)\n", text)
        if picture and link:
            self.drawn.append({"link": link.group(1), "terminal": picture.group(1)})
        for port, name in zip(self.ports, ("<port>", "<tls-port>")):
            text = text.replace(str(port), name)
        text = re.sub(r" · folders changed \d+ times?", " · folders changed <n> times", text)
        if not self.strict_counts:
            text = re.sub(r"\d+ notes? in \d+ folders?", "<n> notes in <n> folders", text)
            text = re.sub(r" · last edit seen <time[^>]*>[^<]*</time>", "", text)
        text = TIME.sub("<time/>", text)
        text = SVG.sub("<svg/>", text)
        text = TERMINAL_QR.sub("<qr>\n", text)
        text = UUID.sub(lambda m: self.name("uuid", m.group(0)), text)
        text = re.sub(r"([?&;]c=)([0-9a-f]{32})", lambda m: m.group(1) + self.name("code", m.group(2)), text)
        return text

    def request_line(self, line):
        if self.since_name is not None:
            return SINCE.sub(f"since=<{self.since_name}>", line)
        if self.current is None:
            return line
        return SINCE.sub(lambda m: f"since=<now{int(m.group(1)) - self.current:+d}>" if abs(int(m.group(1)) - self.current) < 10**7 else m.group(0), line)

    def body(self, headers, data: bytes):
        kind = headers.get("content-type", "")
        if len(data) > 200_000:
            if kind.startswith("application/json"):
                data = re.sub(rb'"generation":\d+', b'"generation":N', data)
            return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        if kind.startswith("application/json"):
            try:
                parsed = [self.json_value(json.loads(l)) if l.strip() else l for l in text.split("\n")]
                # Key order is part of what is compared: json.loads keeps it.
                return {"json": [json.dumps(p, ensure_ascii=False) if not isinstance(p, str) else p for p in parsed]}
            except ValueError:
                pass
        return {"text": self.text(text)}


class Recorder:
    def __init__(self, server: Server):
        self.server = server
        self.norm = Normalizer((server.http_port, server.https_port))
        self.entries = []
        self.known_generation = 0

    def note(self, label, value):
        self.entries.append({"step": label, "value": value})

    def current(self):
        """The server's newest generation, asked cheaply: a delta from the last one known."""
        try:
            _, _, data = request(self.server.http_port, "GET", f"/api/v1/manifest?since={self.known_generation}")
            self.known_generation = json.loads(data)["generation"]
        except Exception:
            pass
        return self.known_generation

    def call(self, label, method, path, headers=None, body=None, tls=False, host=None, record=True):
        port = self.server.https_port if tls else self.server.http_port
        if record and ("/manifest" in path or "/bundle" in path):
            self.norm.current = self.current()
        try:
            status, got, data = request(port, method, path, headers, body, tls=tls, host=host)
        except Exception as error:  # A refused or broken connection is an answer too.
            if record:
                label = label.replace(str(self.server.http_port), "<port>")
                self.entries.append({"step": label, "request": self.norm.request_line(f"{method} {path}"), "error": type(error).__name__})
            return None, {}, b""
        if record:
            compared = COMPARED_HEADERS + ["x-deflate-is"]
            # Each server has ports of its own, and a step that names one names it the same for both.
            ported = lambda text: UUID.sub("<id>", text.replace(str(self.server.http_port), "<port>").replace(str(self.server.https_port), "<tls-port>"))
            self.entries.append({
                "step": ported(label),
                "request": ported(self.norm.request_line(f"{'tls ' if tls else ''}{method} {path}")),
                "sent": {k: ported(v) for k, v in (headers or {}).items() if k.lower() != "authorization"},
                "status": status,
                "headers": {h: got[h] for h in compared if h in got},
                "other_headers": sorted(h for h in got if h not in compared and h not in ("date", "server")),
                "body": self.norm.body(got, data) if method != "HEAD" else {"bytes": len(data)},
            })
        return status, got, data

    def settle(self):
        """Waits for the watcher to have said all it has to say about what was done so far. On macOS the
        JDK polls for changes, and what it finds late it announces late, in the middle of what is being compared."""
        time.sleep(12 if self.server.impl == "kotlin" and sys.platform == "darwin" else 2)

    def wait_for_journal(self, since, paths, timeout=45):
        """Until the server has noticed an edit made behind its back to each of [paths]."""
        deadline = time.time() + timeout
        seen = set()
        while time.time() < deadline:
            _, _, data = request(self.server.http_port, "GET", f"/api/v1/manifest?since={since}")
            manifest = json.loads(data)
            if manifest["complete"]:
                return False
            seen = {e["path"] for e in manifest["entries"]} | set(manifest["deleted"])
            if set(paths) <= seen:
                return True
            time.sleep(0.1)
        return False

    def tree(self):
        """What is on disk under the notes folder, the trash's date folders named alike."""
        out = {}
        for root, dirs, files in os.walk(self.server.real_notes):
            dirs.sort()
            for name in sorted(files):
                full = Path(root) / name
                relative = full.relative_to(self.server.real_notes).as_posix()
                relative = re.sub(r"^\.korgnote-trash/\d{4}-\d{2}-\d{2}/", ".korgnote-trash/<day>/", relative)
                if full.is_symlink():
                    out[relative] = "link"
                    continue
                mode = oct(full.stat().st_mode & 0o777)
                out[relative] = f"{hashlib.sha256(full.read_bytes()).hexdigest()[:16]} {mode}"
            for name in dirs:
                full = Path(root) / name
                if not full.is_symlink() and not any(full.iterdir()):
                    out[full.relative_to(self.server.real_notes).as_posix() + "/"] = "empty folder"
        return out


# --- The conversation ---

def external_write(server: Server, relative: str, data: bytes, mtime=None):
    """A save by some other program: whole, as an editor renaming its temporary file into place."""
    target = server.notes / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = server.outside / f"tmp-{time.time_ns()}"
    temporary.write_bytes(data)
    if mtime is not None:
        os.utime(temporary, (mtime, mtime))
    os.replace(temporary, target)


def seed_library(server: Server):
    old = time.time() - 3600
    files = {
        "inbox.org": b"* TODO Buy made-up milk\n* DONE Invent a note\n",
        "projects/work.org": b"#+title: Work\n* Meeting :made:up:\n  SCHEDULED: <2026-11-20 Fri>\n",
        "projects/deep/er/leaf.org": b"* Leaf\n",
        "journal/2026-11-19.org": "* Caf\u00e9 \u2615 notes\nna\u00efve text\n".encode(),
        "with space/a b.org": b"* spaced\n",
        "sp#cial/q?mark%20.org": b"* odd name\n",
        "accent/\u00e9t\u00e9.org": b"* summer\n",
        "accent/" + unicodedata.normalize("NFD", "d\u00e9compos\u00e9.org"): b"* written with its accents apart\n",
        "order/\U0001F600 emoji.org": b"* above the basic plane\n",
        "order/\uff5e fullwidth.org": b"* high in the basic plane\n",
        "order/\ue000 private.org": b"* private use\n",
        "order/z last ascii.org": b"* ascii\n",
        ".plan.org": b"* a dot file, still a note\n",
        ".stversions/inbox~20260101.org": b"* an old version, not a note\n",
        "sub/.hidden/secret.org": b"* hidden\n",
        "notes.txt": b"not a note\n",
        "empty.org": b"",
        "crlf.org": b"* one\r\n* two\r\n",
        "quotes.org": b'* "quoted" \\ back\tslash\x0b and \x01 control \x7f\n',
        "invalid-utf8.org": b"* bad \xff\xfe bytes \xc3\x28 and \xe2\x82 cut \xed\xa0\x80 surrogate \xf4\x90\x80\x80 beyond \xc0\xaf overlong\n",
        "bom.org": b"\xef\xbb\xbf* with a mark\n",
        "unicode.org": "* line\u2028separator and \u0085 nel and \ufeff mark and \U0001F600\n".encode(),
        "big.org": (b"* A long made-up note\n" + b"lorem ipsum dolor sit amet " * 40 + b"\n") * 300,
    }
    for relative, data in files.items():
        external_write(server, relative, data, mtime=old)
    os.symlink(server.notes / "inbox.org", server.notes / "also-inbox.org")
    (server.outside / "outside.org").write_bytes(b"* outside the library\n")
    os.symlink(server.outside / "outside.org", server.notes / "linked-out.org")
    (server.outside / "folder").mkdir(exist_ok=True)
    os.symlink(server.outside / "folder", server.notes / "linked-folder")
    os.symlink(server.notes / "nowhere.org", server.notes / "dangling.org")
    (server.notes / "folder.org").mkdir()
    (server.notes / "folder.org" / "inside.org").write_bytes(b"* in a folder named like a note\n")
    return files


KEPT_BY_KOTLIN = {"windows-1252": "* caf\u00e9 \u20ac \ufffd\n", "utf-32": "* caf\u00e9\n", "shift-jis": "* \u65e5\u672c\n"}


def enc(path):
    return "/api/v1/files/" + "/".join(urllib.parse.quote(s, safe="") for s in path.split("/"))


PROBE_PATHS = [
    "/", "//", "/?x=1", "/index.html", "/index.html?x=1", "/%69ndex.html", "/INDEX.html", "/app.js", "/module.wasm", "/styles.css",
    "/sub/data.json", "/sub", "/sub/", "/sub/index.html", "/missing.js", "/app.js/", "/app.js.map", "/app.js.LICENSE.txt",
    "/composeResources/strings.commonMain.cvr", "/composeResources/drawable/icon.xml", "/picture.svg", "/picture.png",
    "/picture.jpg", "/picture.gif", "/font.ttf", "/font.woff2", "/data.bin", "/noextension", "/manifest.webmanifest",
    "/page.htm", "/module.mjs", "/notes.md", "/table.csv", "/doc.pdf", "/movie.mp4", "/sound.mp3", "/archive.zip",
    "/UPPER.HTML", "/.hidden.txt", "/with%20space.txt", "/with+space.txt", "/small.txt", "/link-out.txt", "/link-in.txt",
    "/sub/../index.html", "/sub/%2e%2e/index.html", "/sub//data.json", "/./index.html", "/sub%2Fdata.json",
    "/../outside/secret.txt", "/%2e%2e/outside/secret.txt", "/..%2Foutside%2Fsecret.txt", "/favicon.png", "/favicon.ico",
    "/favicon.png/", "/api", "/api/", "/api//", "/api/?x", "/api/v1", "/api/v1/", "/api/v2/manifest",
    "/api/v1/manifest", "/api/v1/manifest/", "/api//v1//manifest", "/api/v1/%6Danifest", "/API/v1/manifest",
    "/api/v1/manifest?since=abc", "/api/v1/manifest?since=", "/api/v1/manifest?since=-5", "/api/v1/manifest?since=0",
    "/api/v1/manifest?since=99999999999999999999", "/api/v1/manifest?other=1", "/api/v1/manifest?since=%31&since=2",
    "/api/v1/manifest?SINCE=1", "/api/v1/manifest?since=+5", "/api/v1/manifest?since=%2B5", "/api/v1/manifest?since= 5",
    "/api/v1/manifest?since=5%", "/api/v1/manifest?since=%zz", "/api/v1/manifest?&&since", "/api/v1/manifest?since=1.0",
    "/api/v1/bundle", "/api/v1/bundle?since=0", "/api/v1/bundle?since=xyz",
    "/api/v1/files", "/api/v1/files/", "/api/v1/files/inbox.org", "/api/v1/files/inbox.org/",
    "/api/v1/files//inbox.org", "/api/v1/files/projects/work.org", "/api/v1/files/projects//work.org",
    "/api/v1/files/projects/deep/er/leaf.org", "/api/v1/files/projects", "/api/v1/files/projects/",
    "/api/v1/files/with%20space/a%20b.org", "/api/v1/files/with+space/a+b.org",
    "/api/v1/files/sp%23cial/q%3Fmark%2520.org", "/api/v1/files/sp%23cial/q%3Fmark%20.org",
    "/api/v1/files/accent/%C3%A9t%C3%A9.org", "/api/v1/files/accent/e%CC%81te%CC%81.org",
    "/api/v1/files/accent/d%C3%A9compos%C3%A9.org", "/api/v1/files/accent/de%CC%81compose%CC%81.org",
    "/api/v1/files/.plan.org", "/api/v1/files/.stversions/inbox~20260101.org", "/api/v1/files/sub/.hidden/secret.org",
    "/api/v1/files/notes.txt", "/api/v1/files/empty.org", "/api/v1/files/crlf.org", "/api/v1/files/quotes.org",
    "/api/v1/files/invalid-utf8.org", "/api/v1/files/bom.org", "/api/v1/files/unicode.org", "/api/v1/files/big.org",
    "/api/v1/files/also-inbox.org", "/api/v1/files/linked-out.org", "/api/v1/files/linked-folder/x.org",
    "/api/v1/files/dangling.org", "/api/v1/files/folder.org", "/api/v1/files/folder.org/inside.org",
    "/api/v1/files/missing.org", "/api/v1/files/../outside/outside.org", "/api/v1/files/%2e%2e/outside/outside.org",
    "/api/v1/files/projects/../inbox.org", "/api/v1/files/./inbox.org", "/api/v1/files/projects%2Fwork.org",
    "/api/v1/files/inbox.org%00", "/api/v1/files/INBOX.org", "/api/v1/files/inbox.ORG", "/api/v1/files/.org",
    "/api/v1/files/a%5Cb.org", "/api/v1/files/%E0%A4%A.org", "/api/v1/files/%E0%A4%A.org?x=1", "/api/v1/files/bad%FFname.org",
    "/api/v1/files/pct%.org", "/api/v1/files/pct%2", "/api/v1/files/%20.org", "/api/v1/files/%20/x.org", "/api/v1/files/%C2%A0/x.org",
    "/api/v1/files/%C2%85/x.org", "/api/v1/files/%1C/x.org", "/api/v1/files/x%0Ay.org", "/api/v1/files/inbox.org?x=1",
    "/api/v1/files/inbox.org%3Fx=1", "/api/v1/files/inbox.org;v=1", "/%zz", "/api/%zz/manifest", "/devices%zz",
    "/api/v1/events/", "/api/v1/nothing", "/api/pair", "/api/local/pairing", "/api/local/pairing?format=link",
    "/devices", "/devices/", "/devices?x=1", "/devices/pair", "/devices/nobody/revoke", "/devices/a/b/revoke",
]

ROUTES_FOR_METHODS = [
    "/", "/api/", "/api/v1/manifest", "/api/v1/bundle", "/api/v1/events", "/api/v1/files/inbox.org", "/api/v1/files/missing.org",
    "/api/v1/files", "/devices", "/devices/pair", "/devices/nobody/revoke", "/api/pair", "/api/local/pairing",
    "/api/v1/nothing", "/app.js", "/favicon.png", "/favicon.ico", "/api/v1/manifest/", "/api",
]


def probes(r: Recorder, with_tls=True):
    for path in PROBE_PATHS:
        r.call("probe", "GET", path)
    for path in ["/api/v1/manifest", "/api/v1/files/inbox.org", "/api/v1/files/big.org", "/api/v1/bundle", "/", "/app.js",
                 "/module.wasm", "/devices", "/api/", "/favicon.png", "/picture.png", "/picture.jpg", "/picture.gif", "/picture.svg",
                 "/movie.mp4", "/sound.mp3", "/font.ttf", "/data.bin", "/api/v1/files/missing-with-a-long-name-" + "x" * 300 + ".org"]:
        for accept in ["gzip", "deflate", "gzip, deflate", "br", "identity", "gzip;q=0", "deflate, gzip;q=0.5", "*", "GZIP",
                       "gzip;q=0.5, deflate;q=0.5", "deflate;q=0.5, gzip;q=0.5", "br, *;q=0.1", "identity, gzip", "gzip;q=abc",
                       "x-gzip", "", "gzip , deflate", "deflate;q=1.0, gzip;q=1.0", "*;q=0, gzip", "gzip;q=0, *"]:
            r.call("compression", "GET", path, {"Accept-Encoding": accept})
    for method in ["HEAD", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"]:
        for path in ROUTES_FOR_METHODS:
            if method in ("PUT", "DELETE") and "files/" in path:
                continue  # Those change the library; the file steps cover them.
            if method == "POST" and path in ("/api/pair", "/devices/pair", "/api/local/pairing", "/devices/nobody/revoke"):
                continue
            r.call("method", method, path, body=b"" if method in ("POST", "PUT", "PATCH") else None)
    for name, line in [
        ("no host header", b"GET /api/v1/manifest HTTP/1.1\r\nConnection: close\r\n\r\n"),
        ("http 1.0", b"GET /api/v1/manifest HTTP/1.0\r\n\r\n"),
        ("http 1.0, a push", b"PUT /api/v1/files/http10.org HTTP/1.0\r\nContent-Length: 4\r\n\r\n* x\n"),
        ("absolute uri", f"GET http://127.0.0.1:{r.server.http_port}/api/ HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".encode()),
        ("absolute uri, other host", b"GET http://evil.example/api/ HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"),
        ("space in path", b"GET /api/v1/files/with space/a b.org HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"),
        ("garbage", b"NOT HTTP AT ALL\r\n\r\n"),
        ("an unknown method", b"FROB /api/ HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"),
        ("an unknown method on a note", b"FROB /api/v1/files/inbox.org HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"),
        ("lower-case method", b"get /api/ HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"),
        ("two host headers", b"GET /api/ HTTP/1.1\r\nHost: 127.0.0.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n"),
        ("two host headers, the other way", b"GET /api/ HTTP/1.1\r\nHost: evil.example\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"),
    ]:
        r.note(f"raw: {name}", raw_request(r.server.http_port, line))
    r.note("raw: http 1.0 push landed", (r.server.real_notes / "http10.org").exists())


def who_may_ask(r: Recorder):
    """The plain port's two questions: does the Host name this computer, and does a change come from its own page."""
    port = r.server.http_port
    for host in ["localhost", f"localhost:{port}", "LOCALHOST", "127.0.0.1", "127.9.9.9:1", "[::1]", "[::1]:8080", "::1",
                 "rebound.example", "127.evil.example", "127.0.0.1.evil.example", "192.168.1.20:8080", "", "localhost.", " localhost",
                 "localhost:abc", "localhost:", ":8080", "127.0.0.1:99999999999", "127.1", "0.0.0.0", "user@localhost", "localhost:80:90",
                 "<none>", "127.000.000.001", "127.0.0.256", "[::ffff:127.0.0.1]:80", "[0:0:0:0:0:0:0:1]:80"]:
        r.call(f"host {host!r}", "GET", "/api/?probe", host=host)
        r.call(f"host {host!r}, a change", "DELETE", "/api/v1/files/never-there.org", {"If-Match": "x"}, host=host)
    origins = [None, "http://localhost", f"http://127.0.0.1:{port}", f"http://localhost:{port}", "https://evil.example", "null",
               "not a url", f"https://127.0.0.1:{port}", "http://127.0.0.1", "http://127.0.0.1:80", "https://127.0.0.1", "https://127.0.0.1:443",
               "http://LOCALHOST:80", "localhost", "//localhost", "http://localhost/", "http://localhost:80/path?q#f", "http://user@localhost:80",
               "http://user:pw@127.0.0.1:80", "http://[::1]:80", "ftp://localhost:80", "ftp://localhost", "http://localhost:abc", "http://localhost:",
               "", " http://localhost:80", "http://localhost:80 ", "ws://localhost", "wss://localhost", "http:/localhost", "http:localhost",
               "file:///etc/passwd", "about:blank", "chrome-extension://abcdef", "http://127.0.0.1:080", "http://127.0.0.1:65536", "http://",
               "HTTP://127.0.0.1:80", "http://127.0.0.1.:80", "http://%6Cocalhost:80", "x://127.0.0.1", "x://127.0.0.1:80", "null://", "://"]
    for host in [f"127.0.0.1:{port}", "127.0.0.1", "localhost:80", "localhost:443", "localhost", "[::1]:80", "<none>", "localhost:0"]:
        for origin in origins:
            headers = {"Origin": origin} if origin is not None else {}
            r.call(f"host {host!r} origin {origin!r}", "DELETE", "/api/v1/files/never-there.org", {**headers, "If-Match": "x"}, host=host)
    for method in ["GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH"]:
        r.call(f"{method} from another site", method, "/api/v1/files/never-there-either.org", {"Origin": "https://evil.example", "If-Match": "x"},
               b"" if method in ("POST", "PUT", "PATCH") else None)
    r.call("referer is not looked at", "DELETE", "/api/v1/files/never-there.org", {"Referer": "https://evil.example/", "If-Match": "x"})


def file_steps(r: Recorder, files):
    """Each case the Kotlin server's own tests name, and the corners around them."""
    e = lambda p: etag_of(files[p])
    r.call("fetch with its etag", "GET", "/api/v1/files/inbox.org", {"If-None-Match": e("inbox.org")})
    r.call("fetch with its etag quoted", "GET", "/api/v1/files/inbox.org", {"If-None-Match": f'"{e("inbox.org")}"'})
    r.call("fetch with its etag quoted twice", "GET", "/api/v1/files/inbox.org", {"If-None-Match": f'""{e("inbox.org")}"'})
    r.call("fetch with a weak etag", "GET", "/api/v1/files/inbox.org", {"If-None-Match": f'W/"{e("inbox.org")}"'})
    r.call("fetch with another etag", "GET", "/api/v1/files/inbox.org", {"If-None-Match": "0" * 32})
    r.call("fetch with two etags", "GET", "/api/v1/files/inbox.org", {"If-None-Match": f'"{"0" * 32}", "{e("inbox.org")}"'})
    r.call("fetch with its etag in capitals", "GET", "/api/v1/files/inbox.org", {"If-None-Match": e("inbox.org").upper()})
    r.call("fetch with a star", "GET", "/api/v1/files/inbox.org", {"If-None-Match": "*"})
    r.call("fetch with an empty etag", "GET", "/api/v1/files/inbox.org", {"If-None-Match": ""})
    r.call("fetch with If-Match", "GET", "/api/v1/files/inbox.org", {"If-Match": "0" * 32})
    r.call("fetch a missing file with an etag", "GET", "/api/v1/files/missing.org", {"If-None-Match": "0" * 32})
    r.call("fetch a range", "GET", "/api/v1/files/big.org", {"Range": "bytes=0-9"})
    r.call("fetch the web client with a range", "GET", "/app.js", {"Range": "bytes=0-9"})
    r.call("fetch the web client if modified since", "GET", "/app.js", {"If-Modified-Since": "Wed, 21 Oct 2099 07:28:00 GMT"})

    r.call("push with the current etag", "PUT", "/api/v1/files/inbox.org", {"If-Match": e("inbox.org")}, b"* TODO edited\n")
    r.call("push with the old etag", "PUT", "/api/v1/files/inbox.org", {"If-Match": e("inbox.org")}, b"* TODO again\n")
    r.call("push with a quoted etag", "PUT", "/api/v1/files/inbox.org", {"If-Match": f'"{etag_of(b"* TODO edited" + bytes([10]))}"'}, b"* TODO quoted\n")
    r.call("push with a star in If-Match", "PUT", "/api/v1/files/inbox.org", {"If-Match": "*"}, b"* star\n")
    r.call("push with an empty If-Match", "PUT", "/api/v1/files/inbox.org", {"If-Match": ""}, b"* empty if-match\n")
    r.call("push with only quotes in If-Match", "PUT", "/api/v1/files/inbox.org", {"If-Match": '""'}, b"* quotes if-match\n")
    r.call("push unconditionally", "PUT", "/api/v1/files/inbox.org", None, b"* unconditional\n")
    r.call("push the same text again", "PUT", "/api/v1/files/inbox.org", None, b"* unconditional\n")
    r.call("push to a missing file with an etag", "PUT", "/api/v1/files/nothing.org", {"If-Match": "abc"}, b"x\n")
    r.call("create only, over a note", "PUT", "/api/v1/files/inbox.org", {"If-None-Match": "*"}, b"* new\n")
    r.call("create only, with spaces round the star", "PUT", "/api/v1/files/inbox.org", {"If-None-Match": " * "}, b"* new\n")
    r.call("create only, the star quoted", "PUT", "/api/v1/files/star-quoted.org", {"If-None-Match": '"*"'}, b"* new\n")
    r.call("create only, the star quoted, again", "PUT", "/api/v1/files/star-quoted.org", {"If-None-Match": '"*"'}, b"* newer\n")
    r.call("create only, a new note in a new folder", "PUT", "/api/v1/files/new/folder/n.org", {"If-None-Match": "*"}, b"* Captured\n")
    r.call("create only and an etag at once", "PUT", "/api/v1/files/both.org", {"If-None-Match": "*", "If-Match": "abc"}, b"* both\n")
    r.call("If-None-Match naming an etag on a push", "PUT", "/api/v1/files/inm.org", {"If-None-Match": "abc"}, b"* inm\n")
    r.call("create with an encoded name", "PUT", "/api/v1/files/archive/old%20notes%23%3F.org", {"If-None-Match": "*"}, b"* Kept\n")
    r.call("and read it back", "GET", "/api/v1/files/archive/old%20notes%23%3F.org")
    r.call("create over a dangling link", "PUT", "/api/v1/files/dangling.org", {"If-None-Match": "*"}, b"* over a link to nothing\n")
    r.call("write over a dangling link", "PUT", "/api/v1/files/dangling.org", None, b"* over a link to nothing\n")
    r.call("write over a folder named like a note", "PUT", "/api/v1/files/folder.org", None, b"* over a folder\n")
    r.call("create over a folder named like a note", "PUT", "/api/v1/files/folder.org", {"If-None-Match": "*"}, b"* over a folder\n")
    r.call("delete a folder named like a note", "DELETE", "/api/v1/files/folder.org", {"If-Match": "abc"})
    r.call("an empty body", "PUT", "/api/v1/files/blank.org", None, b"")
    r.call("no body at all", "PUT", "/api/v1/files/nobody.org", None, None)
    r.call("bytes that are not utf-8", "PUT", "/api/v1/files/lossy.org", None,
           b"* \xff\xfe \xc3\x28 \xe2\x82 \xf0\x9f\x98 \xed\xa0\x80 \xf4\x90\x80\x80 \xc0\xaf \xf8\x88\x80\x80\x80 \xe0\x80\x80 end \xf0\x9f")
    r.call("and what was kept of them", "GET", "/api/v1/files/lossy.org")
    for label, kind, data in [
        ("latin-1", "text/plain; charset=ISO-8859-1", "* caf\u00e9 \x80\x9f\n".encode("latin-1")),
        ("latin-1, lower case", "text/plain; charset=iso-8859-1", "* caf\u00e9\n".encode("latin-1")),
        ("latin1", "text/plain; charset=latin1", "* caf\u00e9\n".encode("latin-1")),
        ("ascii", "text/plain; charset=US-ASCII", b"* caf\xe9 \x80\n"),
        ("utf-8", "text/plain; charset=UTF-8", "* caf\u00e9\n".encode()),
        ("utf-8, quoted", 'text/plain; charset="utf-8"', "* caf\u00e9\n".encode()),
        ("utf8", "text/plain; charset=utf8", "* caf\u00e9\n".encode()),
        ("utf-16", "text/plain; charset=UTF-16", "* caf\u00e9\n".encode("utf-16")),
        ("utf-16le", "text/plain; charset=UTF-16LE", "* caf\u00e9\n".encode("utf-16-le")),
        ("utf-16be, odd length", "text/plain; charset=UTF-16BE", "* caf\u00e9\n".encode("utf-16-be") + b"\x00"),
        ("utf-16le above the basic plane", "text/plain; charset=UTF-16LE", "* \U0001F600 and \u2615\n".encode("utf-16-le")),
        ("utf-16le with half a pair, the first", "text/plain; charset=UTF-16LE", b"a\x00\x00\xd8b\x00c\x00\n\x00"),
        ("utf-16le with half a pair, the second", "text/plain; charset=UTF-16LE", b"a\x00\x00\xdcb\x00c\x00\n\x00"),
        ("utf-16le with two first halves", "text/plain; charset=UTF-16LE", b"a\x00\x00\xd8\x00\xd8\x00\xdcb\x00\n\x00"),
        ("utf-16be ending in half a pair", "text/plain; charset=UTF-16BE", b"\x00a\x00b\xd8\x00"),
        ("utf-16 with a mark and half a pair", "text/plain; charset=UTF-16", b"\xff\xfea\x00\x00\xd8\n\x00"),
        ("utf-16be ending in half a pair and a byte", "text/plain; charset=UTF-16BE", b"\x00a\xd8\x00\x00"),
        ("utf-16be with half a pair before a first half and a whole pair", "text/plain; charset=UTF-16BE", b"\x00a\xd8\x00\xd8\x3d\xde\x00\x00b"),
        ("utf-16 that is empty", "text/plain; charset=UTF-16", b""),
        ("utf-16 that is only a mark", "text/plain; charset=UTF-16", b"\xfe\xff"),
        ("windows-1252", "text/plain; charset=windows-1252", b"* caf\xe9 \x80 \x81\n"),
        ("utf-32", "text/plain; charset=UTF-32", "* caf\u00e9\n".encode("utf-32")),
        ("shift-jis", "text/plain; charset=Shift_JIS", b"* \x93\xfa\x96\x7b\n"),
        ("a made-up charset", "text/plain; charset=made-up", b"* caf\xc3\xa9\n"),
        ("an empty charset", "text/plain; charset=", b"* caf\xc3\xa9\n"),
        ("json", "application/json", b'{"a":"\xc3\xa9"}'),
        ("json in latin-1", "application/json; charset=ISO-8859-1", b'{"a":"\xe9"}'),
        ("octets", "application/octet-stream", b"* octets \xff\n"),
        ("a form", "application/x-www-form-urlencoded", b"a=b&c=%20d"),
        ("multipart", "multipart/form-data; boundary=x", b"--x\r\nContent-Disposition: form-data; name=a\r\n\r\nb\r\n--x--\r\n"),
        ("a type that is no type", "nonsense", b"* nonsense \xe9\n"),
        ("an empty type", "", b"* empty type \xe9\n"),
    ]:
        name = "charset-" + re.sub(r"[^a-z0-9]+", "-", label) + ".org"
        status, _, _ = r.call(f"a body said to be {label}", "PUT", f"/api/v1/files/{name}", {"Content-Type": kind}, data)
        if status == 415 and label in KEPT_BY_KOTLIN:
            # Refused on purpose. What the Kotlin server keeps is put there, so that what follows is alike.
            r.call("", "PUT", f"/api/v1/files/{name}", None, KEPT_BY_KOTLIN[label].encode(), record=False)
        r.call(f"and what was kept of {label}", "GET", f"/api/v1/files/{name}")
    for path in ["notes.txt", ".korgnote-trash/planted.org", ".new/planted.org", "sub/.x/y.org", "../escape.org", "a/../../escape.org",
                 "linked-folder/new.org", "linked-out.org", "also-inbox.org", "projects", "projects/work.org/under.org", ".org", "a%5Cb.org",
                 "inbox.org/", "x/./y.org", "trailing.org%20", "UPPER.ORG", "deep/" * 30 + "d.org", "n" * 250 + ".org", "n" * 300 + ".org",
                 "%20.org", "%20/x.org", "%C2%A0/x.org", "%C2%85/x.org", "%1C/x.org", "%E2%80%8B/x.org", "x%0Ay.org", "nul%00.org", "tab%09.org",
                 "he%22ard%20%C3%A9.org", "%F0%9F%98%80.org", "bad%FFname.org", "semi;colon.org", "dots...org", "...org", "a/.org", "..a/x.org",
                 "a../x.org", "con.org", "x.org.org"]:
        r.call(f"write to {path[:60]}", "PUT", "/api/v1/files/" + path, None, b"* planted\n")
        r.call(f"create {path[:60]}", "PUT", "/api/v1/files/" + path, {"If-None-Match": "*"}, b"* planted\n")
        r.call(f"read {path[:60]}", "GET", "/api/v1/files/" + path)
        r.call(f"delete {path[:60]}", "DELETE", "/api/v1/files/" + path, {"If-Match": "abc"})
    limit = 32 * 1024 * 1024
    r.call("a note of exactly the limit", "PUT", "/api/v1/files/limit.org", None, b"x" * limit)
    r.call("a note one byte over", "PUT", "/api/v1/files/over.org", None, b"x" * (limit + 1))
    r.call("a note over the limit, in a bad place", "PUT", "/api/v1/files/notes.txt", None, b"x" * (limit + 1))
    r.call("a note over the limit, with a stale etag", "PUT", "/api/v1/files/inbox.org", {"If-Match": "abc"}, b"x" * (limit + 1))
    r.call("a note over the limit, where one is", "PUT", "/api/v1/files/inbox.org", {"If-None-Match": "*"}, b"x" * (limit + 1))
    r.call("a note under the limit that grows past it", "PUT", "/api/v1/files/grows.org", None, b"\xff" * (limit // 2))
    r.call("a note far over the limit", "PUT", "/api/v1/files/far-over.org", None, b"x" * (limit * 2 + 7))
    r.call("the limit note's etag", "GET", "/api/v1/files/limit.org", {"If-None-Match": etag_of(b"x" * limit)})
    r.call("delete the limit note", "DELETE", "/api/v1/files/limit.org", {"If-Match": etag_of(b"x" * limit)})

    r.call("delete without an etag", "DELETE", "/api/v1/files/crlf.org")
    r.call("delete without an etag, a missing note", "DELETE", "/api/v1/files/never.org")
    r.call("delete without an etag, a bad path", "DELETE", "/api/v1/files/notes.txt")
    r.call("delete with an empty etag", "DELETE", "/api/v1/files/crlf.org", {"If-Match": ""})
    r.call("delete with a stale etag", "DELETE", "/api/v1/files/crlf.org", {"If-Match": "0" * 32})
    r.call("delete with a star", "DELETE", "/api/v1/files/crlf.org", {"If-Match": "*"})
    r.call("delete", "DELETE", "/api/v1/files/crlf.org", {"If-Match": e("crlf.org")})
    r.call("delete again", "DELETE", "/api/v1/files/crlf.org", {"If-Match": e("crlf.org")})
    r.call("delete a missing note", "DELETE", "/api/v1/files/never.org", {"If-Match": "abc"})
    r.call("delete with a body", "DELETE", "/api/v1/files/bom.org", {"If-Match": f'"{e("bom.org")}"'}, b"ignored")
    # The same name deleted twice in a day must keep both in the trash.
    for round in range(3):
        text = f"* round {round}\n".encode()
        r.call("make it again", "PUT", "/api/v1/files/crlf.org", {"If-None-Match": "*"}, text)
        r.call("and delete it again", "DELETE", "/api/v1/files/crlf.org", {"If-Match": etag_of(text)})
    r.call("the manifest after all that", "GET", "/api/v1/manifest")
    r.call("the bundle after all that", "GET", "/api/v1/bundle")


def bundle_budget(r: Recorder):
    """Past 8 MB of text the bundle stops and says so. The budget counts UTF-16 units, as the Kotlin server does."""
    chunk = ("* A made-up heading \u00e9\u2615\U0001F600\n" + "lorem ipsum " * 100 + "\n").encode()
    one = chunk * (3 * 1024 * 1024 // len(chunk))
    for n in range(4):
        r.call("", "PUT", f"/api/v1/files/budget/b{n}.org", None, one, record=False)
    r.call("a bundle past its budget", "GET", "/api/v1/bundle")
    r.call("a bundle past its budget, gzipped", "GET", "/api/v1/bundle", {"Accept-Encoding": "gzip"})
    for n in range(4):
        r.call("", "DELETE", f"/api/v1/files/budget/b{n}.org", {"If-Match": etag_of(one)}, record=False)


def delta_steps(r: Recorder):
    r.settle()
    start = r.current()
    r.call("nothing since now", "GET", f"/api/v1/manifest?since={start}")
    r.call("a generation from the future", "GET", f"/api/v1/manifest?since={start + 1000}")
    r.call("one past now", "GET", f"/api/v1/manifest?since={start + 1}")
    r.call("a generation before the journal", "GET", f"/api/v1/manifest?since={start - 100000}")
    text = b"* delta one\n"
    r.call("a change", "PUT", "/api/v1/files/delta.org", {"If-None-Match": "*"}, text)
    r.call("then deleted", "DELETE", "/api/v1/files/delta.org", {"If-Match": etag_of(text)})
    r.call("then made again", "PUT", "/api/v1/files/delta.org", {"If-None-Match": "*"}, b"* delta two\n")
    r.call("and another", "PUT", "/api/v1/files/delta2.org", None, b"* delta other\n")
    r.call("and one deleted for good", "PUT", "/api/v1/files/delta3.org", None, text)
    r.call("deleted", "DELETE", "/api/v1/files/delta3.org", {"If-Match": etag_of(text)})
    r.call("what moved since", "GET", f"/api/v1/manifest?since={start}")
    r.call("the bundle of what moved", "GET", f"/api/v1/bundle?since={start}")
    r.call("since the middle", "GET", f"/api/v1/manifest?since={start + 2}")
    r.call("bundle since the future", "GET", f"/api/v1/bundle?since={start + 1000}")
    # More changes than the journal holds: the oldest fall out, and asking from before them is a full manifest.
    for n in range(520):
        r.call("", "PUT", "/api/v1/files/churn.org", None, f"* churn {n}\n".encode(), record=False)
    now = r.current()
    for back in (520, 513, 512, 511, 1, 0):
        r.call(f"after 520 changes, since {back} ago", "GET", f"/api/v1/manifest?since={now - back}")
    r.call("after 520 changes, the bundle since 512 ago", "GET", f"/api/v1/bundle?since={now - 512}")
    r.call("after 520 changes, the bundle since 513 ago", "GET", f"/api/v1/bundle?since={now - 513}")


def event_steps(r: Recorder):
    r.settle()
    stream = EventStream(r.server.http_port)
    time.sleep(0.5)
    start = r.current()
    text = b"* heard\n"
    r.call("a save, while listening", "PUT", "/api/v1/files/heard.org", None, text)
    r.call("a save of a name with quotes", "PUT", "/api/v1/files/he%22ard%20%C3%A9%F0%9F%98%80%09.org", None, text)
    r.call("a delete, while listening", "DELETE", "/api/v1/files/heard.org", {"If-Match": etag_of(text)})
    r.call("a refused save, while listening", "PUT", "/api/v1/files/heard.org", {"If-Match": "abc"}, text)
    time.sleep(1.0)
    head, lines = stream.frames()
    stream.close()
    if head is None:
        r.note("events: head", "no answer")
        return
    head_lines = head.split("\r\n")
    headers = {l.split(":", 1)[0].lower(): l.split(":", 1)[1].strip() for l in head_lines[1:] if ":" in l}
    r.note("events: status", head_lines[0].split(" ")[1])
    r.note("events: headers", {h: headers[h] for h in COMPARED_HEADERS if h in headers})
    r.note("events: other headers", sorted(h for h in headers if h not in COMPARED_HEADERS and h not in ("date", "server")))
    r.norm.current = start
    frames = []
    for line in lines:
        if line.startswith("data: "):
            frames.append("data: " + json.dumps(r.norm.json_value(json.loads(line[6:])), ensure_ascii=False))
            frames.append("raw:  " + re.sub(r'"generation":\d+', '"generation":N', line))
        elif line != ": ping":
            frames.append(line)
    r.note("events: frames", frames)

    # A reader that stops reading loses events, and its stream is closed for it to catch up.
    slow = EventStream(r.server.http_port)
    time.sleep(0.5)
    slow.socket.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
    with slow.lock:
        pass
    quiet = socket.create_connection(("127.0.0.1", r.server.http_port), timeout=120)
    quiet.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 2048)
    quiet.sendall(f"GET /api/v1/events HTTP/1.1\r\nHost: 127.0.0.1:{r.server.http_port}\r\n\r\n".encode())
    time.sleep(0.5)
    started = time.time()
    for n in range(4000):
        request(r.server.http_port, "PUT", "/api/v1/files/" + "n" * 180 + f"{n % 7}.org", None, f"* {n}\n".encode())
    r.note("events: 4000 saves went through while a reader slept", True)
    # Now it reads: the stream must end, not go on with events missing. With room to read into
    # again, or what the server has waiting comes through a few kilobytes at a time.
    quiet.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024 * 1024)
    quiet.settimeout(20)
    got = b""
    ended = False
    try:
        while True:
            chunk = quiet.recv(65536)
            if not chunk:
                ended = True
                break
            got += chunk
            if got.endswith(b"0\r\n\r\n"):
                ended = True
                break
    except socket.timeout:
        pass
    quiet.close()
    slow.close()
    generations = [int(g) for g in re.findall(rb'"generation":(\d+)', got)]
    r.note("info: the sleeping reader's stream ended", ended)
    r.note("events: what it did get was in order with none missing", generations == list(range(generations[0], generations[0] + len(generations))) if generations else True)
    r.note("info: the sleeping reader got", len(generations))
    for n in range(7):
        request(r.server.http_port, "DELETE", "/api/v1/files/" + "n" * 180 + f"{n}.org", {"If-Match": etag_of(f"* {3999 - ((3999 - n) % 7)}\n".encode())})


def watcher_steps(r: Recorder):
    """Edits made behind the server's back, each waited for, so that the journal counts alike."""
    def behind(label, paths, action):
        since = r.current()
        action()
        noticed = r.wait_for_journal(since, paths)
        r.note(f"watcher: {label}: noticed", noticed)
        r.call(f"watcher: {label}: delta", "GET", f"/api/v1/manifest?since={since}")

    notes = r.server.notes
    behind("an edit", ["projects/work.org"], lambda: external_write(r.server, "projects/work.org", b"* edited in an editor\n"))
    behind("a new note", ["fresh.org"], lambda: external_write(r.server, "fresh.org", b"* fresh\n"))
    behind("a note in a new folder", ["newdir/in.org"], lambda: external_write(r.server, "newdir/in.org", b"* in\n"))
    behind("a note deep in new folders", ["a/b/c/d.org"], lambda: external_write(r.server, "a/b/c/d.org", b"* deep\n"))
    behind("a deletion", ["fresh.org"], lambda: os.remove(notes / "fresh.org"))
    behind("a note renamed", ["inbox.org", "inbox-renamed.org"], lambda: os.rename(notes / "inbox.org", notes / "inbox-renamed.org"))
    behind("a folder renamed", ["projects/work.org", "renamed/work.org", "renamed/deep/er/leaf.org"], lambda: os.rename(notes / "projects", notes / "renamed"))
    behind("a folder deleted", ["newdir/in.org"], lambda: shutil.rmtree(notes / "newdir"))
    behind("an edit in the renamed folder", ["renamed/work.org"], lambda: external_write(r.server, "renamed/work.org", b"* edited after the rename\n"))
    behind("a note moved out", ["renamed/work.org"], lambda: os.rename(notes / "renamed" / "work.org", r.server.outside / "moved-out.org"))

    def folder_moved_in():
        staging = r.server.outside / "project"
        (staging / "deep").mkdir(parents=True)
        (staging / "deep" / "d.org").write_bytes(b"* moved in\n")
        (staging / "top.org").write_bytes(b"* moved in, top\n")
        os.rename(staging, notes / "moved-in")
    behind("a folder moved in with its notes", ["moved-in/deep/d.org", "moved-in/top.org"], folder_moved_in)

    def dot_folder_then_note():
        external_write(r.server, ".stversions/x~1.org", b"* not a note\n")
        external_write(r.server, "sub/.hidden/more.org", b"* not a note either\n")
        external_write(r.server, "notes.txt", b"still not a note\n")
        external_write(r.server, "real.org", b"* real\n")
    behind("only the note among what is not one", ["real.org"], dot_folder_then_note)
    behind("an edit that changes nothing, then one that does", ["real.org"], lambda: (external_write(r.server, "real.org", b"* real\n"), time.sleep(1.5), external_write(r.server, "real.org", b"* real, edited\n")))
    time.sleep(2)
    r.norm.strict_counts = True
    r.call("watcher: the devices page", "GET", "/devices")
    r.norm.strict_counts = False
    r.call("watcher: the manifest at the end", "GET", "/api/v1/manifest")


def tls_steps(r: Recorder):
    """The network's side: paired devices only, and only the sync API."""
    for path in ["/", "/devices", "/api/", "/api/v1/manifest", "/api/v1/bundle", "/api/v1/files/inbox.org", "/api/v1/events",
                 "/api/local/pairing", "/api/pair", "/api/pair/", "/api/pair?x=1", "/api//pair", "/index.html", "/favicon.png", "/api/v1", "/api/v1/",
                 "/api/v1/%zz", "/%zz", "/api/v1/files/%E0%A4%A.org", "/api/v1x/manifest"]:
        r.call("tls, no token", "GET", path, tls=True)
        r.call("tls, a made-up token", "GET", path, {"Authorization": "Bearer guess"}, tls=True)
        r.call("tls, no token, post", "POST", path, None, b"", tls=True)
    r.call("tls, pair with no body", "POST", "/api/pair", {"Content-Type": "application/json"}, b"", tls=True)
    r.call("tls, pair with a made-up code", "POST", "/api/pair", {"Content-Type": "application/json"},
           json.dumps({"code": "made-up", "deviceName": "Phone"}).encode(), tls=True)
    r.call("tls, pair with no json", "POST", "/api/pair", {"Content-Type": "application/json"}, b"not json", tls=True)
    r.call("tls, pair with another type", "POST", "/api/pair", {"Content-Type": "text/plain"},
           json.dumps({"code": "made-up", "deviceName": "Phone"}).encode(), tls=True)
    r.call("tls, pair with no type", "POST", "/api/pair", None, json.dumps({"code": "made-up", "deviceName": "Phone"}).encode(), tls=True)

    def code(label):
        status, _, data = r.call(label, "POST", f"/api/local/pairing?addresses={ADDRESS}&format=link", body=b"")
        match = re.search(r"&c=([0-9a-f]+)", data.decode())
        return match.group(1) if match else ""

    def pair(label, body, kind="application/json", raw=None):
        payload = raw if raw is not None else json.dumps(body).encode()
        status, _, data = r.call(label, "POST", "/api/pair", {"Content-Type": kind}, payload, tls=True)
        try:
            return json.loads(data)
        except ValueError:
            return {}

    first = pair("tls, pair", {"code": code("a code"), "deviceName": "  Made-up Phone <b> \"quoted\" 'single' & more  "})
    token = first.get("token", "")
    auth = {"Authorization": f"Bearer {token}"}
    for path in ["/api/v1/manifest", "/api/v1/bundle", "/api/v1/files/inbox.org", "/api/", "/", "/devices", "/api/local/pairing",
                 "/api//local/pairing", "/api/%6Cocal/pairing", "/%61pi/local/pairing", "/api/v1/../local/pairing",
                 "/api/v1/%2e%2e/local/pairing", "/api/v1/../../devices", "/api/v1//manifest", "/api//v1/manifest", "/api/v1/",
                 "/api/v1/%zz", "/api/v1/files/%E0%A4%A.org", "/api/v1/nothing"]:
        r.call("tls, paired", "GET", path, auth, tls=True)
        r.call("tls, paired, post", "POST", path + "?addresses=6.6.6.6&format=link", auth, b"", tls=True)
    for header in [f"bearer {token}", f"Bearer  {token}", f"Bearer {token} ", token, f"Basic {token}", "Bearer ", "", f"Bearer {token}x",
                   f"Bearer Bearer {token}", f"BEARER {token}", f"Bearer\t{token}", f" Bearer {token}"]:
        r.call(f"tls, authorization spelled {header.replace(token, '<token>')!r}", "GET", "/api/v1/manifest?since=1", {"Authorization": header}, tls=True)
    r.call("tls, a push", "PUT", "/api/v1/files/from-phone.org", {**auth, "If-None-Match": "*"}, b"* from the phone\n", tls=True)
    r.call("tls, a push from another origin", "PUT", "/api/v1/files/from-phone2.org", {**auth, "Origin": "https://evil.example"}, b"* x\n", tls=True)
    r.call("tls, any host name", "GET", "/api/v1/manifest?since=1", auth, tls=True, host="whatever.example")
    r.call("tls, a delete", "DELETE", "/api/v1/files/from-phone2.org", {**auth, "If-Match": etag_of(b"* x\n")}, tls=True)
    r.call("tls, gzip", "GET", "/api/v1/manifest", {**auth, "Accept-Encoding": "gzip"}, tls=True)
    used = code("another code")
    second = pair("tls, pair again, replacing with a wrong token", {"code": used, "deviceName": "Phone 2", "replaces": first.get("deviceId"), "replacesToken": "wrong"})
    pair("tls, the same code twice", {"code": used, "deviceName": "Phone 2"})
    third = pair("tls, pair again, replacing", {"code": code("a third code"), "deviceName": "", "replaces": first.get("deviceId"), "replacesToken": token})
    r.call("tls, the replaced token", "GET", "/api/v1/manifest?since=1", auth, tls=True)
    r.call("tls, the new token", "GET", "/api/v1/manifest?since=1", {"Authorization": f"Bearer {third.get('token', '')}"}, tls=True)
    pair("tls, pair with unknown fields", {"code": code("a fourth code"), "deviceName": "N" * 200, "extra": 1})
    pair("tls, pair without a name", {"code": code("a fifth code")})
    long_name = pair("tls, pair with a long name", {"code": code("a sixth code"), "deviceName": "\U0001F600" * 60 + " tail"})
    pair("tls, pair with nulls", {"code": code("a seventh code"), "deviceName": "Nulls", "replaces": None, "replacesToken": None})
    pair("tls, pair replacing nobody", {"code": code("an eighth code"), "deviceName": "Nobody", "replaces": "no-such-device", "replacesToken": "x"})
    pair("tls, pair replacing without a token", {"code": code("a ninth code"), "deviceName": "No token", "replaces": third.get("deviceId")})
    pair("tls, pair with a charset", {"code": code("a tenth code"), "deviceName": "Charset \u00e9"}, kind="application/json; charset=UTF-8")
    pair("tls, pair with a name of spaces", {"code": code("an eleventh code"), "deviceName": " \t\u00a0\u2003 "})
    r.call("the devices page", "GET", "/devices")
    r.call("revoke the second", "POST", f"/devices/{second.get('deviceId', 'none')}/revoke", {"Origin": f"http://127.0.0.1:{r.server.http_port}"}, b"")
    r.call("revoke it again", "POST", f"/devices/{second.get('deviceId', 'none')}/revoke", None, b"")
    r.call("tls, the revoked token", "GET", "/api/v1/manifest?since=1", {"Authorization": f"Bearer {second.get('token', '')}"}, tls=True)
    r.call("the devices page after", "GET", "/devices")
    for addresses in ["", "10.0.0.5, 192.168.1.20", "https://192.168.1.20:8443", "laptop.local", "999.1.1.1", "fe80::1%en0 10.1.1.1\n10.2.2.2",
                      "a_b.local", "-x.local", "x-.local", "1.2.3", "1.2.3.4.5", "::1", "1::", ":", "::", "x" * 64 + ".local", "x" * 63 + ".local",
                      "\u00e9t\u00e9.local", "<b>\"quote\"&amp;", "10.0.0.5,,  ,10.0.0.6", "01.02.03.004", "256.1.1.1", "1.1.1.1.", ".local", "a..b",
                      "fe80::1%", "fe80::zz", "12345", "1.2.3.4:80"]:
        body = urllib.parse.urlencode({"addresses": addresses}).encode()
        r.call(f"a code for {addresses[:40]!r}", "POST", "/devices/pair", {"Content-Type": "application/x-www-form-urlencoded"}, body)
    r.call("a code with no form", "POST", "/devices/pair", None, b"")
    r.call("a code with an empty form", "POST", "/devices/pair", {"Content-Type": "application/x-www-form-urlencoded"}, b"")
    r.call("a code with a form of another field", "POST", "/devices/pair", {"Content-Type": "application/x-www-form-urlencoded"}, b"other=1")
    r.call("a code with a form with a charset", "POST", "/devices/pair", {"Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"}, b"addresses=10.9.9.9")
    r.call("a code with json", "POST", "/devices/pair", {"Content-Type": "application/json"}, b'{"addresses":"10.0.0.5"}')
    r.call("a code with a multipart form", "POST", "/devices/pair", {"Content-Type": "multipart/form-data; boundary=x"},
           b"--x\r\nContent-Disposition: form-data; name=\"addresses\"\r\n\r\n10.7.7.7\r\n--x--\r\n")
    r.call("a code with the field twice", "POST", "/devices/pair", {"Content-Type": "application/x-www-form-urlencoded"}, b"addresses=10.3.3.3&addresses=10.4.4.4")
    r.call("a code with plus and percent", "POST", "/devices/pair", {"Content-Type": "application/x-www-form-urlencoded"}, b"addresses=10.3.3.3+10.4.4.4%2C10.5.5.5")
    r.call("the script's code, with its picture", "POST", f"/api/local/pairing?addresses={ADDRESS}", body=b"")
    r.call("the script's code, a bad address", "POST", "/api/local/pairing?addresses=http://x&format=link", body=b"")
    r.call("the script's code, two addresses", "POST", "/api/local/pairing?addresses=10.1.1.1,10.2.2.2%2010.3.3.3&format=link", body=b"")
    r.call("the script's code, no addresses", "POST", "/api/local/pairing?format=link", body=b"")
    r.call("the script's code, another format", "POST", "/api/local/pairing?format=LINK", body=b"")
    r.call("the script's code, with a body", "POST", "/api/local/pairing?format=link", {"Content-Type": "application/json"}, b'{"x":1}')
    # Addresses are checked one by one and not counted: enough of them make a link no QR code holds.
    many = ",".join(f"192.168.{100 + n // 250}.{n % 250 + 1}" for n in range(200))
    r.call("too many addresses for a code, asked for as a link", "POST", f"/api/local/pairing?addresses={many}&format=link", body=b"")
    r.call("too many addresses for a code, asked for as a picture", "POST", f"/api/local/pairing?addresses={many}", body=b"")
    r.call("too many addresses for a code, on the page", "POST", "/devices/pair", {"Content-Type": "application/x-www-form-urlencoded"},
           urllib.parse.urlencode({"addresses": many}).encode())
    r.call("a code after too many addresses", "POST", f"/api/local/pairing?addresses={ADDRESS}&format=link", body=b"")
    for flag in ["--http1.1", "--http2"]:
        out = subprocess.run(["curl", "-sk", flag, "-o", "/dev/null", "-w", "%{http_version} %{http_code}",
                              f"https://127.0.0.1:{r.server.https_port}/api/v1/manifest"], capture_output=True, text=True).stdout
        r.note(f"curl {flag}", out)
    out = subprocess.run(["curl", "-sk", "--http2", "-H", f"Authorization: Bearer {third.get('token', '')}", "-X", "PUT", "--data-binary", "* over http/2\n",
                          "-w", " %{http_version} %{http_code}", f"https://127.0.0.1:{r.server.https_port}/api/v1/files/over-h2.org"], capture_output=True, text=True).stdout
    r.note("curl, a push over http/2", out)
    out = subprocess.run(["curl", "-sk", "--http2", "--compressed", "-H", f"Authorization: Bearer {third.get('token', '')}",
                          "-w", " %{http_version} %{http_code}", f"https://127.0.0.1:{r.server.https_port}/api/v1/files/over-h2.org"], capture_output=True, text=True).stdout
    r.note("curl, a fetch over http/2", out)
    plain_on_tls = raw_request(r.server.https_port, b"GET /api/ HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", timeout=3)
    r.note("plain http on the tls port: answered in http", plain_on_tls.isdigit())
    r.note("the devices file", devices_file(r.server))


def devices_file(server: Server):
    """devices.json, with what is random in it named: the shape and the key order are what is compared."""
    path = server.state / "devices.json"
    if not path.exists():
        return "none"
    text = path.read_text()
    text = UUID.sub("<id>", text)
    text = re.sub(r'"[0-9a-f]{64}"', '"<hash>"', text)
    text = re.sub(r"\d{13}", "<time>", text)
    return text


def random_steps(r: Recorder, seed, steps):
    """A made-up day of saves and deletions from a seed, the same for both servers."""
    r.settle()
    rng = random.Random(seed)
    model = {}
    # The generation after each change that went through. A delta is asked from one of these and not
    # from so many generations ago: either server may now and then announce a save twice, when its
    # watcher looks at the file between the save and its announcement, and counting back would then
    # ask the two servers different questions.
    marks = [r.current()]
    names = [f"r{n}.org" for n in range(6)] + ["dir/r.org", "dir/sub/r.org", "caf\u00e9 \u2615.org", "we ird#?.org", "\U0001F600.org"]
    words = ["alpha", "bravo", "\u00e9t\u00e9", "\u2615", "\U0001F600", "TODO", "DONE", "* ", "\n", "\t", '"', "\\", "\r\n", " ", "x" * 50, "\x00", "\x1b", "\u2028"]

    def text():
        data = "".join(rng.choice(words) for _ in range(rng.randint(0, 30))).encode()
        if rng.random() < 0.15:
            cut = rng.randint(0, len(data))
            data = data[:cut] + bytes(rng.choice([0xff, 0xc3, 0xe2, 0x80, 0xf0, 0xed, 0xa0, 0xf4, 0x90, 0xc0, 0xbf]) for _ in range(rng.randint(1, 4))) + data[cut:]
        return data

    for step in range(steps):
        path = rng.choice(names)
        current = model.get(path)
        kind = rng.choices(["put", "create", "delete", "get", "manifest", "bundle", "delta"], [30, 12, 12, 14, 6, 4, 14])[0]
        label = f"random {step}: {kind} {path}"
        if kind in ("put", "create"):
            data = text()
            headers = {}
            if kind == "create":
                headers["If-None-Match"] = "*"
                ok = current is None
            else:
                choice = rng.random()
                if choice < 0.55 and current is not None:
                    headers["If-Match"] = etag_of(current)
                    ok = True
                elif choice < 0.8:
                    headers["If-Match"] = etag_of(text() + b"stale")
                    ok = False
                else:
                    ok = True
            status, _, _ = r.call(label, "PUT", enc(path), headers, data)
            if ok:
                model[path] = lossy(data)
            if status == 200:
                marks.append(r.current())
        elif kind == "delete":
            good = current is not None and rng.random() < 0.7
            status, _, _ = r.call(label, "DELETE", enc(path), {"If-Match": etag_of(current) if good else "0" * 32})
            if good:
                del model[path]
            if status == 204:
                marks.append(r.current())
        elif kind == "get":
            headers = {"If-None-Match": etag_of(current)} if current is not None and rng.random() < 0.3 else {}
            r.call(label, "GET", enc(path), headers)
        elif kind == "manifest":
            r.call(label, "GET", "/api/v1/manifest")
        elif kind == "bundle":
            r.call(label, "GET", "/api/v1/bundle", {"Accept-Encoding": "gzip"} if rng.random() < 0.5 else {})
        elif kind == "delta":
            back = min(rng.randint(0, 12), len(marks) - 1)
            r.norm.since_name = f"{back} changes ago"
            r.call(f"{label} -{back}", "GET", f"/api/v1/manifest?since={marks[-1 - back]}")
            r.norm.since_name = None
    r.note("random: the model's notes", sorted(model))


def utf8_steps(r: Recorder, seed):
    """Bytes that are not UTF-8, read from disk and sent as bodies: both servers must make the same text of them."""
    rng = random.Random(seed * 7919 + 1)
    since = r.current()
    pool = ([0x41, 0x20, 0x0a, 0x7f] + [0x80, 0x8f, 0x90, 0x9f, 0xa0, 0xbf] * 3 + [0xc0, 0xc1, 0xc2, 0xdf] * 2 + [0xe0, 0xe1, 0xec, 0xed, 0xee, 0xef] * 3
            + [0xf0, 0xf1, 0xf3, 0xf4] * 3 + [0xf5, 0xf7, 0xf8, 0xfb, 0xfc, 0xfe, 0xff])
    for round in range(24):
        size = rng.choice([1, 2, 3, 4, 5, 7, 64, 500, 4090, 4096, 4099, 8190, 8192, 8195, 16384, 65530, 65536, 65540, 200000])
        data = bytes(rng.choice(pool) for _ in range(size))
        if round % 3 == 0:
            # Mostly text, with a wrong byte here and there, as a damaged note is.
            text = ("* caf\u00e9 \u2615 \U0001F600 lorem ipsum\n" * (size // 30 + 1)).encode()[:size]
            data = bytearray(text)
            for _ in range(max(1, size // 200)):
                data[rng.randrange(len(data))] = rng.choice(pool)
            data = bytes(data)
        external_write(r.server, f"utf8/read-{round}.org", data)
        r.call(f"utf8: a note of {size} bytes, read", "GET", f"/api/v1/files/utf8/read-{round}.org")
        r.call(f"utf8: a note of {size} bytes, sent", "PUT", f"/api/v1/files/utf8/sent-{round}.org", None, data)
        kept = (r.server.real_notes / "utf8" / f"sent-{round}.org")
        r.note(f"utf8: what was kept of {size} bytes sent", hashlib.sha256(kept.read_bytes()).hexdigest()[:16] if kept.exists() else "nothing")
    r.note("utf8: the watcher noticed them", r.wait_for_journal(since, [f"utf8/read-{round}.org" for round in range(24)], timeout=80))
    r.call("utf8: the bundle of them", "GET", "/api/v1/bundle")


def record(impl, work: Path, seed, steps, state_from=None, phases=None):
    server = Server(impl, work, state_from)
    files = seed_library(server)
    server.start()
    r = Recorder(server)
    try:
        r.call("the first manifest", "GET", "/api/v1/manifest")
        r.call("the first bundle", "GET", "/api/v1/bundle")
        all_phases = {
            "probes": lambda: probes(r),
            "who": lambda: who_may_ask(r),
            "files": lambda: file_steps(r, files),
            "utf8": lambda: utf8_steps(r, seed),
            "budget": lambda: bundle_budget(r),
            "deltas": lambda: delta_steps(r),
            "events": lambda: event_steps(r),
            "tls": lambda: tls_steps(r),
            "random": lambda: random_steps(r, seed, steps),
            "watcher": lambda: watcher_steps(r),
        }
        for name, phase in all_phases.items():
            if phases and name not in phases:
                continue
            started = time.time()
            phase()
            print(f"  {impl}: {name} in {time.time() - started:.1f}s", file=sys.stderr)
        r.note("the notes folder at the end", r.tree())
        r.note("server still running", server.process.poll() is None)
        r.note("info: codes drawn", r.norm.drawn)
    finally:
        server.stop()
    entries = r.entries
    if not phases or "variants" in phases:
        started = time.time()
        entries = entries + variants(impl, work, state_from if state_from is not None else keep_state(server, work))
        print(f"  {impl}: variants in {time.time() - started:.1f}s", file=sys.stderr)
    return entries, server


def variants(impl, work: Path, state_from):
    """The same servers started other ways: with no web client, a notes folder that is a link, none at all, one that cannot be read."""
    out = []

    def run(variant, steps, seed=True, expect_failure=False, **options):
        server = Server(impl, work, state_from, variant=variant, **options)
        if seed and server.real_notes.exists():
            seed_library(server)
        r = Recorder(server)
        try:
            code = server.start(expect_failure=expect_failure)
            if expect_failure:
                log = (server.dir / "server.log").read_text(errors="replace")
                r.note("stopped at startup", code is not None and code != 0)
                steps(r, server, log)
            else:
                steps(r, server, None)
                r.note("the notes folder at the end", r.tree() if server.real_notes.exists() else "none")
        finally:
            server.stop()
        for entry in r.entries:
            entry["step"] = f"[{variant}] {entry['step']}"
        out.extend(r.entries)

    def no_web(r, server, _):
        for path in ["/", "//", "/index.html", "/app.js", "/api/", "/api", "/devices", "/favicon.png", "/api/v1/manifest", "/api/pair",
                     "/api/local/pairing", "/devices/pair", "/devices/nobody/revoke", "/api/v1/files/inbox.org", "/api/v1/files",
                     "/api/v1/nothing", "/nothing", "/devices/", "/api/v1/manifest/", "/api/v1/files/a/b", "/devices/a/b/revoke", "/%zz"]:
            for method in ["GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"]:
                if method in ("PUT", "DELETE") and "files/" in path:
                    continue
                if method == "POST" and path in ("/api/pair", "/devices/pair", "/api/local/pairing", "/devices/nobody/revoke"):
                    continue
                r.call("method", method, path, body=b"" if method in ("POST", "PUT", "PATCH") else None)
        r.call("tls, no token", "GET", "/", tls=True)
        r.call("tls, no token, pair", "GET", "/api/pair", tls=True)
        r.call("tls, no token, pair, put", "PUT", "/api/pair", None, b"", tls=True)
        r.note("raw: an unknown method", raw_request(server.http_port, b"FROB /api/ HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"))
    run("no-web", no_web, env={"KORGNOTE_DISABLE_WEB": "1"})

    def through_link(r, server, _):
        r.call("the manifest", "GET", "/api/v1/manifest")
        r.call("a note", "GET", "/api/v1/files/inbox.org")
        r.call("a push", "PUT", "/api/v1/files/through-link.org", {"If-None-Match": "*"}, b"* through a link\n")
        r.call("a delete", "DELETE", "/api/v1/files/through-link.org", {"If-Match": etag_of(b"* through a link\n")})
        r.call("a push to a new folder", "PUT", "/api/v1/files/made/through-link.org", {"If-None-Match": "*"}, b"* through a link\n")
        since = r.current()
        external_write(server, "edited-behind.org", b"* edited behind a link\n")
        r.note("watcher noticed", r.wait_for_journal(since, ["edited-behind.org"]))
        r.call("the devices page", "GET", "/devices")
    run("notes-through-a-link", through_link, notes_through_link=True)

    def no_notes(r, server, _):
        r.call("the manifest", "GET", "/api/v1/manifest")
        r.call("the bundle", "GET", "/api/v1/bundle")
        r.call("a delta", "GET", f"/api/v1/manifest?since={r.current()}")
        r.call("a note", "GET", "/api/v1/files/inbox.org")
        r.call("a push", "PUT", "/api/v1/files/a.org", None, b"* a\n")
        r.call("a push to a bad path", "PUT", "/api/v1/files/notes.txt", None, b"* a\n")
        r.call("a push too large", "PUT", "/api/v1/files/a.org", None, b"x" * (32 * 1024 * 1024 + 1))
        r.call("a delete", "DELETE", "/api/v1/files/a.org", {"If-Match": "abc"})
        r.call("a delete with no etag", "DELETE", "/api/v1/files/a.org")
        r.call("the devices page", "GET", "/devices")
        r.note("no folder was made", not server.notes.exists())
    run("no-notes-folder", no_notes, seed=False, make_notes=False)

    def required_missing(r, server, log):
        r.note("says it does not exist", "does not exist" in log)
        r.note("names the variable", "KORGNOTE_REQUIRE_NOTES_DIR" in log)
    run("required-and-missing", required_missing, seed=False, make_notes=False, expect_failure=True, env={"KORGNOTE_REQUIRE_NOTES_DIR": "1"})

    def required_read_only(r, server, log):
        r.note("says it is not writable", "not writable" in log)
    server_probe = Server(impl, work, state_from, variant="required-and-read-only")
    os.chmod(server_probe.real_notes, 0o555)
    try:
        code = server_probe.start(expect_failure=True)
        log = (server_probe.dir / "server.log").read_text(errors="replace")
        out.append({"step": "[required-and-read-only] stopped at startup", "value": code is not None and code != 0})
        out.append({"step": "[required-and-read-only] says it is not writable", "value": "not writable" in log})
    finally:
        server_probe.stop()
        os.chmod(server_probe.real_notes, 0o755)

    def required_present(r, server, _):
        r.call("the manifest", "GET", "/api/v1/manifest")
        r.note("no write check left behind", [p.name for p in server.notes.iterdir() if "write-check" in p.name])
    run("required-and-present", required_present, env={"KORGNOTE_REQUIRE_NOTES_DIR": "yes"})

    def unreadable(r, server, _):
        locked_folder = server.notes / "locked"
        locked_folder.mkdir()
        (locked_folder / "b.org").write_bytes(b"* b\n")
        (server.notes / "locked-note.org").write_bytes(b"* locked\n")
        frozen = server.notes / "frozen"
        frozen.mkdir()
        (frozen / "a.org").write_bytes(b"* a\n")
        etag = etag_of(b"* a\n")
        try:
            os.chmod(frozen, 0o555)
            r.call("a push into a folder that refuses", "PUT", "/api/v1/files/frozen/new.org", None, b"* new\n")
            r.call("a push over a note in a folder that refuses", "PUT", "/api/v1/files/frozen/a.org", {"If-Match": etag}, b"* edited\n")
            r.call("a delete in a folder that refuses", "DELETE", "/api/v1/files/frozen/a.org", {"If-Match": etag})
            os.chmod(frozen, 0o755)
            os.chmod(server.notes / "locked-note.org", 0o000)
            r.call("a note that cannot be read", "GET", "/api/v1/files/locked-note.org")
            r.call("a note that cannot be read, with an etag", "GET", "/api/v1/files/locked-note.org", {"If-None-Match": "abc"})
            r.call("a push over a note that cannot be read", "PUT", "/api/v1/files/locked-note.org", {"If-Match": "abc"}, b"* x\n")
            r.call("a create over a note that cannot be read", "PUT", "/api/v1/files/locked-note.org", {"If-None-Match": "*"}, b"* x\n")
            r.call("a delete of a note that cannot be read", "DELETE", "/api/v1/files/locked-note.org", {"If-Match": "abc"})
            r.call("the manifest with a note that cannot be read", "GET", "/api/v1/manifest")
            r.call("the bundle with a note that cannot be read", "GET", "/api/v1/bundle")
            os.chmod(server.notes / "locked-note.org", 0o644)
            os.chmod(locked_folder, 0o000)
            r.call("the manifest with a folder that cannot be read", "GET", "/api/v1/manifest")
            r.call("the bundle with a folder that cannot be read", "GET", "/api/v1/bundle")
            r.call("a note in a folder that cannot be read", "GET", "/api/v1/files/locked/b.org")
            r.call("a push into a folder that cannot be read", "PUT", "/api/v1/files/locked/c.org", None, b"* c\n")
            r.call("a delete in a folder that cannot be read", "DELETE", "/api/v1/files/locked/b.org", {"If-Match": "abc"})
            r.call("a note beside a folder that cannot be read", "GET", "/api/v1/files/inbox.org")
        finally:
            os.chmod(frozen, 0o755)
            os.chmod(locked_folder, 0o755)
            os.chmod(server.notes / "locked-note.org", 0o644)
        r.call("the manifest once it can all be read", "GET", "/api/v1/manifest")
    run("unreadable", unreadable)

    def other_ports(r, server, _):
        r.call("a code", "POST", "/api/local/pairing?format=link", body=b"")
        r.call("the devices page", "GET", "/devices")
    run("advertised", other_ports, env={"KORGNOTE_ADVERTISED_PORT": "9443", "KORGNOTE_ADVERTISED_ADDRESSES": " 10.0.0.5 ,10.0.0.6  laptop.local,", "KORGNOTE_SERVER_NAME": "  A <name> & \"more\"  ", "KORGNOTE_TOKEN": "leftover"})
    return out


# Differences that are meant: a pattern for "step | request", the field ("*" for any), and why.
MALFORMED_ORIGINS = "|".join(re.escape(o) for o in [
    "'null'", "'not a url'", "'localhost'", "'//localhost'", "'http://localhost/'", "'http://localhost:80/path?q#f'", "'http://user@localhost:80'",
    "'http://user:pw@127.0.0.1:80'", "'http://localhost:'", "''", "'http:/localhost'", "'http:localhost'", "'http://'", "'ftp://localhost'",
    "'http://localhost:abc'", "'http://127.0.0.1:65536'", "'null://'", "'://'",
])
ALLOWED = [
    (rf"^host .* origin ({MALFORMED_ORIGINS}) \|", "*",
     "Ktor reads an Origin it cannot parse as localhost on port 80 (or port 0), and so takes it for the server's own page when the server "
     "is itself asked for by that name and port. The Rust server reads an Origin strictly and refuses these. No browser writes one this way."),
    (r"a body said to be (windows-1252|utf-32|shift-jis) \|", "*",
     "The Kotlin server reads a body in any charset Java knows. The Rust server reads UTF-8, ISO-8859-1, US-ASCII and UTF-16, and answers 415 "
     "for another. The apps send UTF-8."),
    (r"^\[unreadable\] .*(with an etag|over a note that cannot be read|of a note that cannot be read|manifest with|bundle with) ", "body",
     "Where the disk will not say, the Kotlin server answers 500 with the exception's message, which is the file's path on the server's "
     "computer. The Rust server answers 500 without it."),
    (r"^too many addresses for a code, (asked for as a picture|on the page) \|", "body",
     "A pairing link too long for a QR code is a 500 from both. The Kotlin server's says what the library said; the Rust one says what to do."),
    (r"^too many addresses for a code, (asked for as a picture|on the page) \|", "headers",
     "As above: the Kotlin server's 500 has no body to give a type to, or the library's words."),
    (r"^\[notes-through-a-link\] ", "*",
     "With a notes folder that is a link, the Kotlin server lists an empty, complete library, which takes every note off every phone, "
     "and never notices an edit. The Rust server lists and watches the folder the link leads to."),
]


def allowed(entry_a, entry_b, key):
    label = f"{(entry_a or entry_b).get('step')} | {(entry_a or entry_b).get('request', '')}"
    return any(re.search(pattern, label) and field in (key, "*") for pattern, field, _ in ALLOWED)


def compare(a, b, limit=80):
    differences = 0
    excused = 0
    for index in range(max(len(a), len(b))):
        x = a[index] if index < len(a) else None
        y = b[index] if index < len(b) else None
        if x == y or (x or y).get("step", "").startswith("info:"):
            continue
        keys = [k for k in sorted(set(x or {}) | set(y or {})) if k != "other_headers" and (x or {}).get(k) != (y or {}).get(k)]
        if not keys:
            continue
        if x and y and all(allowed(x, y, k) for k in keys):
            excused += 1
            continue
        differences += 1
        if differences > limit:
            continue
        print(f"\n#{index} {(x or y).get('step')} | {(x or y).get('request', '')}")
        for key in keys:
            print(f"   {key}:\n      kotlin: {json.dumps((x or {}).get(key), ensure_ascii=False)[:1200]}\n      rust:   {json.dumps((y or {}).get(key), ensure_ascii=False)[:1200]}")
    header_notes = {}
    for x, y in zip(a, b):
        if x.get("other_headers") != y.get("other_headers"):
            key = (tuple(x.get("other_headers", [])), tuple(y.get("other_headers", [])))
            header_notes[key] = header_notes.get(key, 0) + 1
    print(f"\n{differences} differences in {max(len(a), len(b))} steps ({excused} more are meant, and listed in ALLOWED).")
    if header_notes:
        print("Headers not compared that one server sends and the other does not:")
        for (k, rs), count in sorted(header_notes.items(), key=lambda i: -i[1])[:12]:
            print(f"   {count:5d} times  kotlin: {list(k)}  rust: {list(rs)}")
    return differences


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["run", "record", "compare"])
    parser.add_argument("args", nargs="*")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--steps", type=int, default=400)
    parser.add_argument("--work", default=None)
    parser.add_argument("--phases", default=None, help="comma-separated: probes,who,files,utf8,budget,deltas,events,tls,random,watcher,variants,state")
    parser.add_argument("--state-from", default=None, help="a state folder to start from, for record")
    options = parser.parse_args()
    work = Path(options.work) if options.work else Path(tempfile.mkdtemp(prefix="korgnote-conformance-"))
    work.mkdir(parents=True, exist_ok=True)
    phases = options.phases.split(",") if options.phases else None
    if options.mode == "record":
        impl, out = options.args
        state = Path(options.state_from) if options.state_from else None
        entries, server = record(impl, work, options.seed, options.steps, state_from=state, phases=phases)
        Path(out).write_text(json.dumps(entries, ensure_ascii=False, indent=1))
        if state is None:
            keep_state(server, work)
        print(f"{len(entries)} steps written to {out}")
    elif options.mode == "compare":
        a, b = (json.loads(Path(p).read_text()) for p in options.args)
        sys.exit(1 if compare(a, b) + check_codes(a) else 0)
    else:
        # The Kotlin server makes the identity, and the Rust one is given a copy: the same server to
        # a paired phone, which is also what reading the Kotlin server's key store is tested by.
        kotlin, server = record("kotlin", work, options.seed, options.steps, phases=phases)
        (work / "kotlin.json").write_text(json.dumps(kotlin, ensure_ascii=False, indent=1))
        state = keep_state(server, work)
        rust, _ = record("rust", work, options.seed, options.steps, state_from=state, phases=phases)
        (work / "rust.json").write_text(json.dumps(rust, ensure_ascii=False, indent=1))
        differences = compare(kotlin, rust)
        differences += check_codes(kotlin)
        if not phases or "state" in phases:
            differences += one_state_for_both(work, "kotlin", "rust")
            differences += one_state_for_both(work, "rust", "kotlin")
        sys.exit(1 if differences else 0)


def check_codes(kotlin) -> int:
    """Has the Rust server draw every QR code the Kotlin server drew, for the same link, and compares the drawings."""
    wrong = 0
    drawn = [code for entry in kotlin if entry.get("step") == "info: codes drawn" for code in entry["value"]]
    for code in drawn:
        kind = "svg" if "svg" in code else "terminal"
        out = subprocess.run(COMMANDS["rust"] + [f"--qr-{kind}", code["link"]], capture_output=True, text=True).stdout
        if out != code[kind]:
            wrong += 1
            print(f"\nA QR code drawn otherwise ({kind}) for {code['link']}")
    print(f"{len(drawn)} QR codes drawn by the Kotlin server were drawn again by the Rust one: {wrong} differ.")
    return wrong


def certificate_fingerprint(port) -> str:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    with socket.create_connection(("127.0.0.1", port), timeout=20) as raw:
        with context.wrap_socket(raw) as secure:
            return hashlib.sha256(secure.getpeercert(binary_form=True)).hexdigest()


def one_state_for_both(work: Path, first: str, second: str) -> int:
    """One state directory handed from one server to the other and back: each must be the same server
    to a paired phone as the other was, by its id, its certificate, and the devices it knows."""
    problems = []

    def session(impl, variant, state_from, tokens):
        server = Server(impl, work, state_from, variant=variant)
        server.start()
        try:
            _, _, link = request(server.http_port, "POST", f"/api/local/pairing?addresses={ADDRESS}&format=link", body=b"")
            link = link.decode()
            identity = (re.search(r"[?&]id=([^&]+)", link).group(1), re.search(r"[?&]fp=([0-9a-f]+)", link).group(1), certificate_fingerprint(server.https_port))
            for name, token in tokens.items():
                status, _, _ = request(server.https_port, "GET", "/api/v1/manifest", {"Authorization": f"Bearer {token}"}, tls=True)
                if status != 200:
                    problems.append(f"{impl} does not know the device {name!r} paired earlier (status {status})")
            code = re.search(r"[?&]c=([0-9a-f]+)", link).group(1)
            _, _, answer = request(server.https_port, "POST", "/api/pair", {"Content-Type": "application/json"},
                                   json.dumps({"code": code, "deviceName": f"Paired with {impl} in {variant}"}).encode(), tls=True)
            tokens = {**tokens, f"{impl} in {variant}": json.loads(answer)["token"]}
        finally:
            server.stop()
        return identity, server.state, tokens

    made, state, tokens = session(first, f"state-made-by-{first}", None, {})
    if made[1] != made[2]:
        problems.append(f"{first} says its certificate is {made[1]} and shows {made[2]}")
    read, state, tokens = session(second, f"state-of-{first}-read-by-{second}", state, tokens)
    if read != made:
        problems.append(f"{second} is not the server {first} was: {read} is not {made}")
    back, _, _ = session(first, f"state-of-{first}-back-from-{second}", state, tokens)
    if back != made:
        problems.append(f"{first} is not itself after {second} had its state: {back} is not {made}")
    for problem in problems:
        print(f"\nState: {problem}")
    print(f"A state directory made by the {first} server, used by the {second} one and handed back: {len(problems)} problems.")
    return len(problems)


def keep_state(server: Server, work: Path) -> Path:
    """A copy of the identity a server made, with no device paired, for the other to start from."""
    state = work / f"state-from-{server.impl}"
    shutil.rmtree(state, ignore_errors=True)
    shutil.copytree(server.state, state)
    devices = state / "devices.json"
    if devices.exists():
        devices.unlink()
    return state


if __name__ == "__main__":
    main()
