//! What makes this server itself, kept in its state directory, outside the notes, which are synced
//! and backed up elsewhere: an id that outlives its addresses, and the key and self-signed
//! certificate it serves paired devices with.
//!
//! Paired devices trust that certificate by its fingerprint alone, which the pairing QR code
//! carries, so there is no authority to ask, no domain to own and nothing to renew: it is valid for
//! a hundred years. Losing the directory means a new certificate, and pairing each device again.
//!
//! The files are those releases up to 1.8 (the JVM server) wrote, read and written as they were,
//! so that a state directory from one of them keeps its identity and its paired devices:
//! `server-id`, `tls.password`, and `tls.jks`, which for all its name is a PKCS#12 store, as every
//! JDK since 9 writes one, and those releases only ever ran on newer. The store of older JDKs, JKS,
//! is not read.
//!
//! A new key is an EC P-256 one, where those releases made an RSA one: devices trust the
//! certificate by its fingerprint, whatever its key, so either serves.

use crate::text::{random_hex, random_uuid, sha256_hex};
use sha2::{Digest, Sha256};
use std::fs;
use std::io;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

pub const KEY_ALIAS: &str = "korgnote";

pub struct ServerIdentity {
    pub server_id: String,
    /// SHA-256 of the certificate, lower-case hex: what a paired device checks the server against.
    pub fingerprint: String,
    /// The certificate, DER.
    pub certificate: Vec<u8>,
    /// Its private key, PKCS#8 DER.
    pub private_key: Vec<u8>,
}

fn invalid(message: String) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

impl ServerIdentity {
    pub fn load_or_create(dir: &Path) -> io::Result<ServerIdentity> {
        fs::create_dir_all(dir)?;
        owner_only(dir);
        let server_id = read_or_create(&dir.join("server-id"), random_uuid)?;
        let password = read_or_create(&dir.join("tls.password"), || random_hex(24))?;
        let store_file = dir.join("tls.jks");
        let (certificate, private_key) = if store_file.is_file() {
            read_key_store(&fs::read(&store_file)?, &password).map_err(|error| invalid(format!("{}: {error}", store_file.display())))?
        } else {
            let (certificate, private_key) = make_certificate(&server_id).map_err(io::Error::other)?;
            fs::write(&store_file, write_key_store(&certificate, &private_key, &password).map_err(io::Error::other)?)?;
            owner_only(&store_file);
            (certificate, private_key)
        };
        Ok(ServerIdentity { server_id, fingerprint: sha256_hex(&certificate), certificate, private_key })
    }

    /// What rustls needs to serve with this identity: TLS 1.2 and 1.3, HTTP/2 and HTTP/1.1.
    pub fn tls_config(&self) -> Result<rustls::ServerConfig, rustls::Error> {
        use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
        let provider = std::sync::Arc::new(rustls::crypto::ring::default_provider());
        let mut config = rustls::ServerConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()?
            .with_no_client_auth()
            .with_single_cert(
                vec![CertificateDer::from(self.certificate.clone())],
                PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.private_key.clone())),
            )?;
        config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
        Ok(config)
    }
}

/// The text of `file`, written first by `create` when it is missing or empty.
fn read_or_create(file: &Path, create: impl FnOnce() -> String) -> io::Result<String> {
    if file.is_file() {
        let text = fs::read_to_string(file)?;
        let text = crate::text::trim(&text);
        if !text.is_empty() {
            return Ok(text.to_string());
        }
    }
    let value = create();
    fs::write(file, format!("{value}\n"))?;
    owner_only(file);
    Ok(value)
}

/// Readable by its owner only, where the file system says who may read what.
fn owner_only(file: &Path) {
    let mode = if file.is_dir() { 0o700 } else { 0o600 };
    let _ = fs::set_permissions(file, fs::Permissions::from_mode(mode));
}

/// An EC P-256 key and a certificate for it, signed by itself and good for a hundred years, with
/// the names earlier releases gave theirs. Returns (certificate DER, PKCS#8 key DER).
fn make_certificate(server_id: &str) -> Result<(Vec<u8>, Vec<u8>), String> {
    let key_pair = rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).map_err(|error| format!("could not make a key: {error}"))?;
    let mut params = rcgen::CertificateParams::new(vec!["korgnote".to_string()]).map_err(|error| error.to_string())?;
    params.subject_alt_names.push(rcgen::SanType::IpAddress(std::net::IpAddr::V4(std::net::Ipv4Addr::LOCALHOST)));
    let mut name = rcgen::DistinguishedName::new();
    name.push(rcgen::DnType::CommonName, format!("KorgNote {server_id}"));
    params.distinguished_name = name;
    params.extended_key_usages = vec![rcgen::ExtendedKeyUsagePurpose::ServerAuth];
    let now = time::OffsetDateTime::now_utc();
    params.not_before = now;
    params.not_after = now + time::Duration::days(36_500);
    let certificate = params.self_signed(&key_pair).map_err(|error| format!("could not sign the certificate: {error}"))?;
    Ok((certificate.der().to_vec(), key_pair.serialize_der()))
}

/// (certificate DER, PKCS#8 key DER) from a PKCS#12 key store, under the alias earlier releases used.
fn read_key_store(data: &[u8], password: &str) -> Result<(Vec<u8>, Vec<u8>), String> {
    if data.starts_with(&[0xFE, 0xED, 0xFE, 0xED]) {
        return Err("it is a JKS store, which only JDKs before 9 write, and this server does not read; move it away to make a new one.".to_string());
    }
    let store = p12_keystore::KeyStore::from_pkcs12(data, password, p12_keystore::Pkcs12ImportPolicy::Strict).map_err(|error| error.to_string())?;
    match store.entry(KEY_ALIAS) {
        Some(p12_keystore::KeyStoreEntry::PrivateKeyChain(chain)) => {
            let certificate = chain.certs().first().ok_or_else(no_certificate)?;
            Ok((certificate.as_der().to_vec(), chain.key().as_der().to_vec()))
        }
        _ => Err(no_certificate()),
    }
}

fn no_certificate() -> String {
    format!("it holds no '{KEY_ALIAS}' certificate; move it away to make a new one.")
}

fn write_key_store(certificate: &[u8], private_key: &[u8], password: &str) -> Result<Vec<u8>, String> {
    use p12_keystore::{Certificate, KeyStore, KeyStoreEntry, PrivateKey, PrivateKeyChain};
    // Only pairs the key with its certificate in the store; earlier releases wrote "Time …" there.
    let key_id = Sha256::digest(certificate).to_vec();
    let chain = PrivateKeyChain::new(
        key_id,
        PrivateKey::from_der(private_key).map_err(|error| error.to_string())?,
        [Certificate::from_der(certificate).map_err(|error| error.to_string())?],
    );
    let mut store = KeyStore::new();
    store.add_entry(KEY_ALIAS, KeyStoreEntry::PrivateKeyChain(chain));
    store.writer(password).write().map_err(|error| error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch() -> std::path::PathBuf {
        std::env::temp_dir().join(format!("korgnote-state-{}", random_hex(8)))
    }

    #[test]
    fn the_identity_is_made_once_and_kept() {
        let dir = scratch();
        let other_dir = scratch();
        let first = ServerIdentity::load_or_create(&dir).unwrap();
        let again = ServerIdentity::load_or_create(&dir).unwrap();
        assert_eq!(first.server_id, again.server_id);
        assert_eq!(first.fingerprint, again.fingerprint);
        assert_eq!(first.private_key, again.private_key);
        assert_eq!(first.fingerprint.len(), 64);
        assert!(first.fingerprint.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()));
        let other = ServerIdentity::load_or_create(&other_dir).unwrap();
        assert_ne!(first.fingerprint, other.fingerprint);
        assert!(first.tls_config().is_ok(), "the key and the certificate are a pair TLS can serve with");
        fs::remove_dir_all(dir).unwrap();
        fs::remove_dir_all(other_dir).unwrap();
    }

    #[test]
    fn the_files_are_for_their_owner_only() {
        let dir = scratch();
        ServerIdentity::load_or_create(&dir).unwrap();
        let mode = |name: &str| fs::metadata(dir.join(name)).unwrap().permissions().mode() & 0o777;
        assert_eq!(fs::metadata(&dir).unwrap().permissions().mode() & 0o777, 0o700);
        assert_eq!(mode("server-id"), 0o600);
        assert_eq!(mode("tls.password"), 0o600);
        assert_eq!(mode("tls.jks"), 0o600);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn an_empty_id_file_is_filled_again() {
        let dir = scratch();
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("server-id"), "  \n").unwrap();
        let identity = ServerIdentity::load_or_create(&dir).unwrap();
        assert_eq!(fs::read_to_string(dir.join("server-id")).unwrap(), format!("{}\n", identity.server_id));
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_store_without_the_certificate_says_what_to_do() {
        let dir = scratch();
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("tls.password"), "secret\n").unwrap();
        let empty = p12_keystore::KeyStore::new().writer("secret").write().unwrap();
        fs::write(dir.join("tls.jks"), empty).unwrap();
        let error = ServerIdentity::load_or_create(&dir).err().unwrap().to_string();
        assert!(error.contains("holds no 'korgnote' certificate"), "{error}");
        fs::remove_dir_all(dir).unwrap();
    }

    /// A state directory a release up to 1.8 (the JVM server) made: its `tls.jks` is PKCS#12.
    #[test]
    fn an_earlier_releases_identity_is_read() {
        let fixture = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/state-1.x");
        let dir = scratch();
        fs::create_dir_all(&dir).unwrap();
        for name in ["server-id", "tls.password", "tls.jks"] {
            fs::copy(fixture.join(name), dir.join(name)).unwrap();
        }
        let identity = ServerIdentity::load_or_create(&dir).unwrap();
        assert_eq!(identity.server_id, fs::read_to_string(fixture.join("server-id")).unwrap().trim());
        assert_eq!(identity.fingerprint, fs::read_to_string(fixture.join("fingerprint")).unwrap().trim());
        assert!(identity.tls_config().is_ok());
        fs::remove_dir_all(dir).unwrap();
    }

    /// The store of JDKs before 9 is refused, with what to do about it, not misread.
    #[test]
    fn a_jks_store_says_what_to_do() {
        let dir = scratch();
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("tls.password"), "secret\n").unwrap();
        fs::write(dir.join("tls.jks"), [0xFE, 0xED, 0xFE, 0xED, 0, 0, 0, 2, 0, 0, 0, 0]).unwrap();
        let error = ServerIdentity::load_or_create(&dir).err().unwrap().to_string();
        assert!(error.contains("JKS") && error.contains("move it away"), "{error}");
        fs::remove_dir_all(dir).unwrap();
    }
}
