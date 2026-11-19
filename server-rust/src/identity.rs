//! What makes this server itself, kept in its state directory, outside the notes, which are synced
//! and backed up elsewhere: an id that outlives its addresses, and the key and self-signed
//! certificate it serves paired devices with. A port of `server/…/ServerIdentity.kt`.
//!
//! Paired devices trust that certificate by its fingerprint alone, which the pairing QR code
//! carries, so there is no authority to ask, no domain to own and nothing to renew: it is valid for
//! a hundred years. Losing the directory means a new certificate, and pairing each device again.
//!
//! The files are the Kotlin server's, read and written as it reads and writes them, so that one
//! state directory serves either: `server-id`, `tls.password`, and `tls.jks`, which for all its name
//! is a PKCS#12 store when a current JDK made it, and a JKS one from an old JDK. Both are read; a new
//! one is written as PKCS#12, which the Kotlin server reads too.

use crate::text::{random_hex, random_uuid, sha256_hex};
use sha1::{Digest, Sha1};
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

/// A 2048-bit RSA key and a certificate for it, signed by itself and good for a hundred years, as
/// the Kotlin server makes them. Returns (certificate DER, PKCS#8 key DER).
fn make_certificate(server_id: &str) -> Result<(Vec<u8>, Vec<u8>), String> {
    use rsa::pkcs8::EncodePrivateKey;
    let mut rng = getrandom::SysRng;
    let key = rsa::PrivateKey::new(&mut rng, 2048).map_err(|error| format!("could not make a key: {error}"))?;
    let key_der = key.to_pkcs8_der().map_err(|error| format!("could not write the key: {error}"))?.as_bytes().to_vec();

    let pkcs8 = rustls::pki_types::PrivatePkcs8KeyDer::from(key_der.clone());
    let key_pair = rcgen::KeyPair::from_pkcs8_der_and_sign_algo(&pkcs8, &rcgen::PKCS_RSA_SHA256).map_err(|error| format!("could not use the key: {error}"))?;
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
    Ok((certificate.der().to_vec(), key_der))
}

/// (certificate DER, PKCS#8 key DER) from a key store of either kind, under the alias the Kotlin
/// server uses.
fn read_key_store(data: &[u8], password: &str) -> Result<(Vec<u8>, Vec<u8>), String> {
    if data.starts_with(&[0xFE, 0xED, 0xFE, 0xED]) {
        return jks::read(data, password);
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
    let key_id = Sha1::digest(certificate).to_vec();
    let chain = PrivateKeyChain::new(
        key_id,
        PrivateKey::from_der(private_key).map_err(|error| error.to_string())?,
        [Certificate::from_der(certificate).map_err(|error| error.to_string())?],
    );
    let mut store = KeyStore::new();
    store.add_entry(KEY_ALIAS, KeyStoreEntry::PrivateKeyChain(chain));
    store.writer(password).write().map_err(|error| error.to_string())
}

/// The key store of JDKs before 9, which a state directory that has been kept a long time may hold.
mod jks {
    use sha1::{Digest, Sha1};

    struct Reader<'a> {
        data: &'a [u8],
        at: usize,
    }

    impl<'a> Reader<'a> {
        fn take(&mut self, count: usize) -> Result<&'a [u8], String> {
            let end = self.at.checked_add(count).filter(|end| *end <= self.data.len()).ok_or("the key store is cut short")?;
            let slice = &self.data[self.at..end];
            self.at = end;
            Ok(slice)
        }
        fn u16(&mut self) -> Result<usize, String> {
            Ok(u16::from_be_bytes(self.take(2)?.try_into().unwrap()) as usize)
        }
        fn u32(&mut self) -> Result<usize, String> {
            Ok(u32::from_be_bytes(self.take(4)?.try_into().unwrap()) as usize)
        }
        fn text(&mut self) -> Result<String, String> {
            let length = self.u16()?;
            Ok(String::from_utf8_lossy(self.take(length)?).into_owned())
        }
        fn certificate(&mut self, version: usize) -> Result<Vec<u8>, String> {
            if version == 2 {
                self.text()?;
            }
            let length = self.u32()?;
            Ok(self.take(length)?.to_vec())
        }
    }

    fn password_bytes(password: &str) -> Vec<u8> {
        password.encode_utf16().flat_map(u16::to_be_bytes).collect()
    }

    /// One DER element at the start of `data`: (tag, contents, what follows).
    fn der(data: &[u8]) -> Result<(u8, &[u8], &[u8]), String> {
        let cut = "the key in the key store is not written as expected";
        let (&tag, rest) = data.split_first().ok_or(cut)?;
        let (&first, rest) = rest.split_first().ok_or(cut)?;
        let (length, rest) = if first < 0x80 {
            (first as usize, rest)
        } else {
            let count = (first & 0x7f) as usize;
            if count == 0 || count > 4 || rest.len() < count {
                return Err(cut.to_string());
            }
            (rest[..count].iter().fold(0usize, |length, byte| (length << 8) | *byte as usize), &rest[count..])
        };
        if rest.len() < length {
            return Err(cut.to_string());
        }
        Ok((tag, &rest[..length], &rest[length..]))
    }

    /// Sun's own protection of a key, which is all a JKS store has: the key XORed with a stream of
    /// SHA-1 digests of the password and a salt, and a digest of both to check it by.
    fn unprotect(encrypted_info: &[u8], password: &[u8]) -> Result<Vec<u8>, String> {
        let (_, sequence, _) = der(encrypted_info)?;
        let (_, _algorithm, after) = der(sequence)?;
        let (tag, protected, _) = der(after)?;
        if tag != 0x04 || protected.len() < 40 {
            return Err("the key in the key store is not protected as expected".to_string());
        }
        let (salt, rest) = protected.split_at(20);
        let (encrypted, check) = rest.split_at(rest.len() - 20);
        let mut key = Vec::with_capacity(encrypted.len());
        let mut digest = Sha1::new().chain_update(password).chain_update(salt).finalize();
        for block in encrypted.chunks(20) {
            key.extend(block.iter().zip(digest.iter()).map(|(byte, mask)| byte ^ mask));
            digest = Sha1::new().chain_update(password).chain_update(digest).finalize();
        }
        if Sha1::new().chain_update(password).chain_update(&key).finalize().as_slice() != check {
            return Err("the password in tls.password does not open the key".to_string());
        }
        Ok(key)
    }

    pub fn read(data: &[u8], password: &str) -> Result<(Vec<u8>, Vec<u8>), String> {
        let password = password_bytes(password);
        if data.len() < 32 {
            return Err("the key store is cut short".to_string());
        }
        let (body, seal) = data.split_at(data.len() - 20);
        if Sha1::new().chain_update(&password).chain_update(b"Mighty Aphrodite").chain_update(body).finalize().as_slice() != seal {
            return Err("the password in tls.password does not open the key store, or it was changed".to_string());
        }
        let mut reader = Reader { data: body, at: 4 };
        let version = reader.u32()?;
        if version != 1 && version != 2 {
            return Err(format!("a key store of version {version} is not one this server reads"));
        }
        for _ in 0..reader.u32()? {
            let tag = reader.u32()?;
            let alias = reader.text()?;
            reader.take(8)?;
            match tag {
                1 => {
                    let key_length = reader.u32()?;
                    let protected = reader.take(key_length)?;
                    let chain_length = reader.u32()?;
                    let mut chain = Vec::new();
                    for _ in 0..chain_length {
                        chain.push(reader.certificate(version)?);
                    }
                    if alias.eq_ignore_ascii_case(super::KEY_ALIAS) {
                        let certificate = chain.into_iter().next().ok_or_else(super::no_certificate)?;
                        return Ok((certificate, unprotect(protected, &password)?));
                    }
                }
                2 => {
                    reader.certificate(version)?;
                }
                other => return Err(format!("the key store has an entry of a kind this server does not know ({other})")),
            }
        }
        Err(super::no_certificate())
    }
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

    /// A state directory the Kotlin server made, with a current JDK: its `tls.jks` is PKCS#12.
    #[test]
    fn the_kotlin_servers_identity_is_read() {
        let fixture = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/kotlin-state");
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

    /// The same key and certificate in the store of JDKs before 9, as `keytool` writes one.
    #[test]
    fn an_old_jks_store_is_read() {
        let fixture = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/jks-state");
        let dir = scratch();
        fs::create_dir_all(&dir).unwrap();
        for name in ["server-id", "tls.password", "tls.jks"] {
            fs::copy(fixture.join(name), dir.join(name)).unwrap();
        }
        let identity = ServerIdentity::load_or_create(&dir).unwrap();
        assert_eq!(identity.fingerprint, fs::read_to_string(fixture.join("fingerprint")).unwrap().trim());
        assert!(identity.tls_config().is_ok());

        fs::write(dir.join("tls.password"), "not-the-password\n").unwrap();
        let error = ServerIdentity::load_or_create(&dir).err().unwrap().to_string();
        assert!(error.contains("does not open"), "{error}");
        fs::remove_dir_all(dir).unwrap();
    }
}
