package au.com.elied.vitalsignal.storage

import au.com.elied.vitalsignal.transport.ReplayClaimResult
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedAcknowledgementReplayStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun claimSurvivesRestartAndCannotMoveToAnotherBatch() {
        val root = temporaryFolder.newFolder("ack-replay").toPath()
        val key = KeyGenerator.getInstance("AES").run { init(256); generateKey() }
        fun reopen() = EncryptedAcknowledgementReplayStore(
            EncryptedAppendOnlyRecordStore(root, key, "watch-ack-replay-v1", SecureRandom()),
        )

        assertEquals(ReplayClaimResult.Claimed, reopen().claim("receipt-1", "batch-1"))
        assertEquals(ReplayClaimResult.AlreadyClaimed, reopen().claim("receipt-1", "batch-1"))
        assertEquals(ReplayClaimResult.StoreFailure, reopen().claim("receipt-1", "batch-2"))
    }
}
