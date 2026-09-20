package com.metrolist.music.netease

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.InternalDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NeteaseMigrationTest {
    @Test fun `version 38 migration preserves the music library and creates sync tables`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "netease-migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = Json.parseToJsonElement(File("schemas/com.metrolist.music.db.InternalDatabase/38.json").readText())
            .jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            schema.getValue("entities").jsonArray.forEach {
                val entity = it.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                old.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                    old.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("views").jsonArray.forEach {
                val view = it.jsonObject
                old.execSQL(view.getValue("createSql").jsonPrimitive.content.replace("\${VIEW_NAME}", view.getValue("viewName").jsonPrimitive.content))
            }
            schema.getValue("setupQueries").jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO playlist (id, name, isEditable, isLocal, isAutoSync) VALUES ('existing', 'Keep me', 1, 0, 0)")
            old.version = 38
        }
        val migrated = InternalDatabase.build(context, name, withPragmaCallback = false)
        try {
            assertNull(migrated.neteaseSyncDao.binding())
            migrated.openHelper.readableDatabase.query("SELECT name FROM playlist WHERE id = 'existing'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Keep me", it.getString(0))
            }
            assertEquals(39, migrated.openHelper.readableDatabase.version)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
