package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.Logger
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Persists the desktop agents' identity as one PEM file: a PKCS#8 private key followed by the
 * certificate. PEM rather than PKCS#12 so the Rust agent reads the same file without a keystore
 * password; the file is protected by owner-only permissions instead.
 */
object AgentIdentityStore {
    const val FILE_NAME = "agent-identity.pem"

    /**
     * The per-user default: `~/Library/Application Support/RemoteBLE/` on macOS,
     * `%APPDATA%\RemoteBLE\` on Windows, `$XDG_CONFIG_HOME/remoteble/` (or `~/.config/remoteble/`)
     * elsewhere.
     */
    fun defaultPath(
        osName: String = System.getProperty("os.name").orEmpty(),
        env: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home"),
    ): Path {
        val os = osName.lowercase()
        val dir = when {
            os.startsWith("mac") || os.contains("darwin") -> Paths.get(home, "Library", "Application Support", "RemoteBLE")
            os.startsWith("windows") -> Paths.get(env("APPDATA")?.takeIf { it.isNotBlank() } ?: home, "RemoteBLE")
            else -> Paths.get(env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: Paths.get(home, ".config").toString(), "remoteble")
        }
        return dir.resolve(FILE_NAME)
    }

    /**
     * Loads the identity at [path], creating it on first use. [reset] discards an existing one
     * first, which changes the fingerprint and makes every paired client fail with an identity
     * error until it pairs again: that is the point of a reset.
     */
    fun loadOrCreate(path: Path, reset: Boolean = false): AgentTlsIdentity {
        if (reset) Files.deleteIfExists(path)
        if (Files.exists(path)) return read(path)
        val created = generate()
        write(path, created)
        // Another agent process may have created it in the same instant; whichever file won the
        // move is the identity, so read it back rather than trusting the in-memory copy.
        return read(path).also {
            Logger.info(LogTags.AGENT) { "created agent identity ${it.fingerprint} at $path" }
        }
    }

    internal fun generate(): AgentTlsIdentity {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val serial = ByteArray(16).also(SecureRandom()::nextBytes)
        val der = SelfSignedCertificate.build(
            spki = keys.public.encoded,
            serial = serial,
            // A day back, so a client whose clock runs slightly behind still sees a valid start.
            notBeforeEpochSeconds = System.currentTimeMillis() / 1000 - 86_400,
            sign = { tbs -> Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(tbs); sign() } },
        )
        return AgentTlsIdentity(keys.private, parseCertificate(der))
    }

    private fun read(path: Path): AgentTlsIdentity {
        val pem = Files.readString(path)
        looseMode(path)?.let { mode ->
            // A warning rather than a refusal: a container's mounted secret is often world-readable
            // and owned by someone else, so the agent could neither fix it nor start.
            Logger.warn(LogTags.AGENT) {
                "agent identity $path is readable by other users ($mode); anyone who can read it can " +
                    "impersonate this agent. Restrict it: chmod 600 $path"
            }
        }
        val key = pemBlock(pem, "PRIVATE KEY") ?: error("$path has no PRIVATE KEY block")
        val cert = pemBlock(pem, "CERTIFICATE") ?: error("$path has no CERTIFICATE block")
        val identity = AgentTlsIdentity(
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(key)),
            parseCertificate(cert),
        )
        // Otherwise every handshake would fail, far from the cause; the Rust agent refuses it too.
        check(belongTogether(identity)) { "$path is unusable: the private key does not belong to the certificate" }
        return identity
    }

    /** The file's permissions if group or others may access it; `null` where POSIX ones don't exist. */
    internal fun looseMode(path: Path): String? {
        if (!path.fileSystem.supportedFileAttributeViews().contains("posix")) return null
        val permissions = Files.getPosixFilePermissions(path)
        return PosixFilePermissions.toString(permissions).takeIf { permissions.any { it !in OWNER_ONLY } }
    }

    private val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)

    /** Whether the certificate's key verifies a signature made with the private key. */
    private fun belongTogether(identity: AgentTlsIdentity): Boolean {
        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(identity.privateKey)
            update(challenge)
            sign()
        }
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(identity.certificate.publicKey)
            update(challenge)
            verify(signature)
        }
    }

    private fun write(path: Path, identity: AgentTlsIdentity) {
        val dir = path.toAbsolutePath().parent
        Files.createDirectories(dir)
        val posix = dir.fileSystem.supportedFileAttributeViews().contains("posix")
        val temp = if (posix) {
            Files.createTempFile(dir, ".agent-identity", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createTempFile(dir, ".agent-identity", ".tmp")
        }
        Files.writeString(
            temp,
            pem("PRIVATE KEY", pkcs8WithPublicKey(identity)) + pem("CERTIFICATE", identity.certificate.encoded),
        )
        // Publish without ever replacing an existing file, so a concurrent creator's identity is
        // kept. An atomic move would not do: on POSIX it silently replaces the target. A hard link
        // is atomic and fails if the name exists; where links are unsupported, a plain move still
        // refuses an existing target, with only a check-then-rename window left.
        try {
            try {
                Files.createLink(path, temp)
            } catch (_: UnsupportedOperationException) {
                Files.move(temp, path)
            } catch (e: FileSystemException) {
                if (e is FileAlreadyExistsException) throw e
                Files.move(temp, path)
            }
        } catch (_: FileAlreadyExistsException) {
            // Lost the race; the winner's file is the identity.
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * The key as PKCS#8 with RFC 5915's optional public key included. The JDK's own encoding omits
     * it, and `ring`, through which the Rust agent reads this same file, refuses a key without it,
     * so a JVM-created identity would otherwise be unusable to the other desktop agent.
     */
    internal fun pkcs8WithPublicKey(identity: AgentTlsIdentity): ByteArray {
        val privateKey = identity.privateKey as ECPrivateKey
        val publicKey = identity.certificate.publicKey as ECPublicKey
        val point = byteArrayOf(0x04) + fixed(publicKey.w.affineX) + fixed(publicKey.w.affineY)
        val ecPrivateKey = Der.seq(
            Der.integer(byteArrayOf(1)),
            Der.octetString(fixed(privateKey.s)),
            Der.explicit(1, Der.bitString(point)),
        )
        return Der.seq(
            Der.integer(byteArrayOf(0)),
            Der.seq(Der.oid(EC_PUBLIC_KEY), Der.oid(SECP256R1)),
            Der.octetString(ecPrivateKey),
        )
    }

    /** A P-256 field element as exactly 32 big-endian bytes, as RFC 5915 and SEC 1 require. */
    private fun fixed(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(P256_BYTES - bytes.size) + bytes
    }

    private const val EC_PUBLIC_KEY = "1.2.840.10045.2.1"
    private const val SECP256R1 = "1.2.840.10045.3.1.7"
    private const val P256_BYTES = 32

    private fun parseCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    private fun pem(label: String, der: ByteArray): String =
        "-----BEGIN $label-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
            "\n-----END $label-----\n"

    private fun pemBlock(pem: String, label: String): ByteArray? {
        val begin = "-----BEGIN $label-----"
        val start = pem.indexOf(begin).takeIf { it >= 0 } ?: return null
        val end = pem.indexOf("-----END $label-----", start).takeIf { it >= 0 } ?: return null
        return Base64.getMimeDecoder().decode(pem.substring(start + begin.length, end))
    }
}
