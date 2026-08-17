package me.rerere.rikkahub.di

import java.lang.reflect.Proxy
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerLocalState
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerSessionBindingRepository
import me.rerere.rikkahub.data.db.dao.CodexAppServerSessionBindingDao
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.koin.core.context.koinApplication
import org.koin.dsl.module

class CodexRepositoryModuleTest {
    @Test
    fun `codex local state is resolvable by its interface`() {
        val app = koinApplication {
            modules(
                module {
                    single<ConversationDAO> { interfaceStub() }
                    single<WorkspaceDAO> { interfaceStub() }
                    single<CodexAppServerSessionBindingDao> { interfaceStub() }
                },
                repositoryModule,
            )
        }

        try {
            assertNotNull(app.koin.get<CodexAppServerLocalState>())
            assertNotNull(app.koin.get<CodexAppServerSessionBindingRepository>())
        } finally {
            app.close()
        }
    }

    private inline fun <reified T : Any> interfaceStub(): T {
        val type = T::class.java
        require(type.isInterface)
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when (method.name) {
                "toString" -> "Stub<${type.simpleName}>"
                "hashCode" -> System.identityHashCode(type)
                "equals" -> false
                else -> throw UnsupportedOperationException("Unexpected call to ${type.simpleName}.${method.name}")
            }
        } as T
    }
}
