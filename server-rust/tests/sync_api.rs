//! The sync API's contract, as `docs/05-sync-protocol.md` defines it, over real connections.
//!
//! Every case here is one a client will depend on: a conditional fetch that saves bandwidth, a
//! conditional push that refuses to clobber, and a manifest that can be diffed.
//!
//! A port of `SyncApiV1Test.kt`, `ExternalEditsTest.kt` and `ApplicationTest.kt`, test for test.

mod common;

use common::{Scratch, TestServer, files_under};
use korgnote_server::config::ServerConfig;
use korgnote_server::http::App;
use korgnote_server::security::ServerAccess;
use korgnote_server::store::FileStore;
use korgnote_server::text::sha256_hex;
use std::os::unix::fs::PermissionsExt;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

async fn serve(files: &[(&str, &str)]) -> (Scratch, TestServer) {
    let notes = Scratch::with("api", files);
    let server = TestServer::local(FileStore::new(&notes.path)).await;
    (notes, server)
}

fn is_root() -> bool {
    std::process::Command::new("id").arg("-u").output().is_ok_and(|out| out.stdout.starts_with(b"0\n"))
}

#[tokio::test(flavor = "multi_thread")]
async fn the_manifest_lists_every_file_with_its_etag() {
    let (_notes, server) = serve(&[("a.org", "* A\n"), ("sub/b.org", "* B\n")]).await;
    let answer = server.get("/api/v1/manifest").await;
    assert_eq!(answer.header("content-type"), Some("application/json"));
    let body = answer.text();
    assert!(body.contains("\"complete\":true"), "{body}");
    assert!(body.contains("a.org"));
    assert!(body.contains("sub/b.org"));
}

/// Phones already out there read these names: they are the wire format, whatever the code does.
#[tokio::test(flavor = "multi_thread")]
async fn the_manifest_keeps_its_field_names() {
    let (_notes, server) = serve(&[("a.org", "* A\n")]).await;
    let text = server.get("/api/v1/manifest").await.text();
    let manifest: serde_json::Value = serde_json::from_str(&text).unwrap();
    let keys = |value: &serde_json::Value| value.as_object().unwrap().keys().cloned().collect::<Vec<_>>();
    // In this order, too, which a client that reads as it goes may count on.
    assert!(text.starts_with("{\"generation\":"), "{text}");
    assert!(text.contains(",\"complete\":true,\"entries\":[{\"path\":\"a.org\",\"size\":4,\"mtime\":"), "{text}");
    assert!(text.ends_with("\"etag\":\"370cda735fbc22e2436ab472dacbbf98\"}],\"deleted\":[]}"), "{text}");
    let mut names = keys(&manifest);
    names.sort();
    assert_eq!(names, ["complete", "deleted", "entries", "generation"]);
    let mut names = keys(&manifest["entries"][0]);
    names.sort();
    assert_eq!(names, ["etag", "mtime", "path", "size"]);
}

/// The bandwidth saver: nothing changed, so send nothing.
#[tokio::test(flavor = "multi_thread")]
async fn a_conditional_get_returns_304_when_nothing_changed() {
    let (_notes, server) = serve(&[("a.org", "* A\n")]).await;
    let first = server.get("/api/v1/files/a.org").await;
    assert_eq!(first.status, 200);
    assert_eq!(first.header("content-type"), Some("text/plain; charset=UTF-8"));
    assert_eq!(first.text(), "* A\n");
    let etag = first.etag();

    let second = server.request("GET", "/api/v1/files/a.org", &[("If-None-Match", &etag)], None).await;
    assert_eq!(second.status, 304);
    assert_eq!(second.text(), "");
    assert_eq!(second.etag(), etag);
    let quoted = server.request("GET", "/api/v1/files/a.org", &[("If-None-Match", &format!("\"{etag}\""))], None).await;
    assert_eq!(quoted.status, 304);
    let other = server.request("GET", "/api/v1/files/a.org", &[("If-None-Match", "00000000000000000000000000000000")], None).await;
    assert_eq!(other.status, 200);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_push_with_the_current_etag_succeeds_and_returns_the_new_one() {
    let (_notes, server) = serve(&[("a.org", "* A\n")]).await;
    let etag = server.get("/api/v1/files/a.org").await.etag();
    let answer = server.put("/api/v1/files/a.org", &[("If-Match", &etag)], "* A edited\n").await;
    assert_eq!(answer.status, 200);
    let new = answer.json()["etag"].as_str().unwrap().to_string();
    assert_eq!(answer.etag(), new, "the app reads the header");
    assert_eq!(new, sha256_hex(b"* A edited\n")[..32]);
    assert_eq!(server.get("/api/v1/files/a.org").await.text(), "* A edited\n");
}

/// The one that protects an edit made in Emacs while the client was typing.
#[tokio::test(flavor = "multi_thread")]
async fn a_push_with_a_stale_etag_is_refused_with_409() {
    let (notes, server) = serve(&[("a.org", "* A\n")]).await;
    let stale = server.get("/api/v1/files/a.org").await.etag();
    // Someone else edits the file.
    notes.write("a.org", "* Changed in Emacs\n");
    let answer = server.put("/api/v1/files/a.org", &[("If-Match", &stale)], "* A from the client\n").await;
    assert_eq!(answer.status, 409);
    assert_eq!(answer.etag(), sha256_hex(b"* Changed in Emacs\n")[..32], "the client needs the current etag to merge");
    assert_eq!(notes.read("a.org"), "* Changed in Emacs\n", "nothing was overwritten");
}

#[tokio::test(flavor = "multi_thread")]
async fn create_only_refuses_an_existing_file() {
    let (notes, server) = serve(&[("a.org", "* A\n")]).await;
    let answer = server.put("/api/v1/files/a.org", &[("If-None-Match", "*")], "* new\n").await;
    assert_eq!(answer.status, 409);
    assert_eq!(notes.read("a.org"), "* A\n");
}

#[tokio::test(flavor = "multi_thread")]
async fn create_only_makes_a_new_file_including_its_directory() {
    let (notes, server) = serve(&[]).await;
    let answer = server.put("/api/v1/files/projects/new.org", &[("If-None-Match", "*")], "* Captured\n").await;
    assert_eq!(answer.status, 200);
    assert_eq!(notes.read("projects/new.org"), "* Captured\n");
}

#[tokio::test(flavor = "multi_thread")]
async fn deleting_moves_the_file_to_trash_rather_than_unlinking_it() {
    let (notes, server) = serve(&[("gone.org", "* Gone\n")]).await;
    let etag = server.get("/api/v1/files/gone.org").await.etag();
    let answer = server.delete("/api/v1/files/gone.org", &[("If-Match", &etag)]).await;
    assert_eq!(answer.status, 204);
    assert!(!notes.exists("gone.org"));
    let trashed = files_under(&notes.file(".korgnote-trash"));
    assert_eq!(trashed.len(), 1, "the note should be recoverable");
    assert_eq!(std::fs::read_to_string(&trashed[0]).unwrap(), "* Gone\n");
}

#[tokio::test(flavor = "multi_thread")]
async fn deleting_without_an_etag_is_refused() {
    let (notes, server) = serve(&[("a.org", "* A\n")]).await;
    let answer = server.delete("/api/v1/files/a.org", &[]).await;
    assert_eq!(answer.status, 422);
    assert_eq!(answer.text(), "If-Match is required to delete");
    assert!(notes.exists("a.org"));
}

#[tokio::test(flavor = "multi_thread")]
async fn trashed_notes_are_not_part_of_the_library() {
    let (_notes, server) = serve(&[("a.org", "* A\n")]).await;
    let etag = server.get("/api/v1/files/a.org").await.etag();
    server.delete("/api/v1/files/a.org", &[("If-Match", &etag)]).await;
    let manifest = server.get("/api/v1/manifest").await.text();
    assert!(!manifest.contains("a.org"), "{manifest}");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_dot_folder_is_out_of_reach_of_the_api() {
    let (notes, server) = serve(&[("a.org", "* A\n"), (".stversions/a~1.org", "* old A\n")]).await;
    let manifest = server.get("/api/v1/manifest").await.text();
    assert!(!manifest.contains("stversions"), "{manifest}");
    assert!(!server.get("/api/v1/bundle").await.text().contains("stversions"));
    assert_eq!(server.get("/api/v1/files/.stversions/a~1.org").await.status, 404);
    assert_eq!(server.put("/api/v1/files/.korgnote-trash/planted.org", &[], "* planted\n").await.status, 422);
    assert!(!notes.exists(".korgnote-trash/planted.org"));
}

/// The disk refusing is the server's problem, not the request's: a client must not be told its
/// path is invalid, and has no use for where the notes are on this computer. A folder named
/// `large` is there because the status used to be chosen by looking for that word in the message.
#[tokio::test(flavor = "multi_thread")]
async fn a_write_the_disk_refuses_is_a_server_error_that_does_not_say_where() {
    if is_root() {
        return;
    }
    let (notes, server) = serve(&[("large/a.org", "* A\n")]).await;
    let etag = server.get("/api/v1/files/large/a.org").await.etag();
    std::fs::set_permissions(notes.file("large"), std::fs::Permissions::from_mode(0o555)).unwrap();
    let answers = [
        server.put("/api/v1/files/large/new.org", &[], "* new\n").await,
        server.put("/api/v1/files/large/a.org", &[("If-Match", &etag)], "* A edited\n").await,
        server.delete("/api/v1/files/large/a.org", &[("If-Match", &etag)]).await,
    ];
    for answer in answers {
        assert_eq!(answer.status, 500);
        assert!(!answer.text().contains(&notes.path.display().to_string()), "{}", answer.text());
        assert!(answer.text().starts_with("Could not change large/"), "{}", answer.text());
    }
}

/// A manifest without them would have every phone delete those notes.
#[tokio::test(flavor = "multi_thread")]
async fn a_folder_that_cannot_be_read_is_a_server_error_and_not_a_shorter_manifest() {
    if is_root() {
        return;
    }
    let (notes, server) = serve(&[("a.org", "* A\n"), ("locked/b.org", "* B\n")]).await;
    std::fs::set_permissions(notes.file("locked"), std::fs::Permissions::from_mode(0o000)).unwrap();
    for path in ["/api/v1/manifest", "/api/v1/bundle"] {
        let answer = server.get(path).await;
        assert_eq!(answer.status, 500, "{path}");
        assert!(!answer.text().contains(&notes.path.display().to_string()), "where the notes are is not said: {}", answer.text());
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn what_is_wrong_with_a_request_is_still_said() {
    let (_notes, server) = serve(&[("a.org", "* A\n")]).await;
    assert_eq!(server.put("/api/v1/files/notes.txt", &[], "x").await.status, 422);
    assert_eq!(server.delete("/api/v1/files/notes.txt", &[("If-Match", "any")]).await.status, 422);
    let too_large = server.put("/api/v1/files/big.org", &[], &"x".repeat(32 * 1024 * 1024 + 1)).await;
    assert_eq!(too_large.status, 413);
    assert_eq!(too_large.text(), "file too large");
    // In a bad place, that is what is wrong with it, large or not.
    assert_eq!(server.put("/api/v1/files/notes.txt", &[], &"x".repeat(32 * 1024 * 1024 + 1)).await.status, 422);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_note_of_exactly_the_limit_is_taken() {
    let (notes, server) = serve(&[]).await;
    let answer = server.put("/api/v1/files/limit.org", &[], &"x".repeat(32 * 1024 * 1024)).await;
    assert_eq!(answer.status, 200);
    assert_eq!(std::fs::metadata(notes.file("limit.org")).unwrap().len(), 32 * 1024 * 1024);
    // Sixteen megabytes that are not text become forty-eight of replacement characters.
    let grows = server.request("PUT", "/api/v1/files/grows.org", &[], Some(&vec![0xffu8; 16 * 1024 * 1024])).await;
    assert_eq!(grows.status, 413);
    assert!(!notes.exists("grows.org"));
}

#[tokio::test(flavor = "multi_thread")]
async fn the_bundle_streams_one_json_object_per_file() {
    let (_notes, server) = serve(&[("a.org", "* A\n"), ("b.org", "* B\n")]).await;
    let answer = server.get("/api/v1/bundle").await;
    assert_eq!(answer.header("content-type"), Some("application/json"));
    let text = answer.text();
    let lines: Vec<&str> = text.trim().lines().collect();
    assert_eq!(lines.len(), 2);
    assert_eq!(lines[0], r#"{"path":"a.org","etag":"370cda735fbc22e2436ab472dacbbf98","text":"* A\n"}"#);
    assert!(lines.iter().all(|line| line.contains("\"path\"") && line.contains("\"etag\"") && line.contains("\"text\"")));
    assert!(text.ends_with('\n'));
}

/// Beyond 8 MB of text the bundle stops, and says so with the generation it stopped at.
#[tokio::test(flavor = "multi_thread")]
async fn a_bundle_past_its_budget_ends_with_a_line_saying_so() {
    let three_megabytes = "* lorem ipsum\n".repeat(3 * 1024 * 1024 / 14);
    let (_notes, server) = serve(&[("a.org", &three_megabytes), ("b.org", &three_megabytes), ("c.org", &three_megabytes), ("d.org", "* never reached\n")]).await;
    let text = server.get("/api/v1/bundle").await.text();
    let lines: Vec<&str> = text.trim().lines().collect();
    assert_eq!(lines.len(), 3, "two notes fit, the third does not, and nothing follows the line that says so");
    let last: serde_json::Value = serde_json::from_str(lines[2]).unwrap();
    assert_eq!(last["truncated"], true);
    assert_eq!(last["generation"], server.app.journal.generation());
    assert!(lines[2].starts_with("{\"truncated\":true,\"generation\":"));
}

#[tokio::test(flavor = "multi_thread")]
async fn a_manifest_since_the_current_generation_is_empty_and_incomplete() {
    let (_notes, server) = serve(&[("a.org", "* A\n")]).await;
    let etag = server.get("/api/v1/files/a.org").await.etag();
    server.put("/api/v1/files/a.org", &[("If-Match", &etag)], "* A2\n").await;
    // Asking since the generation that write made should report no further changes.
    let generation = server.get("/api/v1/manifest").await.json()["generation"].as_i64().unwrap();
    let body = server.get(&format!("/api/v1/manifest?since={generation}")).await.text();
    assert!(body.contains("\"complete\":false"), "{body}");
    assert!(body.contains("\"entries\":[]"), "{body}");
}

#[tokio::test(flavor = "multi_thread")]
async fn an_unknown_generation_falls_back_to_a_full_manifest() {
    let (_notes, server) = serve(&[("a.org", "* A\n")]).await;
    for since in ["999", "abc", "", "-5", "99999999999999999999"] {
        let body = server.get(&format!("/api/v1/manifest?since={since}")).await.text();
        assert!(body.contains("\"complete\":true"), "{since}: {body}");
        assert!(body.contains("a.org"));
    }
    assert_eq!(server.get("/api/v1/manifest?since=5%").await.status, 400);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_push_bumps_the_generation_so_clients_can_diff() {
    let (_notes, server) = serve(&[("a.org", "* A\n")]).await;
    let before = server.get("/api/v1/manifest").await.json()["generation"].as_i64().unwrap();
    let etag = server.get("/api/v1/files/a.org").await.etag();
    server.put("/api/v1/files/a.org", &[("If-Match", &etag)], "* A2\n").await;
    let after = server.get("/api/v1/manifest").await.json()["generation"].as_i64().unwrap();
    assert_eq!(after, before + 1, "the generation must move when a file changes");
    // A push that is refused moves nothing.
    server.put("/api/v1/files/a.org", &[("If-Match", &etag)], "* A3\n").await;
    assert_eq!(server.get("/api/v1/manifest").await.json()["generation"].as_i64().unwrap(), after);
}

#[tokio::test(flavor = "multi_thread")]
async fn paths_that_escape_the_library_are_refused() {
    let (notes, server) = serve(&[("a.org", "* A\n")]).await;
    let outside = notes.path.parent().unwrap().join("outside_secret.org");
    std::fs::write(&outside, "private\n").unwrap();
    assert_eq!(server.get("/api/v1/files/../../etc/passwd.org").await.status, 404);
    assert_eq!(server.get("/api/v1/files/../outside_secret.org").await.status, 404);
    assert_eq!(server.put("/api/v1/files/../outside_secret.org", &[], "clobbered\n").await.status, 422);
    let posted = server.post("/api/v1/files/../outside_secret.org", &[], "clobbered\n").await;
    assert_eq!(posted.status, 404);
    assert_eq!(std::fs::read_to_string(&outside).unwrap(), "private\n", "nothing outside the notes directory may be written");
    std::fs::remove_file(outside).unwrap();
}

/// The app percent-encodes each segment, so a name with a space is still one note.
#[tokio::test(flavor = "multi_thread")]
async fn an_encoded_path_addresses_the_note_it_names() {
    let (notes, server) = serve(&[]).await;
    let created = server.put("/api/v1/files/archive/old%20notes.org", &[("If-None-Match", "*")], "* Kept\n").await;
    assert_eq!(created.status, 200);
    assert_eq!(notes.read("archive/old notes.org"), "* Kept\n");
    assert_eq!(server.get("/api/v1/files/archive/old%20notes.org").await.text(), "* Kept\n");
    assert_eq!(server.get("/api/v1/files/archive/old+notes.org").await.status, 404, "a plus is a plus in a path");
    let bad = server.get("/api/v1/files/%E0%A4%A.org?x=1").await;
    assert_eq!(bad.status, 400);
    assert_eq!(bad.text(), "Url decode failed for /api/v1/files/%E0%A4%A.org?x=1");
}

/// The etag handed out must be the hash of the text handed out with it. Read apart, an edit landing
/// in between pairs the old etag with the new text, and the client's next push is a conflict with
/// itself.
#[tokio::test(flavor = "multi_thread")]
async fn a_file_and_its_etag_are_the_same_version_while_it_is_being_edited() {
    let (notes, server) = serve(&[("a.org", "* A\n")]).await;
    let etag_of = |text: &str| sha256_hex(text.as_bytes())[..32].to_string();
    let editing = Arc::new(AtomicBool::new(true));
    let (file, flag) = (notes.file("a.org"), editing.clone());
    let editor = std::thread::spawn(move || {
        let mut round = 0usize;
        while flag.load(Ordering::Relaxed) {
            round += 1;
            std::fs::write(&file, format!("* A {round}\n").repeat(1 + round % 50)).unwrap();
        }
    });
    let mut wrong = Vec::new();
    for _ in 0..300 {
        let file = server.get("/api/v1/files/a.org").await;
        // A write in progress can show the note as it is for a moment: cut short, or empty. It is still the text its etag names.
        if file.status == 200 && etag_of(&file.text()) != file.etag() {
            wrong.push(format!("file: {} for {:?}", file.etag(), file.text()));
        }
        let bundle = server.get("/api/v1/bundle").await.text();
        if let Ok(line) = serde_json::from_str::<serde_json::Value>(bundle.trim())
            && etag_of(line["text"].as_str().unwrap()) != line["etag"].as_str().unwrap() {
                wrong.push(format!("bundle: {line}"));
            }
    }
    editing.store(false, Ordering::Relaxed);
    editor.join().unwrap();
    assert!(wrong.is_empty(), "{wrong:?}");
}

/// A delete made against an old version must not take a newer edit with it.
#[tokio::test(flavor = "multi_thread")]
async fn deleting_a_version_that_has_since_changed_is_refused() {
    let (notes, server) = serve(&[("a.org", "* A\n")]).await;
    let etag = server.get("/api/v1/files/a.org").await.etag();
    notes.write("a.org", "* A, edited in Emacs\n");
    let answer = server.delete("/api/v1/files/a.org", &[("If-Match", &etag)]).await;
    assert_eq!(answer.status, 409);
    assert_eq!(notes.read("a.org"), "* A, edited in Emacs\n");
}

/// A change stream, read line by line out of the chunks it comes in.
struct ChangeStream {
    reader: BufReader<tokio::net::TcpStream>,
    lines: std::collections::VecDeque<String>,
    partial: String,
    ended: bool,
}

async fn open_stream(server: &TestServer) -> ChangeStream {
    let mut stream = tokio::net::TcpStream::connect(("127.0.0.1", server.port())).await.unwrap();
    stream.write_all(b"GET /api/v1/events HTTP/1.1\r\nHost: localhost\r\n\r\n").await.unwrap();
    let mut reader = BufReader::new(stream);
    let mut head = Vec::new();
    loop {
        let mut line = String::new();
        reader.read_line(&mut line).await.unwrap();
        if line.trim_end().is_empty() {
            break;
        }
        head.push(line.trim_end().to_ascii_lowercase());
    }
    assert_eq!(head[0], "http/1.1 200 ok");
    assert!(head.contains(&"content-type: text/event-stream; charset=utf-8".to_string()), "{head:?}");
    assert!(head.contains(&"cache-control: no-cache".to_string()), "{head:?}");
    assert!(head.contains(&"transfer-encoding: chunked".to_string()), "{head:?}");
    ChangeStream { reader, lines: Default::default(), partial: String::new(), ended: false }
}

impl ChangeStream {
    /// The next line of the stream, or None once the server has ended it.
    async fn next(&mut self) -> Option<String> {
        use tokio::io::AsyncReadExt;
        while self.lines.is_empty() && !self.ended {
            let mut size = String::new();
            if self.reader.read_line(&mut size).await.unwrap_or(0) == 0 {
                self.ended = true;
                break;
            }
            let size = usize::from_str_radix(size.trim(), 16).expect("the size of a chunk");
            let mut chunk = vec![0u8; size + 2];
            if size == 0 || self.reader.read_exact(&mut chunk).await.is_err() {
                self.ended = true;
                break;
            }
            self.partial.push_str(&String::from_utf8_lossy(&chunk[..size]));
            while let Some(end) = self.partial.find('\n') {
                self.lines.push_back(self.partial[..end].to_string());
                self.partial.drain(..=end);
            }
        }
        self.lines.pop_front()
    }
}

async fn next_line(stream: &mut ChangeStream) -> Option<String> {
    tokio::time::timeout(Duration::from_secs(10), stream.next()).await.expect("a line in time")
}

/// A quiet stream still says it is there, so that a client can tell it from a dead connection.
#[tokio::test(flavor = "multi_thread")]
async fn a_quiet_change_stream_sends_heartbeats() {
    let notes = Scratch::with("api", &[("a.org", "* A\n")]);
    let mut app = App::new(FileStore::new(&notes.path), ServerAccess::local_only());
    app.heartbeat_every = Duration::from_millis(100);
    let server = TestServer::start(app, None).await;
    let mut lines = open_stream(&server).await;
    let mut seen = Vec::new();
    while seen.iter().filter(|line| *line == ": ping").count() < 2 {
        seen.push(next_line(&mut lines).await.expect("the stream to go on"));
    }
    assert_eq!(seen[0], ": connected");
    assert!(seen.iter().all(|line| line.is_empty() || line.starts_with(':')), "{seen:?}");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_change_is_told_on_the_stream_as_the_apps_read_it() {
    let (_notes, server) = serve(&[("a.org", "* A\n")]).await;
    let mut lines = open_stream(&server).await;
    assert_eq!(next_line(&mut lines).await.as_deref(), Some(": connected"));
    assert_eq!(next_line(&mut lines).await.as_deref(), Some(""));
    let generation = server.app.journal.generation();
    let etag = server.put("/api/v1/files/he%22ard%20%C3%A9.org", &[], "* heard\n").await.etag();
    assert_eq!(next_line(&mut lines).await.as_deref(), Some("event: changed"));
    assert_eq!(next_line(&mut lines).await.unwrap(), format!("data: {{\"generation\":{},\"path\":\"he\\\"ard é.org\",\"etag\":\"{etag}\",\"deleted\":false}}", generation + 1));
    assert_eq!(next_line(&mut lines).await.as_deref(), Some(""));
    server.delete("/api/v1/files/he%22ard%20%C3%A9.org", &[("If-Match", &etag)]).await;
    assert_eq!(next_line(&mut lines).await.as_deref(), Some("event: deleted"));
    assert_eq!(next_line(&mut lines).await.unwrap(), format!("data: {{\"generation\":{},\"path\":\"he\\\"ard é.org\",\"etag\":null,\"deleted\":true}}", generation + 2));
}

/// The stream closes on a reader that lost events, heartbeat and all, for its app to catch up.
#[tokio::test(flavor = "multi_thread")]
async fn a_change_stream_that_lost_events_closes() {
    let (_notes, server) = serve(&[("a.org", "* A\n")]).await;
    let mut lines = open_stream(&server).await;
    assert_eq!(next_line(&mut lines).await.as_deref(), Some(": connected"));
    tokio::time::sleep(Duration::from_millis(200)).await;
    // Long frames, so that they fill what lies between the server and this reader.
    for n in 0..20_000 {
        server.app.journal.record(&format!("{}{n}.org", "n".repeat(200)), Some(&format!("e{n}")), false);
    }
    let mut last = 0i64;
    let mut heard = 0;
    let ended = tokio::time::timeout(Duration::from_secs(30), async {
        while let Some(line) = lines.next().await {
            if let Some(data) = line.strip_prefix("data: ") {
                let generation = serde_json::from_str::<serde_json::Value>(data).unwrap()["generation"].as_i64().unwrap();
                assert!(last == 0 || generation == last + 1, "an event went missing and the stream went on: {last} then {generation}");
                last = generation;
                heard += 1;
            }
        }
    })
    .await;
    assert!(ended.is_ok(), "the stream never ended");
    assert!(heard < 20_000, "nothing was lost, so this tells nothing");
}

/// A note deleted and then made again since the client last looked is there, not gone: a delta
/// that named it both changed and deleted had the client pull it and then remove it.
#[tokio::test(flavor = "multi_thread")]
async fn a_note_deleted_and_made_again_is_listed_as_there_not_as_deleted() {
    let (_notes, server) = serve(&[("a.org", "* A\n")]).await;
    let since = server.get("/api/v1/manifest").await.json()["generation"].as_i64().unwrap();
    let etag = server.get("/api/v1/files/a.org").await.etag();
    server.delete("/api/v1/files/a.org", &[("If-Match", &etag)]).await;
    server.put("/api/v1/files/a.org", &[("If-None-Match", "*")], "* A again\n").await;
    let delta = server.get(&format!("/api/v1/manifest?since={since}")).await.json();
    assert_eq!(delta["complete"], false);
    assert_eq!(delta["entries"].as_array().unwrap().iter().map(|entry| entry["path"].as_str().unwrap()).collect::<Vec<_>>(), ["a.org"]);
    assert_eq!(delta["deleted"].as_array().unwrap().len(), 0);
    assert_eq!(delta["generation"], since + 2);
}

// --- Edits made outside the server (ExternalEditsTest) ---

/// These notes are also edited in Emacs and delivered by Syncthing, so the server has to answer from
/// the disk rather than from anything it read at startup.
#[tokio::test(flavor = "multi_thread")]
async fn an_edit_made_outside_the_server_is_visible_on_the_next_request() {
    let (notes, server) = serve(&[("inbox.org", "* TODO before\n")]).await;
    assert_eq!(server.get("/api/v1/files/inbox.org").await.text(), "* TODO before\n");
    notes.write("inbox.org", "* TODO edited in Emacs\n");
    assert_eq!(server.get("/api/v1/files/inbox.org").await.text(), "* TODO edited in Emacs\n");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_file_created_outside_the_server_appears_in_the_manifest_and_the_bundle() {
    let (notes, server) = serve(&[("first.org", "* one\n")]).await;
    assert!(server.get("/api/v1/manifest").await.text().contains("first.org"));
    server.get("/api/v1/bundle").await;
    notes.write("second.org", "* two\n");
    assert!(server.get("/api/v1/manifest").await.text().contains("second.org"), "a file added after startup should show up");
    assert!(server.get("/api/v1/bundle").await.text().contains("second.org"));
}

#[tokio::test(flavor = "multi_thread")]
async fn saving_again_after_rereading_succeeds() {
    let (notes, server) = serve(&[("inbox.org", "* TODO before\n")]).await;
    let stale = server.get("/api/v1/files/inbox.org").await.etag();
    notes.write("inbox.org", "* TODO edited in Emacs\n");
    assert_eq!(server.put("/api/v1/files/inbox.org", &[("If-Match", &stale)], "* nope\n").await.status, 409);
    // Re-reading gives the current version, so the next save is accepted.
    let current = server.get("/api/v1/files/inbox.org").await.etag();
    let answer = server.put("/api/v1/files/inbox.org", &[("If-Match", &current)], "* TODO reconciled\n").await;
    assert_eq!(answer.status, 200);
    assert_eq!(notes.read("inbox.org"), "* TODO reconciled\n");
}

#[tokio::test(flavor = "multi_thread")]
async fn reads_carry_an_etag_and_saves_can_use_it() {
    let (notes, server) = serve(&[("inbox.org", "* TODO before\n")]).await;
    let etag = server.get("/api/v1/files/inbox.org").await.etag();
    assert_eq!(server.put("/api/v1/files/inbox.org", &[("If-Match", &etag)], "* TODO after\n").await.status, 200);
    assert_eq!(notes.read("inbox.org"), "* TODO after\n");
    assert_eq!(server.put("/api/v1/files/inbox.org", &[("If-Match", &etag)], "* TODO stale\n").await.status, 409);
    assert_eq!(notes.read("inbox.org"), "* TODO after\n");
}

// --- Starting with this folder or that (ApplicationTest) ---

#[tokio::test(flavor = "multi_thread")]
async fn the_root_says_the_server_is_running_when_there_is_no_web_client() {
    let (_notes, server) = serve(&[]).await;
    for path in ["/", "/api/"] {
        let answer = server.get(path).await;
        assert_eq!(answer.status, 200, "{path}");
        assert_eq!(answer.text(), "Ktor: the KorgNote server is running.", "the words a script may look for, whichever server says them");
    }
    assert_eq!(server.get("/api").await.status, 404);
}

/// Nothing to fall back on: an empty library, which a phone's edits are not written into.
#[tokio::test(flavor = "multi_thread")]
async fn a_missing_notes_directory_is_an_empty_library() {
    let missing = std::env::temp_dir().join("korgnote_temp_notes_does_not_exist");
    let config = ServerConfig { notes_dir: missing.clone(), ..ServerConfig::default() };
    let server = TestServer::local(config.open_store().unwrap()).await;
    let manifest = server.get("/api/v1/manifest").await;
    assert_eq!(manifest.status, 200);
    assert!(manifest.text().contains("\"entries\":[]"), "{}", manifest.text());
    assert_eq!(server.put("/api/v1/files/a.org", &[], "* a\n").await.status, 403);
    assert_eq!(server.put("/api/v1/files/a.org", &[], "* a\n").await.text(), "Read-only library");
    assert_eq!(server.put("/api/v1/files/notes.txt", &[], "* a\n").await.status, 403, "read-only is said before anything about the path");
    assert_eq!(server.delete("/api/v1/files/a.org", &[("If-Match", "abc")]).await.status, 403);
    assert!(!missing.exists());
}

#[tokio::test(flavor = "multi_thread")]
async fn notes_in_folders_are_listed_and_read() {
    let (_notes, server) = serve(&[("root.org", "* Root"), ("Sub/leaf.org", "* Leaf")]).await;
    let body = server.get("/api/v1/manifest").await.text();
    assert!(body.contains("root.org") && body.contains("Sub/leaf.org"), "{body}");
    assert_eq!(server.get("/api/v1/files/Sub/leaf.org").await.text(), "* Leaf");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_save_with_no_condition_replaces_the_note() {
    let (notes, server) = serve(&[("save.org", "* Initial")]).await;
    assert_eq!(server.put("/api/v1/files/save.org", &[], "* Updated Content").await.status, 200);
    assert_eq!(notes.read("save.org"), "* Updated Content");
    assert_eq!(server.get("/api/v1/files/save.org").await.text(), "* Updated Content");
}

fn web_client() -> Scratch {
    Scratch::with("web", &[("index.html", "<html>web client</html>"), ("app.js", "console.log('made up')"), ("sub/index.html", "<html>sub</html>"), ("module.wasm", "\0asm")])
}

#[tokio::test(flavor = "multi_thread")]
async fn the_web_client_is_served_at_the_root_when_present() {
    let (web, notes) = (web_client(), Scratch::new("api"));
    let config = ServerConfig { web_client_dir: Some(web.path.display().to_string()), ..ServerConfig::default() };
    let mut app = App::new(FileStore::new(&notes.path), ServerAccess::local_only());
    app.web_client = config.find_web_client();
    let server = TestServer::start(app, None).await;
    let root = server.get("/").await;
    assert!(root.text().contains("web client"));
    assert_eq!(root.header("content-type"), Some("text/html; charset=UTF-8"));
    assert_eq!(server.get("/app.js").await.header("content-type"), Some("text/javascript; charset=UTF-8"));
    assert_eq!(server.get("/module.wasm").await.header("content-type"), Some("application/wasm"));
    assert_eq!(server.get("/sub").await.text(), "<html>sub</html>");
    assert_eq!(server.get("/sub/../index.html").await.text(), "<html>web client</html>");
    assert_eq!(server.get("/missing.js").await.status, 404);
    assert_eq!(server.get("/api/").await.text(), "Ktor: the KorgNote server is running.", "the API is still there");
    assert_eq!(server.post("/", &[], "").await.status, 404);
    // Nothing above the folder is the web client's to give.
    std::fs::write(web.path.parent().unwrap().join("korgnote-outside-web.txt"), "outside").unwrap();
    assert_eq!(server.get("/../korgnote-outside-web.txt").await.status, 404);
    assert_eq!(server.get("/%2e%2e/korgnote-outside-web.txt").await.status, 404);
    std::fs::remove_file(web.path.parent().unwrap().join("korgnote-outside-web.txt")).unwrap();
}

#[tokio::test(flavor = "multi_thread")]
async fn the_web_client_can_be_turned_off() {
    let (web, notes) = (web_client(), Scratch::new("api"));
    let config = ServerConfig { web_client_dir: Some(web.path.display().to_string()), serve_web_client: false, ..ServerConfig::default() };
    let mut app = App::new(FileStore::new(&notes.path), ServerAccess::local_only());
    app.web_client = config.find_web_client();
    let server = TestServer::start(app, None).await;
    let body = server.get("/").await.text();
    assert!(!body.contains("web client"));
    assert!(body.starts_with("Ktor:"));
    assert_eq!(server.get("/app.js").await.status, 404);
}

// --- What Ktor does around the routes, that a client or a script can see ---

#[tokio::test(flavor = "multi_thread")]
async fn a_wrong_method_is_405_on_a_route_spelled_out_and_404_on_a_note() {
    let (_notes, server) = serve(&[("a.org", "* A\n")]).await;
    for path in ["/api/", "/api/v1/manifest", "/api/v1/bundle", "/api/v1/events", "/devices", "/favicon.png", "/"] {
        for method in ["POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"] {
            assert_eq!(server.request(method, path, &[], None).await.status, 405, "{method} {path}");
        }
    }
    for path in ["/api/pair", "/api/local/pairing", "/devices/pair"] {
        assert_eq!(server.get(path).await.status, 405, "{path}");
    }
    for method in ["POST", "PATCH", "HEAD", "OPTIONS"] {
        assert_eq!(server.request(method, "/api/v1/files/a.org", &[], None).await.status, 404, "{method}");
    }
    assert_eq!(server.get("/devices/nobody/revoke").await.status, 404);
    assert_eq!(server.get("/api/v1/manifest/").await.status, 404, "a slash at the end is another path");
    assert_eq!(server.get("/api//v1//manifest").await.status, 200, "and empty segments are none");
    assert_eq!(server.get("/api/v1/%6Danifest").await.status, 200);
    assert_eq!(server.get("/API/v1/manifest").await.status, 404);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_long_answer_is_compressed_when_asked_and_a_short_one_is_not() {
    let long = "* A long made-up note\n".repeat(200);
    let (_notes, server) = serve(&[("long.org", &long), ("short.org", "* short\n")]).await;
    let gzip = server.request("GET", "/api/v1/files/long.org", &[("Accept-Encoding", "gzip, deflate")], None).await;
    assert_eq!(gzip.header("content-encoding"), Some("gzip"));
    assert_eq!(gzip.header("vary"), Some("Accept-Encoding"));
    assert_eq!(gzip.header("etag"), Some(&sha256_hex(long.as_bytes())[..32]));
    let mut decoded = String::new();
    std::io::Read::read_to_string(&mut flate2::read::GzDecoder::new(gzip.body.as_slice()), &mut decoded).unwrap();
    assert_eq!(decoded, long);

    let deflate = server.request("GET", "/api/v1/files/long.org", &[("Accept-Encoding", "deflate")], None).await;
    assert_eq!(deflate.header("content-encoding"), Some("deflate"));
    let mut decoded = String::new();
    std::io::Read::read_to_string(&mut flate2::read::DeflateDecoder::new(deflate.body.as_slice()), &mut decoded).unwrap();
    assert_eq!(decoded, long, "raw deflate, as Ktor sends it");

    let short = server.request("GET", "/api/v1/files/short.org", &[("Accept-Encoding", "gzip")], None).await;
    assert_eq!(short.header("content-encoding"), None);
    assert_eq!(short.text(), "* short\n");
    let plain = server.get("/api/v1/files/long.org").await;
    assert_eq!(plain.header("content-encoding"), None);
    assert_eq!(plain.text(), long);
    assert_eq!(server.request("GET", "/favicon.png", &[("Accept-Encoding", "gzip")], None).await.header("content-encoding"), None, "a picture is compressed already");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_body_is_read_in_the_charset_it_says_and_kept_as_utf8() {
    let (notes, server) = serve(&[]).await;
    let put = |path: &'static str, kind: &'static str, body: &'static [u8]| {
        let server = &server;
        async move { server.request("PUT", path, &[("Content-Type", kind)], Some(body)).await }
    };
    assert_eq!(put("/api/v1/files/latin1.org", "text/plain; charset=ISO-8859-1", b"* caf\xe9\n").await.status, 200);
    assert_eq!(notes.read("latin1.org"), "* café\n");
    assert_eq!(put("/api/v1/files/utf8.org", "text/plain; charset=UTF-8", "* café\n".as_bytes()).await.status, 200);
    assert_eq!(notes.read("utf8.org"), "* café\n");
    assert_eq!(put("/api/v1/files/lossy.org", "application/octet-stream", b"* \xff\xed\xa0\x80\n").await.status, 200);
    assert_eq!(notes.read("lossy.org"), "* \u{fffd}\u{fffd}\n");
    let refused = put("/api/v1/files/other.org", "text/plain; charset=Shift_JIS", b"* \x93\xfa\n").await;
    assert_eq!(refused.status, 415, "which the Kotlin server would read");
    assert!(!notes.exists("other.org"));
    let nonsense = put("/api/v1/files/nonsense.org", "nonsense", b"* x\n").await;
    assert_eq!(nonsense.status, 400);
    assert_eq!(nonsense.text(), "Illegal Content-Type format: nonsense");
}

/// No more than 64 MB of notes being sent is held at once, so that what the server needs does not
/// grow with how many are sent together. Those that would pass it wait their turn, and all arrive.
#[tokio::test(flavor = "multi_thread")]
async fn large_notes_sent_together_all_arrive() {
    let (notes, server) = serve(&[]).await;
    let server = Arc::new(server);
    let body = Arc::new("x".repeat(20 * 1024 * 1024));
    let sending: Vec<_> = (0..5)
        .map(|n| {
            let (server, body) = (server.clone(), body.clone());
            tokio::spawn(async move { server.put(&format!("/api/v1/files/large-{n}.org"), &[], &body).await.status })
        })
        .collect();
    for upload in sending {
        assert_eq!(upload.await.unwrap(), 200);
    }
    for n in 0..5 {
        assert_eq!(std::fs::metadata(notes.file(&format!("large-{n}.org"))).unwrap().len(), 20 * 1024 * 1024);
    }
    // And small ones are not kept waiting behind them for long: a hundred at once.
    let sending: Vec<_> = (0..100)
        .map(|n| {
            let server = server.clone();
            tokio::spawn(async move { server.put(&format!("/api/v1/files/small-{n}.org"), &[], "* small\n").await.status })
        })
        .collect();
    for upload in sending {
        assert_eq!(upload.await.unwrap(), 200);
    }
}

/// A phone that leaves the network in the middle of a save is never heard from again. What it held
/// of the upload budget must come back, or after a few of them no note could be saved at all.
#[tokio::test(flavor = "multi_thread")]
async fn a_save_that_stops_coming_is_given_up_on_and_holds_nothing_back() {
    let notes = Scratch::new("api");
    let mut app = App::new(FileStore::new(&notes.path), ServerAccess::local_only());
    app.body_idle = Duration::from_millis(400);
    let server = TestServer::start(app, None).await;
    // Three saves of the largest note there can be, of which only the first bytes ever come: more
    // than the whole budget between them.
    let mut stalled = Vec::new();
    for n in 0..3 {
        let mut stream = tokio::net::TcpStream::connect(("127.0.0.1", server.port())).await.unwrap();
        stream.write_all(format!("PUT /api/v1/files/stalled-{n}.org HTTP/1.1\r\nHost: localhost\r\nContent-Length: 33554432\r\n\r\n* the start of a note").as_bytes()).await.unwrap();
        stalled.push(stream);
    }
    tokio::time::sleep(Duration::from_millis(100)).await;
    let saved = tokio::time::timeout(Duration::from_secs(10), server.put("/api/v1/files/after.org", &[], "* saved after\n")).await.expect("a save while others are stalled");
    assert_eq!(saved.status, 200);
    for mut stream in stalled {
        let mut answer = String::new();
        let _ = tokio::time::timeout(Duration::from_secs(10), tokio::io::AsyncReadExt::read_to_string(&mut stream, &mut answer)).await;
        assert!(answer.starts_with("HTTP/1.1 408 "), "{answer}");
    }
    assert_eq!(std::fs::read_dir(&notes.path).unwrap().count(), 1, "nothing of a save that never finished was kept");
    // And with all three given up on, a note of the largest size still goes through.
    assert_eq!(server.put("/api/v1/files/large.org", &[], &"x".repeat(32 * 1024 * 1024)).await.status, 200);
}
