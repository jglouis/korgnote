//! Pairings waiting for someone at the computer to allow them on the Devices page.
//!
//! A pairing code is in a QR code, and a QR code read by the phone's camera goes to whichever app
//! claims `korgnote://` links: another app on the phone could take the code and pair in KorgNote's
//! place. So a code is not enough. The device that brings one is shown on the Devices page, by its
//! name and where it is, and gets its token only once someone there presses Allow.

use std::net::IpAddr;
use std::sync::Mutex;
use tokio::sync::oneshot;

/// A device asking to pair, as the Devices page shows it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Request {
    pub id: String,
    pub name: String,
    pub from: IpAddr,
    /// Epoch milliseconds.
    pub asked_at: i64,
}

struct Waiting {
    request: Request,
    answer: oneshot::Sender<bool>,
}

/// The pairings waiting for an answer.
#[derive(Default)]
pub struct Approvals {
    waiting: Mutex<Vec<Waiting>>,
}

/// A pairing waiting for its answer, which [answer](Self::answer) brings. Dropped, as when the
/// device gives up or the wait runs out, it is no longer shown.
pub struct Asked<'a> {
    approvals: &'a Approvals,
    pub id: String,
    pub answer: oneshot::Receiver<bool>,
}

impl Drop for Asked<'_> {
    fn drop(&mut self) {
        self.approvals.lock().retain(|waiting| waiting.request.id != self.id);
    }
}

impl Approvals {
    fn lock(&self) -> std::sync::MutexGuard<'_, Vec<Waiting>> {
        self.waiting.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Shows the device called `name`, asking from `from`, on the Devices page, until it is answered.
    pub fn ask(&self, name: &str, from: IpAddr, now: i64) -> Asked<'_> {
        let id = crate::text::random_hex(16);
        let (sender, receiver) = oneshot::channel();
        let request = Request { id: id.clone(), name: name.to_string(), from: from.to_canonical(), asked_at: now };
        self.lock().push(Waiting { request, answer: sender });
        Asked { approvals: self, id, answer: receiver }
    }

    /// Those waiting, the earliest first.
    pub fn waiting(&self) -> Vec<Request> {
        self.lock().iter().map(|waiting| waiting.request.clone()).collect()
    }

    /// Allows the pairing `id`, or refuses it; false when there is no such pairing waiting any more.
    pub fn answer(&self, id: &str, allow: bool) -> bool {
        let mut waiting = self.lock();
        let Some(at) = waiting.iter().position(|waiting| waiting.request.id == id) else { return false };
        waiting.remove(at).answer.send(allow).is_ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn here() -> IpAddr {
        "192.168.1.42".parse().unwrap()
    }

    #[test]
    fn a_pairing_waits_until_it_is_answered() {
        let approvals = Approvals::default();
        let mut asked = approvals.ask("Pixel", here(), 1);
        assert_eq!(approvals.waiting(), vec![Request { id: asked.id.clone(), name: "Pixel".into(), from: here(), asked_at: 1 }]);
        assert!(asked.answer.try_recv().is_err(), "no answer yet");
        assert!(approvals.answer(&asked.id, true));
        assert_eq!(asked.answer.try_recv(), Ok(true));
        assert!(approvals.waiting().is_empty());
        assert!(!approvals.answer(&asked.id, true), "answered once");
    }

    #[test]
    fn one_given_up_on_is_no_longer_shown() {
        let approvals = Approvals::default();
        let asked = approvals.ask("Pixel", here(), 1);
        let id = asked.id.clone();
        drop(asked);
        assert!(approvals.waiting().is_empty());
        assert!(!approvals.answer(&id, true));
    }

    #[test]
    fn a_refusal_is_told() {
        let approvals = Approvals::default();
        let mut asked = approvals.ask("Pixel", here(), 1);
        assert!(approvals.answer(&asked.id, false));
        assert_eq!(asked.answer.try_recv(), Ok(false));
    }
}
