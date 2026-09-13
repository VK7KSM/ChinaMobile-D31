package dev.octoshrimpy.quik.feature.phone

import android.app.Application
import android.content.pm.ProviderInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class D31UnreadProviderTest {
    private fun provider(app: Application) = D31UnreadProvider().apply {
        attachInfo(app, ProviderInfo().apply { authority = "${app.packageName}.unread" })
    }

    @Test fun coldMainThreadQueryReturnsUnknownWithoutOpeningEitherDatabase() {
        val app = RuntimeEnvironment.getApplication()
        val provider = provider(app)
        val uri = D31UnreadProvider.countUri(app)
        assertNull(provider.query(uri, null, null, null, null))
        assertNull(provider.query(uri, arrayOf("total"), null, null, null))
        assertFalse(app.getDatabasePath("d31_sip_messages.db").exists())
    }

    @Test fun coldBinderThreadQueryDoesNotWaitForApplicationInitialization() {
        val app = RuntimeEnvironment.getApplication()
        val provider = provider(app)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<android.database.Cursor?> {
                provider.query(D31UnreadProvider.countUri(app), null, null, null, null)
            }
            assertNull(result.get(1, TimeUnit.SECONDS))
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun failedRealmInitializationDoesNotPublishZeroCounts() {
        val app = RuntimeEnvironment.getApplication()
        val provider = provider(app)
        try {
            D31UnreadProvider.observeSim(app)
            fail("未配置Realm时不得宣告就绪")
        } catch (_: IllegalStateException) {
            assertNull(provider.query(D31UnreadProvider.countUri(app), null, null, null, null))
        }
    }
}
