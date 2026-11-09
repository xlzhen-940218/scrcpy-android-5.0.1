package org.las2mile.scrcpy.adb

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date

object AdbKeyManager {
    private const val TAG = "AdbKeyManager"
    private const val PRIV_KEY_FILE = "priv.key"
    private const val CERT_FILE = "cert.der"
    private const val PUB_KEY_FILE = "pub.key"

    class AdbKeyPair(val privateKey: PrivateKey, val certificate: X509Certificate)

    @Volatile
    private var cachedKeyPair: AdbKeyPair? = null

    @JvmStatic
    @Synchronized
    @Throws(Exception::class)
    fun getKeyPair(context: Context): AdbKeyPair {
        cachedKeyPair?.let { return it }

        val privFile = context.getFileStreamPath(PRIV_KEY_FILE)
        val certFile = context.getFileStreamPath(CERT_FILE)

        var privateKey: PrivateKey? = null
        var cert: X509Certificate? = null

        if (privFile.exists() && privFile.length() > 0) {
            try {
                val privBytes = readFile(privFile)
                val spec = PKCS8EncodedKeySpec(privBytes)
                val kf = KeyFactory.getInstance("RSA")
                privateKey = kf.generatePrivate(spec)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load private key, regenerating", e)
                privateKey = null
            }
        }

        if (privateKey != null && certFile.exists() && certFile.length() > 0) {
            try {
                val cf = CertificateFactory.getInstance("X.509")
                FileInputStream(certFile).use { fis ->
                    cert = cf.generateCertificate(fis) as X509Certificate
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load certificate, recreating cert", e)
                cert = null
            }
        }

        if (privateKey != null && cert == null) {
            // Private key exists but certificate is missing: reconstruct public key and sign cert
            try {
                val privCrt = privateKey as RSAPrivateCrtKey
                val pubSpec = RSAPublicKeySpec(privCrt.modulus, privCrt.publicExponent)
                val kf = KeyFactory.getInstance("RSA")
                val pubKey = kf.generatePublic(pubSpec)
                val newCert = generateCertificate(privateKey, pubKey)
                writeFile(certFile, newCert.encoded)
                cert = newCert
            } catch (e: Exception) {
                Log.e(TAG, "Failed to regenerate cert from existing private key", e)
                privateKey = null
            }
        }

        if (privateKey == null || cert == null) {
            // Generate a fresh RSA 2048 keypair and certificate
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            val newPrivKey = kp.private
            val newCert = generateCertificate(newPrivKey, kp.public)

            writeFile(privFile, newPrivKey.encoded)
            writeFile(certFile, newCert.encoded)

            // Also write pub.key for backward compatibility with legacy adblib
            val pubFile = context.getFileStreamPath(PUB_KEY_FILE)
            val pubBase64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
            writeFile(pubFile, ("$pubBase64 scrcpy-android\u0000").toByteArray(StandardCharsets.UTF_8))

            privateKey = newPrivKey
            cert = newCert
        }

        val result = AdbKeyPair(privateKey, cert!!)
        cachedKeyPair = result
        return result
    }

    private fun generateCertificate(privKey: PrivateKey, pubKey: PublicKey): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 86400000L)
        val notAfter = Date(now + 100L * 365L * 86400000L) // valid for 100 years
        val serial = BigInteger.valueOf(now)
        val subject = X500Name("CN=scrcpy-android")

        val subPubKeyInfo = SubjectPublicKeyInfo.getInstance(pubKey.encoded)
        val certBuilder = X509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            subPubKeyInfo
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privKey)
        val holder = certBuilder.build(signer)
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun readFile(file: File): ByteArray {
        val data = ByteArray(file.length().toInt())
        FileInputStream(file).use { fis ->
            var offset = 0
            while (offset < data.size) {
                val read = fis.read(data, offset, data.size - offset)
                if (read < 0) break
                offset += read
            }
        }
        return data
    }

    private fun writeFile(file: File, data: ByteArray) {
        FileOutputStream(file).use { fos ->
            fos.write(data)
            fos.flush()
        }
    }
}

