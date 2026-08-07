package com.example.groceriesapp.data.network

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * REWE's mobile API gateway demands a client certificate at the TLS handshake, independent of the
 * bearer token, so the socket factory has to carry the imported identity.
 */
class ReweMtlsIdentity private constructor(
    val sslSocketFactory: SSLSocketFactory,
    val trustManager: X509TrustManager,
) {
    companion object {
        private const val KEY_ALIAS = "rewe-client"

        fun from(certificatePem: String, privateKeyPem: String): ReweMtlsIdentity {
            val certificate = parseCertificate(certificatePem)
            val privateKey = parsePrivateKey(privateKeyPem)

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry(KEY_ALIAS, privateKey, CharArray(0), arrayOf(certificate))
            }
            val keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                    init(keyStore, CharArray(0))
                }
            val trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                    init(null as KeyStore?)
                }
            val trustManager = trustManagerFactory.trustManagers
                .filterIsInstance<X509TrustManager>()
                .first()

            val sslContext = SSLContext.getInstance("TLS").apply {
                init(keyManagerFactory.keyManagers, arrayOf(trustManager), null)
            }
            return ReweMtlsIdentity(sslContext.socketFactory, trustManager)
        }

        private fun parseCertificate(pem: String): X509Certificate {
            val der = decodePemBlock(pem, "CERTIFICATE")
                ?: throw IllegalArgumentException("No CERTIFICATE block found in the selected file")
            return ByteArrayInputStream(der).use { stream ->
                CertificateFactory.getInstance("X.509").generateCertificate(stream)
                    as X509Certificate
            }
        }

        private fun parsePrivateKey(pem: String): java.security.PrivateKey {
            if (decodePemBlock(pem, "RSA PRIVATE KEY") != null) {
                throw IllegalArgumentException(
                    "PKCS#1 keys are not supported. Convert it first: " +
                        "openssl pkcs8 -topk8 -nocrypt -in private.key -out private_pkcs8.key",
                )
            }
            val der = decodePemBlock(pem, "PRIVATE KEY")
                ?: throw IllegalArgumentException("No PRIVATE KEY block found in the selected file")
            val spec = PKCS8EncodedKeySpec(der)
            // The extracted REWE identity is RSA, but EC costs nothing to accept.
            return runCatching { KeyFactory.getInstance("RSA").generatePrivate(spec) }
                .getOrElse { KeyFactory.getInstance("EC").generatePrivate(spec) }
        }

        /** The exported files carry OpenSSL "Bag Attributes" preamble around the PEM block. */
        private fun decodePemBlock(pem: String, type: String): ByteArray? {
            val begin = "-----BEGIN $type-----"
            val end = "-----END $type-----"
            val start = pem.indexOf(begin)
            if (start < 0) return null
            val stop = pem.indexOf(end, start)
            if (stop < 0) return null
            val body = pem.substring(start + begin.length, stop)
                .filterNot(Char::isWhitespace)
            return Base64.getDecoder().decode(body)
        }
    }
}
