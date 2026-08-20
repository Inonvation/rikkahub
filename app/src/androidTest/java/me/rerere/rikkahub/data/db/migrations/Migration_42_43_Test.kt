package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_42_43_Test {
    private val TEST_DB = "migration-test-42-43"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate42To43_addsModeColumnWithEmptyDefault() {
        helper.createDatabase(TEST_DB, 42).apply { close() }

        val db = helper.runMigrationsAndValidate(TEST_DB, 43, true, Migration_42_43)

        val cursor = db.query("SELECT * FROM conversationentity LIMIT 0")
        val columns = cursor.columnNames.toList()
        cursor.close()
        assertTrue("conversationentity should have 'mode' column", columns.contains("mode"))

        db.close()
    }
}
