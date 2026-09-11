package dev.octoshrimpy.quik.worker

import android.app.Application
import android.content.Context
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import androidx.work.impl.workers.ConstraintTrackingWorker
import com.f2prateek.rx.preferences2.RxSharedPreferences
import dev.octoshrimpy.quik.blocking.BlockingClient
import dev.octoshrimpy.quik.interactor.UpdateBadge
import dev.octoshrimpy.quik.manager.ActiveConversationManager
import dev.octoshrimpy.quik.manager.NotificationManager
import dev.octoshrimpy.quik.manager.ShortcutManager
import dev.octoshrimpy.quik.manager.WidgetManager
import dev.octoshrimpy.quik.repository.ContactRepository
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageContentFilterRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.repository.ScheduledMessageRepository
import dev.octoshrimpy.quik.repository.SyncRepository
import dev.octoshrimpy.quik.util.Preferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class InjectionWorkerFactoryTest {
    private val conversations = mock(ConversationRepository::class.java)
    private val blocking = mock(BlockingClient::class.java)
    private val messages = mock(MessageRepository::class.java)
    private val shortcuts = mock(ShortcutManager::class.java)
    private val widgets = mock(WidgetManager::class.java)
    private val scheduled = mock(ScheduledMessageRepository::class.java)
    private val notifications = mock(NotificationManager::class.java)
    private val active = mock(ActiveConversationManager::class.java)
    private val sync = mock(SyncRepository::class.java)
    private val filters = mock(MessageContentFilterRepository::class.java)
    private val contacts = mock(ContactRepository::class.java)
    private lateinit var app: Application
    private lateinit var prefs: Preferences
    private lateinit var badge: UpdateBadge
    private lateinit var factory: InjectionWorkerFactory
    private lateinit var parameters: WorkerParameters

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        val shared = app.getSharedPreferences("worker-factory", Context.MODE_PRIVATE)
        prefs = Preferences(app, RxSharedPreferences.create(shared), shared)
        badge = UpdateBadge(shortcuts, widgets)
        factory = InjectionWorkerFactory(conversations, blocking, prefs, messages, badge, shortcuts,
            scheduled, notifications, active, sync, filters, contacts)
        parameters = WorkerParameters(UUID.randomUUID(), Data.EMPTY, emptyList(),
            WorkerParameters.RuntimeExtras(), 0, 0, Executor { it.run() },
            mock(TaskExecutor::class.java), factory, mock(ProgressUpdater::class.java),
            mock(ForegroundUpdater::class.java))
    }

    @Test fun realConstraintWrapperReproducesOldCastAndUsesDefaultFallback() {
        val type = ConstraintTrackingWorker::class.java
        assertTrue(ListenableWorker::class.java.isAssignableFrom(type))
        assertFalse(Worker::class.java.isAssignableFrom(type))
        try { type.asSubclass(Worker::class.java); fail("旧强转必须复现异常") }
        catch (expected: ClassCastException) { }
        assertNull(factory.createWorker(app, type.name, parameters))
        val wrapper = factory.createWorkerWithDefaultFallback(app, type.name, parameters)
        assertTrue(wrapper is ConstraintTrackingWorker)
        assertEquals(parameters.id, wrapper!!.id)
        assertSame(app, wrapper.applicationContext)
    }

    class UnownedWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
        override fun doWork(): Result = throw AssertionError("测试禁止执行后台业务")
    }

    @Test fun unownedWorkerAndUnknownClassAreDelegatedWithoutReflection() {
        assertNull(factory.createWorker(app, "missing.worker.DoesNotExist", parameters))
        assertNull(factory.createWorker(app, String::class.java.name, parameters))
        assertNull(factory.createWorker(app, UnownedWorker::class.java.name, parameters))
        assertTrue(factory.createWorkerWithDefaultFallback(app, UnownedWorker::class.java.name, parameters) is UnownedWorker)
    }

    @Test fun housekeepingReceivesItsRepositoryAndIsNotExecutedDuringCreation() {
        val worker = factory.createWorker(app, HousekeepingWorker::class.java.name, parameters) as HousekeepingWorker
        assertSame(scheduled, worker.scheduledMessageRepository)
        assertEquals(parameters.id, worker.id)
        verifyZeroInteractions(scheduled)
    }

    @Test fun smsRetainsEveryNotificationFilteringAndConversationDependency() {
        val worker = factory.createWorker(app, ReceiveSmsWorker::class.java.name, parameters) as ReceiveSmsWorker
        assertSame(conversations, worker.conversationRepo)
        assertSame(blocking, worker.blockingClient)
        assertSame(prefs, worker.prefs)
        assertSame(messages, worker.messageRepo)
        assertSame(shortcuts, worker.shortcutManager)
        assertSame(notifications, worker.notificationManager)
        assertSame(badge, worker.updateBadge)
        assertSame(filters, worker.filterRepo)
        assertSame(contacts, worker.contactsRepo)
        verifyZeroInteractions(conversations, blocking, messages, shortcuts, notifications, filters, contacts)
    }

    @Test fun mmsRetainsSyncVisibilityAndNotificationDependencies() {
        val worker = factory.createWorker(app, ReceiveMmsWorker::class.java.name, parameters) as ReceiveMmsWorker
        assertSame(sync, worker.syncRepo)
        assertSame(active, worker.activeConversationManager)
        assertSame(conversations, worker.conversationRepo)
        assertSame(blocking, worker.blockingClient)
        assertSame(prefs, worker.prefs)
        assertSame(messages, worker.messageRepo)
        assertSame(shortcuts, worker.shortcutManager)
        assertSame(notifications, worker.notificationManager)
        assertSame(badge, worker.updateBadge)
        assertSame(filters, worker.filterRepo)
        assertSame(contacts, worker.contactsRepo)
        verifyZeroInteractions(sync, active, conversations, blocking, messages, shortcuts, notifications, filters, contacts)
    }

    @Test fun retriesCreateFreshWorkersAndDefaultFactoryStillUsesInjection() {
        for (type in listOf(HousekeepingWorker::class.java, ReceiveSmsWorker::class.java, ReceiveMmsWorker::class.java)) {
            val first = factory.createWorkerWithDefaultFallback(app, type.name, parameters)
            val second = factory.createWorkerWithDefaultFallback(app, type.name, parameters)
            assertNotSame(first, second)
            assertFalse(first!!.isUsed)
        }
        val worker = factory.createWorkerWithDefaultFallback(app, ReceiveSmsWorker::class.java.name, parameters) as ReceiveSmsWorker
        assertSame(notifications, worker.notificationManager)
    }
}
