package tablekeeper.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Password hashes: Argon2id with OWASP's parameters (19 MiB, 2 passes, 1 lane), stored as a PHC string
 * (`$argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>`). [verify] reads the parameters from the string, so they can be
 * raised later without breaking stored hashes.
 */
object Passwords {
    private const val MEMORY_KB = 19 * 1024
    private const val ITERATIONS = 2
    private const val PARALLELISM = 1
    private const val SALT_BYTES = 16
    private const val HASH_BYTES = 32
    private val random = SecureRandom()
    private val encoder = Base64.getEncoder().withoutPadding()

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = argon2(password, salt, MEMORY_KB, ITERATIONS, PARALLELISM, HASH_BYTES)
        return "\$argon2id\$v=19\$m=$MEMORY_KB,t=$ITERATIONS,p=$PARALLELISM\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
    }

    /** Whether [password] gives [stored]; false for anything that isn't an Argon2id PHC string. */
    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')   // "", "argon2id", "v=19", "m=…,t=…,p=…", salt, hash
        if (parts.size != 6 || parts[1] != "argon2id" || parts[2] != "v=19") return false
        return runCatching {
            val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
            val decoder = Base64.getDecoder()
            val expected = decoder.decode(parts[5])
            val actual = argon2(password, decoder.decode(parts[4]), params.getValue("m"), params.getValue("t"), params.getValue("p"), expected.size)
            MessageDigest.isEqual(expected, actual)
        }.getOrDefault(false)
    }

    private fun argon2(password: String, salt: ByteArray, memoryKb: Int, iterations: Int, parallelism: Int, length: Int): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt).withMemoryAsKB(memoryKb).withIterations(iterations).withParallelism(parallelism).build())
        return ByteArray(length).also { generator.generateBytes(password.toByteArray(Charsets.UTF_8), it) }
    }
}
