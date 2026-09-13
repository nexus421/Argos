package bayern.kickner.argos.web

import kotnexlib.crypto.Argon2Helper

/**
 * Hashes a status page password the way [Argon2Helper.verify] expects it in `basicAuth.passwordHash`.
 */
fun hashPassword(password: String): String = Argon2Helper.hash(password.toCharArray())
