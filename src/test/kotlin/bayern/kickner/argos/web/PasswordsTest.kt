package bayern.kickner.argos.web

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotnexlib.crypto.Argon2Helper

class PasswordsTest : FunSpec({

    test("hashPassword produces a hash that the Basic Auth verification accepts") {
        val hash = hashPassword("secret")

        Argon2Helper.verify("secret".toCharArray(), hash).getOrDefault(false) shouldBe true
        Argon2Helper.verify("wrong".toCharArray(), hash).getOrDefault(true) shouldBe false
    }

    test("hashPassword salts, so two hashes of the same password differ") {
        hashPassword("secret") shouldNotBe hashPassword("secret")
    }
})
