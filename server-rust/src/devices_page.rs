//! The Devices page, on the plain port only: every paired device with a button to revoke it, how
//! the watching of the notes folder is doing, and a button that shows a QR code to pair another.
//! A port of the pages in `server/…/DevicesPage.kt`, word for word.

use crate::pairing::{PairingLink, qr_svg};
use crate::security::ServerAccess;
use crate::text::escape_html as escape;
use crate::watcher::{WatcherState, WatcherStatus, duration_text};
use crate::clock;

/// A `<time>` the page's script shows in the browser's zone; the server's own, without script.
fn time(epoch_millis: i64) -> String {
    let instant = clock::utc(epoch_millis);
    // As Java writes an instant: the seconds always, and the milliseconds when there are any.
    let fraction = if instant.millisecond() == 0 { String::new() } else { format!(".{:03}", instant.millisecond()) };
    let iso = format!("{}{fraction}Z", clock::date_time(instant).replace(' ', "T"));
    let shown = clock::date_time(clock::local(epoch_millis));
    format!("<time datetime=\"{iso}\">{shown}</time>")
}

/// The devices, and the form that shows a pairing code. `problem` is said above the form when the
/// last try made no code, with what was typed, `typed`, kept in the box to correct.
pub fn devices_body(access: &ServerAccess, watcher: Option<&WatcherStatus>, problem: Option<&str>, typed: Option<&str>) -> String {
    let mut out = String::from("<h1>Devices</h1>");
    let mut devices = access.devices.all();
    if devices.is_empty() {
        out.push_str("<p class=muted>No device is paired yet.</p>");
    } else {
        out.push_str("<table><tr><th>Device</th><th>Paired</th><th>Last seen</th><th></th></tr>");
        devices.sort_by_key(|device| device.paired_at);
        for device in &devices {
            out.push_str(&format!("<tr><td>{}</td>", escape(&device.name)));
            out.push_str(&format!("<td>{}</td>", time(device.paired_at)));
            out.push_str(&format!("<td>{}</td>", device.last_seen.map(time).unwrap_or_else(|| "never".to_string())));
            // The name goes in an attribute for the page's script to read, never into script itself:
            // a browser decodes `onsubmit` before parsing it, so no escaping keeps a name in its string.
            out.push_str(&format!("<td><form method=post action=\"/devices/{}/revoke\" ", escape(&device.id)));
            out.push_str(&format!("data-name=\"{}\"><button>Revoke</button></form></td></tr>", escape(&device.name)));
        }
        out.push_str("</table>");
    }
    out.push_str(&notes_folder_section(watcher, clock::now_millis()));
    if let Some(details) = &access.pairing {
        out.push_str("<h2>Pair a device</h2>");
        out.push_str("<p>Shows a QR code to scan in KorgNote's Settings. It works once, for 10 minutes.</p>");
        if let Some(warning) = problem.or_else(|| details.missing_address_advice()) {
            out.push_str(&format!("<p class=warning role=alert>{}</p>", escape(warning)));
        }
        out.push_str("<form method=post action=/devices/pair>");
        out.push_str("<label>Addresses the phone can reach this computer at<br>");
        let shown = typed.map(str::to_string).unwrap_or_else(|| details.addresses().join(", "));
        out.push_str(&format!("<input name=addresses size=40 placeholder=\"192.168.1.20\" value=\"{}\"></label> ", escape(&shown)));
        out.push_str("<button>Show a pairing code</button></form>");
        let fingerprint: Vec<String> = details.fingerprint.chars().collect::<Vec<_>>().chunks(4).take(4).map(|chunk| chunk.iter().collect()).collect();
        out.push_str(&format!("<p class=muted>Server {} · certificate {}…</p>", escape(&details.server_name), fingerprint.join(" ")));
    }
    out
}

/// Whether edits made on this computer reach the devices. A phone cannot tell: when the server stops
/// noticing them, every sync still succeeds and brings nothing. So this says it, in words, and warns
/// when anything is off.
pub fn notes_folder_section(status: Option<&WatcherStatus>, now: i64) -> String {
    let mut out = String::from("<h2>Notes folder</h2>");
    let Some(status) = status else {
        out.push_str("<p class=muted>Edits made outside the server are not watched: the notes folder was not found when it started.</p>");
        return out;
    };
    let warn = |text: String| format!("<p class=warning role=alert>{text}</p>");
    let check_every = status.check_every.map(duration_text).unwrap_or_else(|| "Infinity".to_string());

    match status.state {
        WatcherState::Starting => out.push_str("<p class=muted>Starting to watch the notes folder…</p>"),
        WatcherState::NotRunning => out.push_str(&warn(format!(
            "The server is not watching the notes folder{}. Edits made on this computer will not reach the devices until the server is restarted.",
            status.last_failure.as_ref().map(|failure| format!(": {}", escape(failure))).unwrap_or_default()
        ))),
        WatcherState::Failing => out.push_str(&warn(format!(
            "The server cannot watch the notes folder since {}: {}. Edits made on this computer reach the devices only when it manages to look again; the server log has the details.",
            time(status.since),
            escape(status.last_failure.as_deref().unwrap_or(""))
        ))),
        WatcherState::Watching => {
            // A check runs every check_every; three missed in a row is a watcher that is stuck.
            let stuck = match (status.last_check_at, status.check_every) {
                (Some(last_check), Some(every)) => (now - last_check > every.as_millis() as i64 * 3 + 30_000).then_some(last_check),
                _ => None,
            };
            match stuck {
                Some(last_check) => out.push_str(&warn(format!(
                    "The notes folder has not been checked since {}. Edits made on this computer may not reach the devices; restart the server.",
                    time(last_check)
                ))),
                None => out.push_str("<p>Watching for edits made on this computer.</p>"),
            }
        }
    }
    if status.missed > 0 {
        let one = status.missed == 1;
        out.push_str(&warn(format!(
            "{} {} made on this computer {} found only by the check that runs every {check_every}, most recently {}{}. They reached the devices, but late: the server is missing file events. The server log has the details.",
            status.missed,
            if one { "edit" } else { "edits" },
            if one { "was" } else { "were" },
            status.last_missed_at.map(time).unwrap_or_default(),
            if status.last_missed_paths.is_empty() { String::new() } else { format!(" ({})", escape(&status.last_missed_paths.join(", "))) },
        )));
    }
    let recovered = matches!(status.state, WatcherState::Watching | WatcherState::Starting);
    if let (true, Some(failure), Some(at)) = (recovered, &status.last_failure, status.last_failure_at) {
        out.push_str(&format!("<p class=muted>Last problem watching it, since recovered: {}, {}</p>", time(at), escape(failure)));
    }
    out.push_str("<p class=muted>");
    out.push_str(&format!("{}{}", status.notes, if status.notes == 1 { " note" } else { " notes" }));
    out.push_str(&format!(" in {}{}", status.folders, if status.folders == 1 { " folder" } else { " folders" }));
    if let Some(at) = status.last_check_at {
        out.push_str(&format!(" · last full check {}", time(at)));
    }
    if let Some(at) = status.last_change_at {
        out.push_str(&format!(" · last edit seen {}", time(at)));
    }
    if status.rebuilds > 0 {
        out.push_str(&format!(" · folders changed {}{}", status.rebuilds, if status.rebuilds == 1 { " time" } else { " times" }));
    }
    out.push_str("</p>");
    out
}

/// The page that shows a pairing code, or None when the link is too long to draw as one.
pub fn pairing_body(link: &PairingLink) -> Option<String> {
    let uri = link.to_uri();
    let mut out = String::from("<h1>Pair a device</h1>");
    out.push_str("<p>In KorgNote on the phone: Settings, <b>Pair with a computer</b>, <b>Scan the code</b>.</p>");
    out.push_str(&format!("<div class=qr>{}</div>", qr_svg(&uri, 6)?));
    out.push_str(&format!("<p class=muted>Valid once, for 10 minutes. Addresses: {}", escape(&link.addresses.join(", "))));
    out.push_str(&format!(" · port {}</p>", link.port));
    out.push_str(&format!("<details><summary>Or paste the link instead</summary><p><code>{}</code></p></details>", escape(&uri)));
    out.push_str("<p><a href=/devices>Back to the devices</a></p>");
    Some(out)
}

pub fn page(body: &str) -> String {
    format!("{PAGE_HEAD}{body}{PAGE_TAIL}")
}

const PAGE_HEAD: &str = r#"<!doctype html>
<html lang=en><head><meta charset=utf-8><meta name=viewport content="width=device-width, initial-scale=1">
<title>KorgNote devices</title>
<style>
body{font:16px/1.5 system-ui,sans-serif;max-width:44rem;margin:2rem auto;padding:0 1rem;color:#1c1b1f;background:#fffbfe}
table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:.4rem .6rem;border-bottom:1px solid #ddd}
button{font:inherit;padding:.3rem .9rem;cursor:pointer}input{font:inherit;padding:.3rem}
.muted{color:#79747e}.qr svg{max-width:100%;height:auto}code{word-break:break-all}
.warning{border-left:4px solid #b3261e;padding:.5rem .8rem;background:#f9dedc;color:#410e0b}
@media (prefers-color-scheme:dark){body{color:#e6e1e5;background:#1c1b1f}th,td{border-color:#49454f}.muted{color:#938f99}.warning{background:#601410;color:#f9dedc;border-color:#f2b8b5}}
</style></head><body>"#;

const PAGE_TAIL: &str = r#"
<script>
// The server may run without a time zone (in Docker it is UTC): show its times in the browser's.
for (const t of document.querySelectorAll("time[datetime]")) {
  const d = new Date(t.dateTime)
  if (!isNaN(d)) t.textContent = d.toLocaleString(undefined, {dateStyle: "medium", timeStyle: "medium"})
}
for (const form of document.querySelectorAll("form[data-name]")) {
  form.addEventListener("submit", event => {
    if (!confirm("Revoke " + form.dataset.name + "? It will stop syncing until it is paired again.")) event.preventDefault()
  })
}
</script></body></html>"#;

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    const NOW: i64 = 1_800_000_000_000;

    fn healthy() -> WatcherStatus {
        WatcherStatus {
            state: WatcherState::Watching,
            since: NOW - 3_600_000,
            folders: 4,
            notes: 120,
            last_change_at: Some(NOW - 120_000),
            last_check_at: Some(NOW - 20_000),
            check_every: Some(Duration::from_secs(60)),
            rebuilds: 0,
            missed: 0,
            last_missed_at: None,
            last_missed_paths: Vec::new(),
            last_failure: None,
            last_failure_at: None,
        }
    }

    #[test]
    fn a_healthy_watcher_says_so_without_a_warning() {
        let html = notes_folder_section(Some(&healthy()), NOW);
        assert!(html.contains("Watching for edits made on this computer."));
        assert!(html.contains("120 notes in 4 folders"));
        assert!(html.contains("last full check <time"));
        assert!(!html.contains("warning"));
    }

    #[test]
    fn a_watcher_that_stopped_checking_is_warned_about() {
        let status = WatcherStatus { last_check_at: Some(NOW - 600_000), ..healthy() };
        let html = notes_folder_section(Some(&status), NOW);
        assert!(html.contains("class=warning"));
        assert!(html.contains("has not been checked since"));
        assert!(!html.contains("Watching for edits"));
    }

    #[test]
    fn edits_found_only_by_the_check_are_warned_about_with_their_paths() {
        let status = WatcherStatus { missed: 2, last_missed_at: Some(NOW - 60_000), last_missed_paths: vec!["inbox.org".into(), "a<b>.org".into()], ..healthy() };
        let html = notes_folder_section(Some(&status), NOW);
        assert!(html.contains("class=warning"));
        assert!(html.contains("2 edits made on this computer were found only by the check that runs every 1m, most recently <time"), "{html}");
        assert!(html.contains("inbox.org, a&lt;b&gt;.org"));
        let one = WatcherStatus { missed: 1, last_missed_at: Some(NOW), last_missed_paths: vec![], ..healthy() };
        assert!(notes_folder_section(Some(&one), NOW).contains("1 edit made on this computer was found only"));
    }

    #[test]
    fn a_failing_watcher_is_warned_about_with_the_error() {
        let status = WatcherStatus { state: WatcherState::Failing, last_failure: Some("User limit of inotify watches reached".into()), last_failure_at: Some(NOW), ..healthy() };
        let html = notes_folder_section(Some(&status), NOW);
        assert!(html.contains("class=warning"));
        assert!(html.contains("cannot watch the notes folder"));
        assert!(html.contains("User limit of inotify watches reached"));
    }

    #[test]
    fn a_watcher_that_is_not_running_says_edits_will_not_arrive() {
        let status = WatcherStatus { state: WatcherState::NotRunning, last_failure: Some("/notes is not a folder".into()), ..healthy() };
        let html = notes_folder_section(Some(&status), NOW);
        assert!(html.contains("class=warning"));
        assert!(html.contains("will not reach the devices until the server is restarted"));
    }

    #[test]
    fn a_recovered_failure_is_mentioned_quietly() {
        let status = WatcherStatus { last_failure: Some("gone".into()), last_failure_at: Some(NOW - 60_000), ..healthy() };
        let html = notes_folder_section(Some(&status), NOW);
        assert!(!html.contains("class=warning"));
        assert!(html.contains("since recovered"));
    }

    #[test]
    fn no_watcher_is_said() {
        assert!(notes_folder_section(None, NOW).contains("not watched"));
    }

    #[test]
    fn a_time_is_written_as_java_writes_an_instant() {
        assert!(time(1_800_000_000_000).starts_with("<time datetime=\"2027-01-15T08:00:00Z\">"));
        assert!(time(1_800_000_000_123).starts_with("<time datetime=\"2027-01-15T08:00:00.123Z\">"));
        assert!(time(1_800_000_000_120).starts_with("<time datetime=\"2027-01-15T08:00:00.120Z\">"));
    }

    #[test]
    fn the_page_is_one_document_with_the_script_at_its_end() {
        let html = page("<h1>x</h1>");
        assert!(html.starts_with("<!doctype html>\n<html lang=en>"));
        assert!(html.contains("<body><h1>x</h1>\n<script>"));
        assert!(html.ends_with("</script></body></html>"));
    }
}
