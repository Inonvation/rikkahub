package me.rerere.rikkahub.di

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.common.http.AcceptLanguageBuilder
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.AIRequestInterceptor
import me.rerere.rikkahub.data.ai.RequestLoggingInterceptor
import me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.api.RikkaHubAPI
import me.rerere.rikkahub.data.api.SponsorAPI
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.VocabularySettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_26_27
import me.rerere.rikkahub.data.db.migrations.Migration_27_28
import me.rerere.rikkahub.data.db.migrations.Migration_28_29
import me.rerere.rikkahub.data.db.migrations.Migration_29_30
import me.rerere.rikkahub.data.db.migrations.Migration_30_31
import me.rerere.rikkahub.data.db.migrations.Migration_31_32
import me.rerere.rikkahub.data.db.migrations.Migration_32_33
import me.rerere.rikkahub.data.db.migrations.Migration_33_34
import me.rerere.rikkahub.data.db.migrations.Migration_34_35
import me.rerere.rikkahub.data.db.migrations.Migration_35_36
import me.rerere.rikkahub.data.db.migrations.Migration_36_37
import me.rerere.rikkahub.data.db.migrations.Migration_37_38
import me.rerere.rikkahub.data.db.migrations.Migration_38_39
import me.rerere.rikkahub.data.db.dao.VocabularyDao
import me.rerere.rikkahub.data.db.dao.WrongQuestionDao
import me.rerere.rikkahub.data.db.dao.KnowledgeCardDao
import me.rerere.rikkahub.data.db.dao.NoteDao
import me.rerere.rikkahub.data.ai.tools.StudyDaoSet
import me.rerere.rikkahub.data.ai.tools.StudyTools
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.search.SearchService
import me.rerere.rikkahub.data.sync.S3Sync
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit

val dataSourceModule = module {
    single {
        SettingsStore(context = get(), scope = get())
    }

    single {
        VocabularySettingsStore(context = get())
    }

    single {
        val context: Context = get()
        Room.databaseBuilder(context, AppDatabase::class.java, "rikka_hub")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(Migration_6_7, Migration_11_12, Migration_13_14, Migration_14_15, Migration_15_16, Migration_26_27, Migration_27_28, Migration_28_29, Migration_29_30, Migration_30_31, Migration_31_32, Migration_32_33, Migration_33_34, Migration_34_35, Migration_35_36, Migration_36_37, Migration_37_38, Migration_38_39)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    val dictDir = SimpleDictManager.extractDict(context)
                    val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                    cursor.use {
                        if (it.moveToFirst()) {
                            val result = it.getString(0)
                            val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                            if (!success) {
                                android.util.Log.e(
                                    "DataSourceModule",
                                    "jieba_dict failed: $result, path=${dictDir.absolutePath}"
                                )
                            }
                        }
                    }
                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                            text,
                            node_id UNINDEXED,
                            message_id UNINDEXED,
                            conversation_id UNINDEXED,
                            title UNINDEXED,
                            update_at UNINDEXED,
                            tokenize = 'simple'
                        )
                        """.trimIndent()
                    )
                }
            })
            .openHelperFactory(
                RequerySQLiteOpenHelperFactory(
                    listOf(
                RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                    options.customExtensions.add(
                        SQLiteCustomExtension(
                            context.applicationInfo.nativeLibraryDir + "/libsimple",
                            null
                        )
                    )
                    options
                }
            )))
            .build()
    }

    single {
        AssistantTemplateLoader(settingsStore = get())
    }

    single {
        PebbleEngine.Builder()
            .loader(get<AssistantTemplateLoader>())
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
    }

    single { TemplateTransformer(engine = get(), settingsStore = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().groupDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().messageNodeDao()
    }

    single {
        get<AppDatabase>().managedFileDao()
    }

    single {
        get<AppDatabase>().favoriteDao()
    }

    single {
        get<AppDatabase>().workspaceDao()
    }

    single {
        get<AppDatabase>().folderDao()
    }

    single {
        get<AppDatabase>().knowledgeBaseDao()
    }

    single {
        get<AppDatabase>().knowledgeDocumentDao()
    }

    single {
        get<AppDatabase>().knowledgeChunkDao()
    }

    single {
        get<AppDatabase>().vocabularyDao()
    }

    single {
        get<AppDatabase>().wrongQuestionDao()
    }

    single {
        get<AppDatabase>().knowledgeCardDao()
    }

    single {
        get<AppDatabase>().noteDao()
    }

    single {
        get<AppDatabase>().subAgentUsageDao()
    }

    single {
        get<AppDatabase>().subAgentTaskDao()
    }

    single {
        StudyDaoSet(
            vocabularyDao = get(),
            noteDao = get(),
            wrongQuestionDao = get(),
            knowledgeCardDao = get(),
        )
    }

    single {
        StudyTools(
            studyDaoSet = get(),
            knowledgeChunkDao = get(),
        )
    }

    single {
        MessageFtsManager(get())
    }

    single { McpManager(settingsStore = get(), appScope = get(), filesManager = get(), appEventBus = get()) }

    single {
        GenerationHandler(
            context = get(),
            providerManager = get(),
            json = get(),
            memoryRepo = get()
        )
    }

    single<OkHttpClient> {
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

                if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                    requestBuilder.addHeader(HttpHeaders.UserAgent, "RikkaHub-Android/${BuildConfig.VERSION_NAME}")
                }

                chain.proceed(requestBuilder.build())
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val contentTypeHeader = request.header("Content-Type")
                if (
                    contentTypeHeader != null &&
                    contentTypeHeader.contains(";") &&
                    contentTypeHeader.substringBefore(";").trim().equals("application/json", ignoreCase = true)
                ) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .addInterceptor(AIRequestInterceptor())
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            })
            .build().also { SearchService.init(it, get()) }
    }

    single {
        SponsorAPI.create(get())
    }

    // AI 流式专用 client：仅 AI 请求（主对话 + 子代理）强制 HTTP/1.1，规避 HTTP/2
    // 的 RST_STREAM(CANCEL)（"stream was reset: CANCEL"）导致流式输出被掐断。
    // 基于主 client 派生，继承其拦截器配置；搜索/同步等其他请求仍走主 client 的默认 HTTP/2。
    single(named("aiClient")) {
        get<OkHttpClient>().newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    single {
        ProviderManager(client = get<OkHttpClient>(named("aiClient")), context = get())
    }

    single {
        WebDavSync(
            settingsStore = get(),
            json = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<HttpClient> {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(20, TimeUnit.SECONDS)
                    readTimeout(10, TimeUnit.MINUTES)
                    writeTimeout(120, TimeUnit.SECONDS)
                    followSslRedirects(true)
                    followRedirects(true)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    single {
        S3Sync(
            settingsStore = get(),
            json = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<Retrofit> {
        Retrofit.Builder()
            .baseUrl("https://api.rikka-ai.com")
            .addConverterFactory(get<Json>().asConverterFactory("application/json; charset=UTF8".toMediaType()))
            .build()
    }

    single<RikkaHubAPI> {
        get<Retrofit>().create(RikkaHubAPI::class.java)
    }
}
