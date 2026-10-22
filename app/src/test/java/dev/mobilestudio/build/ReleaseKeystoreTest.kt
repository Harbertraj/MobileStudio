package dev.mobilestudio.build

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore

class ReleaseKeystoreTest {
    private lateinit var base: File
    private lateinit var store: ReleaseKeystore
    private lateinit var project: File
    private val log = mutableListOf<String>()

    @Before fun setUp() {
        base = File.createTempFile("relks", "").also { it.delete(); it.mkdirs() }
        store = ReleaseKeystore(File(base, "signing"))
        project = File(base, "proj").also { it.mkdirs() }
    }
    @After fun tearDown() { base.deleteRecursively() }

    @Test fun generatesOnceAndReusesTheSameKey() {
        val first = store.load(project) { log += it }
        assertTrue(store.deviceKeystore.isFile)
        assertTrue(log.any { "generating" in it })
        log.clear()
        val second = store.load(project) { log += it }
        assertFalse(log.any { "generating" in it })
        assertEquals(first.chain.first().serialNumber, second.chain.first().serialNumber)
        assertEquals(first.key, second.key)
        first.chain.first().checkValidity()
    }

    @Test fun describeReportsTheSource() {
        assertTrue(store.describe(project).contains("will be generated"))
        store.load(project)
        assertTrue(store.describe(project).contains("this device"))
        File(project, "keystore.properties").writeText("x=y")
        assertTrue(store.describe(project).contains("keystore.properties"))
    }

    @Test fun projectKeystorePropertiesWin() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = SelfSignedCert.create(pair, "Mine", "Me")
        val p12 = File(project, "my.p12")
        KeyStore.getInstance("PKCS12").apply {
            load(null, null); setKeyEntry("mine", pair.private, "secret".toCharArray(), arrayOf(cert))
        }.store(p12.outputStream(), "secret".toCharArray())
        File(project, "keystore.properties").writeText("storeFile=my.p12\nstorePassword=secret\nkeyAlias=mine\n")

        val key = store.load(project)
        assertEquals("mine", key.alias)
        assertTrue(key.subject.contains("CN=Mine"))
        assertFalse("must not create a device key when the project has its own", store.deviceKeystore.exists())
    }

    @Test fun badProjectKeystoreGivesAReadableError() {
        File(project, "keystore.properties").writeText("storeFile=nope.p12\nstorePassword=x\n")
        val e = runCatching { store.load(project) }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(e!!.message!!.contains("nope.p12"))

        File(project, "keystore.properties").writeText("storeFile=nope.p12\n")
        assertTrue(runCatching { store.load(project) }.exceptionOrNull()!!.message!!.contains("storePassword"))
    }

    @Test fun exportWritesAKeystoreAndMatchingProperties() {
        assertTrue(store.export(File(base, "out")).isFailure) // nothing generated yet
        val original = store.load(project)
        val props = store.export(File(base, "out")).getOrThrow()
        assertTrue(props.isFile)
        // the exported files must work as a project override
        File(base, "out").copyRecursively(File(project, "keys"))
        File(project, "keystore.properties").writeText(
            props.readText().replace("storeFile=release.p12", "storeFile=keys/release.p12"))
        val again = store.load(project)
        assertEquals(original.chain.first().serialNumber, again.chain.first().serialNumber)
    }
}
